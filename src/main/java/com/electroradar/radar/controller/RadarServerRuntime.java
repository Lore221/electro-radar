package com.electroradar.radar.controller;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkPolicy;
import com.electroradar.radar.network.RadarNetworkManager;
import com.electroradar.radar.network.RadarObservationPlan;
import com.electroradar.radar.network.RadarObservationSource;
import com.electroradar.radar.network.RegistrationToken;
import com.electroradar.radar.network.ObservationWindowSchedule;
import com.electroradar.radar.visual.RadarSectorOrder;
import com.electroradar.radar.visual.RadarVisualProfile;

/** Контроллер хранит идентичность, а построение плана и обход вызывает общий Pre-координатор. */
final class RadarServerRuntime implements RadarObservationSource {
    private static final String NETWORK_ID_TAG = "NetworkId";
    private static final String SECTOR_ORDER_TAG = "SectorOrderVariant";
    /** Память забывает подтверждение после трёх посещений именно его геометрического сектора. */
    private static final NetworkPolicy POLICY = new NetworkPolicy(3L);

    private NetworkId networkId;
    private boolean loadedNetworkId;
    private int sectorOrderVariant;
    private RegistrationToken token;
    private EndpointKey.Static endpoint;
    private RadarControllerBlockEntity owner;
    private RadarAreaGeometry.GeometrySnapshot cachedGeometry;
    private GeometryKey cachedKey;
    private RadarObservationPlan lastPreparedPlan;
    private long geometryRevision;
    private boolean registrationFailureLogged;
    private long lastLimitWarningRevision = Long.MIN_VALUE;

    public void read(CompoundTag tag) {
        int savedOrder = tag.getInt(SECTOR_ORDER_TAG);
        sectorOrderVariant = RadarSectorOrder.validVariant(savedOrder) ? savedOrder : 0;
        if (tag.hasUUID(NETWORK_ID_TAG)) {
            networkId = new NetworkId(tag.getUUID(NETWORK_ID_TAG));
            loadedNetworkId = true;
        } else {
            networkId = null;
            loadedNetworkId = false;
        }
        resetTransientState();
    }

    public void write(CompoundTag tag) {
        if (networkId != null) tag.putUUID(NETWORK_ID_TAG, networkId.value());
        if (RadarSectorOrder.validVariant(sectorOrderVariant)) tag.putInt(SECTOR_ORDER_TAG, sectorOrderVariant);
    }

    /** Новый token и новый план создаются при каждой загрузке BlockEntity. */
    public void onLoad(RadarControllerBlockEntity owner) {
        resetTransientState();
        this.owner = owner;
        if (owner.getLevel() instanceof ServerLevel level) ensureSectorOrder(owner, level);
    }

    private void resetTransientState() {
        token = null;
        endpoint = null;
        cachedGeometry = null;
        cachedKey = null;
        lastPreparedPlan = null;
        registrationFailureLogged = false;
    }

    public NetworkId networkId() { return networkId; }

    public NetworkId getOrCreateNetworkIdForBinding(RadarControllerBlockEntity owner) {
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Radar NetworkId is only available on logical server");
        }
        endpoint = endpoint(level, owner.getBlockPos());
        if (networkId == null) {
            networkId = NetworkId.create();
            loadedNetworkId = false;
            owner.setChanged();
            RadarDiagnostics.log("network_id_created", networkId, endpoint, null, tick(level),
                    "reason=item_binding_before_first_server_tick");
        }
        return networkId;
    }

    public void applyNetworkIdFromItem(RadarControllerBlockEntity owner, NetworkId replacement) {
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Radar NetworkId can only be applied on logical server");
        }
        if (token != null) throw new IllegalStateException("Cannot replace a registered radar NetworkId");
        networkId = replacement;
        loadedNetworkId = false;
        endpoint = endpoint(level, owner.getBlockPos());
        invalidateProfile();
        owner.setChanged();
        RadarDiagnostics.log("radar_item_network_id_applied", networkId, endpoint, null, tick(level),
                "source=bound_item");
    }

    /** Отключение сперва отзывает старые права и измерения, затем регистрирует новый NetworkId. */
    public void reconfigureNetwork(RadarControllerBlockEntity owner, NetworkId replacement) {
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            throw new IllegalStateException("Radar NetworkId can only be changed on logical server");
        }
        NetworkId next = replacement == null ? NetworkId.create() : replacement;
        if (next.equals(networkId)) return;
        NetworkId previous = networkId;
        unregister("linker_reconfiguration", owner);
        resetTransientState();
        this.owner = owner;
        networkId = next;
        loadedNetworkId = false;
        owner.setChanged();
        register(level, owner.getBlockPos());
        RadarDiagnostics.log("radar_network_reconfigured", networkId, endpoint, null, tick(level),
                "previous=" + previous + " isolated=" + (replacement == null));
    }

    /** BlockEntity ticker обслуживает lifecycle и регистрацию; сканирования здесь больше нет. */
    public void tick(RadarControllerBlockEntity owner, ServerLevel level) {
        this.owner = owner;
        ensureNetworkId(owner, level);
        ensureSectorOrder(owner, level);
        register(level, owner.getBlockPos());
    }

    @Override
    public Optional<RadarObservationPlan> createObservationPlan(RadarServerConfig.Snapshot parameters) {
        RadarControllerBlockEntity controller = owner;
        if (controller == null || controller.isRemoved()
                || !(controller.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(controller.getBlockPos())
                || !(controller.getBlockState().getBlock() instanceof RadarControllerBlock)
                || !controller.assembly().isReadyForScanning(controller)) return Optional.empty();
        ensureSectorOrder(controller, level);
        RadarMode mode = controller.mode();
        RadarApertureKind kind = controller.assembly().apertureKind();
        int apertureCount = controller.assembly().apertureCount();
        if (kind == RadarApertureKind.OVERVIEW && apertureCount <= 0) return Optional.empty();
        RadarAreaGeometry.GeometrySnapshot geometry = geometry(
                controller, level, mode, kind, apertureCount, parameters);
        int activeLimit = kind == RadarApertureKind.OVERVIEW
                ? parameters.overview().maxModules() : parameters.maxPanels();
        if (apertureCount > activeLimit && lastLimitWarningRevision != parameters.revision()) {
            lastLimitWarningRevision = parameters.revision();
            RadarDiagnostics.log("assembly_limit_reduced", networkId, endpoint, null, tick(level),
                    "aperture=" + kind.serializedName() + " captured=" + apertureCount
                            + " activeLimit=" + activeLimit + " action=range_uses_limit_assembly_kept");
        }
        RadarObservationPlan previous = lastPreparedPlan;
        if (previous != null && previous.geometryRevision() == geometryRevision
                && previous.sectorOrderVariant() == sectorOrderVariant
                && previous.configRevision() == parameters.revision()) return Optional.of(previous);
        RadarObservationPlan plan;
        if (previous != null && previous.geometryRevision() == geometryRevision
                && previous.sectorOrderVariant() == sectorOrderVariant) {
            // D может измениться при reload, хотя SAT/профиль/перестановка геометрически прежние.
            plan = new RadarObservationPlan(level.dimension().location(), previous.profile(), previous.sectors(),
                    sectorOrderVariant, geometryRevision, parameters.revision(), RadarTargetFilter.ELIGIBLE,
                    previous.schedule());
        } else {
            List<RadarObservationPlan.Sector> sectors = geometry.sectors().stream()
                    .map(sector -> new RadarObservationPlan.Sector(sector.index(), sector.area())).toList();
            ObservationWindowSchedule schedule = kind == RadarApertureKind.OVERVIEW
                    ? OverviewRadarSchedule.create(sectorOrderVariant)
                    : ObservationWindowSchedule.sectorCycle(geometry.profile().sectorCount(), sectorOrderVariant);
            plan = new RadarObservationPlan(level.dimension().location(), geometry.profile(), sectors,
                    sectorOrderVariant, geometryRevision, parameters.revision(),
                    RadarTargetFilter.ELIGIBLE, schedule);
        }
        lastPreparedPlan = plan;
        RadarDiagnostics.log("radar_profile_published", networkId, endpoint, null, tick(level),
                "geometryRevision=" + geometryRevision + " configRevision=" + parameters.revision()
                        + " mode=" + mode + " aperture=" + kind.serializedName()
                        + " apertureCount=" + apertureCount + " range=" + geometry.range() + " angle="
                        + geometry.horizontalAngle() + " minY=" + geometry.minYOffset() + " maxY="
                        + geometry.maxYOffset() + " sectors=" + plan.sectors().size()
                        + " orderVariant=" + sectorOrderVariant);
        return Optional.of(plan);
    }

    @Override
    public boolean isReadyForObservation(RadarObservationPlan plan) {
        RadarControllerBlockEntity controller = owner;
        if (plan == null || controller == null || controller.isRemoved() || controller.getLevel() == null
                || !(controller.getLevel() instanceof ServerLevel level)
                || !level.dimension().location().equals(plan.dimension())
                || !level.hasChunkAt(controller.getBlockPos())
                || !(controller.getBlockState().getBlock() instanceof RadarControllerBlock)
                || !controller.assembly().isReadyForScanning(controller)
                || networkId == null || endpoint == null
                || !endpoint.equals(endpoint(level, controller.getBlockPos()))) return false;
        return cachedKey != null && scanFacing(controller) == cachedKey.scanFacing()
                && plan.geometryRevision() == geometryRevision;
    }

    public Optional<RadarVisualProfile> visualProfile(RadarControllerBlockEntity owner) {
        RadarObservationPlan plan = lastPreparedPlan;
        if (plan == null || !isReadyForObservation(plan)) return Optional.empty();
        return Optional.of(plan.profile());
    }

    private RadarAreaGeometry.GeometrySnapshot geometry(RadarControllerBlockEntity owner, ServerLevel level,
                                                        RadarMode mode, RadarApertureKind kind, int apertureCount,
                                                        RadarServerConfig.Snapshot parameters) {
        int activeLimit = kind == RadarApertureKind.OVERVIEW
                ? parameters.overview().maxModules() : parameters.maxPanels();
        int boundedCount = Math.min(Math.max(0, apertureCount), activeLimit);
        RadarServerConfig.ModeParameters modeParameters = mode.parameters(parameters);
        GeometryKey key = new GeometryKey(parameters.range(), parameters.overview(), modeParameters,
                kind, boundedCount, mode, scanFacing(owner), owner.getBlockPos().immutable(),
                sectorOrderVariant);
        if (cachedGeometry != null && key.equals(cachedKey)) return cachedGeometry;
        cachedGeometry = RadarAreaGeometry.create(level.dimension().location(), key.position(), key.scanFacing(),
                mode, kind, apertureCount, parameters, sectorOrderVariant);
        cachedKey = key;
        geometryRevision = Math.incrementExact(geometryRevision);
        return cachedGeometry;
    }

    /** OVERVIEW закреплён относительно севера независимо от декоративного поворота корпуса. */
    private static Direction scanFacing(RadarControllerBlockEntity controller) {
        return controller.assembly().apertureKind() == RadarApertureKind.OVERVIEW
                ? Direction.NORTH : controller.getBlockState().getValue(RadarControllerBlock.FACING);
    }

    public void invalidateProfile() {
        cachedGeometry = null;
        cachedKey = null;
        lastPreparedPlan = null;
    }

    /** Смена числа/смысла индексов сбрасывает привязанные к ним подтверждения вне обхода Pre. */
    public void apertureKindChanged(RadarControllerBlockEntity owner) {
        if (!(owner.getLevel() instanceof ServerLevel level)) {
            invalidateProfile();
            return;
        }
        unregister("aperture_kind_changed", owner);
        this.owner = owner;
        ensureNetworkId(owner, level);
        ensureSectorOrder(owner, level);
        register(level, owner.getBlockPos());
        RadarDiagnostics.log("radar_aperture_kind_changed", networkId, endpoint, null, tick(level),
                "action=registration_reset preserveNetworkId=true preserveOrderVariant="
                        + sectorOrderVariant);
    }

    public void unregister(String reason, RadarControllerBlockEntity owner) {
        if (token == null || endpoint == null || networkId == null
                || !(owner.getLevel() instanceof ServerLevel level)) {
            token = null;
            endpoint = null;
            invalidateProfile();
            return;
        }
        try {
            RadarNetworkManager manager = RadarNetworkManager.forServer(level.getServer());
            boolean removed = manager.unregister(endpoint, networkId, token);
            RadarDiagnostics.log(removed ? "radar_unregistered" : "radar_unregister_rejected",
                    networkId, endpoint, null, manager.currentServerTick(),
                    "reason=" + reason + " token=" + token.value());
        } catch (RuntimeException exception) {
            RadarDiagnostics.logException("radar_unregister_failed", networkId, endpoint, null,
                    tick(level), "reason=" + reason, exception);
        } finally {
            token = null;
            endpoint = null;
            invalidateProfile();
        }
    }

    private void ensureNetworkId(RadarControllerBlockEntity owner, ServerLevel level) {
        endpoint = endpoint(level, owner.getBlockPos());
        if (networkId == null) {
            networkId = NetworkId.create();
            owner.setChanged();
            RadarDiagnostics.log("network_id_created", networkId, endpoint, null, tick(level),
                    "reason=missing_saved_id");
        } else if (loadedNetworkId) {
            RadarDiagnostics.log("network_id_loaded", networkId, endpoint, null, tick(level),
                    "source=block_entity_nbt");
            loadedNetworkId = false;
        }
    }

    private void ensureSectorOrder(RadarControllerBlockEntity owner, ServerLevel level) {
        if (RadarSectorOrder.validVariant(sectorOrderVariant)) return;
        sectorOrderVariant = 1 + level.random.nextInt(RadarSectorOrder.VARIANT_COUNT);
        owner.setChanged();
        invalidateProfile();
        RadarDiagnostics.log("radar_sector_order_assigned", networkId, endpoint(level, owner.getBlockPos()),
                null, tick(level), "orderVariant=" + sectorOrderVariant + " persist=controller_nbt");
    }

    private void register(ServerLevel level, BlockPos pos) {
        if (token != null || networkId == null) return;
        endpoint = endpoint(level, pos);
        try {
            token = RadarNetworkManager.forServer(level.getServer()).registerRadar(endpoint, networkId, POLICY, this);
            registrationFailureLogged = false;
            RadarDiagnostics.log("radar_registration_ready", networkId, endpoint, null, tick(level),
                    "token=" + token.value() + " forgetAfterMisses=" + POLICY.forgetAfterMisses());
        } catch (RuntimeException exception) {
            if (!registrationFailureLogged) {
                registrationFailureLogged = true;
                RadarDiagnostics.logException("radar_registration_failed", networkId, endpoint, null,
                        tick(level), "operation=registerRadar", exception);
            }
        }
    }

    private static EndpointKey.Static endpoint(ServerLevel level, BlockPos pos) {
        return new EndpointKey.Static(level.dimension(), pos);
    }

    private long tick(ServerLevel level) {
        try { return RadarNetworkManager.forServer(level.getServer()).currentServerTick(); }
        catch (RuntimeException ignored) { return -1L; }
    }

    public EndpointKey.Static endpoint() { return endpoint; }
    public long currentTick(ServerLevel level) { return tick(level); }

    private record GeometryKey(RadarServerConfig.RangeParameters rangeParameters,
                               RadarServerConfig.OverviewParameters overviewParameters,
                               RadarServerConfig.ModeParameters modeParameters,
                               RadarApertureKind apertureKind, int countedAperture,
                               RadarMode mode, Direction scanFacing, BlockPos position,
                               int sectorOrderVariant) { }
}
