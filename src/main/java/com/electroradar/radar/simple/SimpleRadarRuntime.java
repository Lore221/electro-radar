package com.electroradar.radar.simple;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.electroradar.config.RadarServerConfig;
import com.electroradar.radar.bearing.RadarBearingBlockEntity;
import com.electroradar.radar.controller.RadarTargetFilter;
import com.electroradar.radar.network.*;
import com.electroradar.radar.scan.CylinderRadarScanArea;
import com.electroradar.radar.spatial.Placement;
import com.electroradar.radar.spatial.RadarSpatial;
import com.electroradar.radar.visual.RadarVisualProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Поставщик дешёвых неидентифицированных наблюдений; часы антенны независимы от поиска. */
public final class SimpleRadarRuntime implements RadarObservationSource {
    private final RadarBearingBlockEntity owner;
    private NetworkId id;
    private RegistrationToken token;
    private EndpointKey endpoint;
    private RadarObservationPlan prepared;
    private Key geometryKey;
    private long revision;
    private record Key(Placement placement,int count,RadarRotation rotation,long configRevision) { }

    public SimpleRadarRuntime(RadarBearingBlockEntity owner) { this.owner=owner; }
    public void read(CompoundTag tag) {
        id=tag.hasUUID("NetworkId") ? new NetworkId(tag.getUUID("NetworkId")) : null;
        token=null; endpoint=null; prepared=null; geometryKey=null;
    }
    public void write(CompoundTag tag) { if(id!=null) tag.putUUID("NetworkId",id.value()); }
    public void onLoad() { token=null; endpoint=null; prepared=null; geometryKey=null; }
    public NetworkId networkId() { return id; }
    public NetworkId getOrCreateNetworkId() {
        if (!(owner.getLevel() instanceof ServerLevel)) throw new IllegalStateException("Server-only radar binding");
        if(id==null) { id=NetworkId.create(); owner.setChanged(); }
        return id;
    }
    public void applyNetworkId(NetworkId next) {
        if(token!=null) throw new IllegalStateException("Already registered radar");
        if(!compatible(next)) throw new IllegalArgumentException("Incompatible radar network");
        id=next; owner.setChanged();
    }
    private boolean compatible(NetworkId next) {
        return next==null || owner.getLevel() instanceof ServerLevel level
                && RadarNetworkManager.forServer(level.getServer()).networkKind(next)
                    .map(kind -> kind==NetworkKind.SIMPLE_RADAR).orElse(true);
    }
    public boolean reconfigure(NetworkId next) {
        if(!compatible(next)) return false;
        NetworkId replacement=next==null ? NetworkId.create() : next;
        if(Objects.equals(id,replacement)) return true;
        unregister(); id=replacement; owner.setChanged();
        if(owner.getLevel() instanceof ServerLevel level) tick(level);
        return true;
    }
    public void tick(ServerLevel level) {
        var placement=RadarSpatial.placement(level,owner.getBlockPos());
        if(placement.isEmpty()) { unregister(); return; }
        if(token!=null && !placement.orElseThrow().endpoint().equals(endpoint)) unregister();
        if(token==null) {
            endpoint=placement.orElseThrow().endpoint();
            token=RadarNetworkManager.forServer(level.getServer()).registerRadar(endpoint,getOrCreateNetworkId(),
                    new NetworkPolicy(3),this);
        }
    }
    public void unregister() {
        if(token!=null && endpoint!=null && id!=null && owner.getLevel() instanceof ServerLevel level)
            RadarNetworkManager.forServer(level.getServer()).unregister(endpoint,id,token);
        token=null; endpoint=null; prepared=null; geometryKey=null;
    }
    @Override public NetworkKind networkKind() { return NetworkKind.SIMPLE_RADAR; }
    @Override public Optional<RadarObservationPlan> createObservationPlan(RadarServerConfig.Snapshot parameters) {
        return build(parameters.revision());
    }
    private Optional<RadarObservationPlan> build(long configRevision) {
        if(!ready() || !(owner.getLevel() instanceof ServerLevel level)) return Optional.empty();
        var placement=RadarSpatial.placement(level,owner.getBlockPos());
        if(placement.isEmpty() || !placement.orElseThrow().endpoint().equals(endpoint)) return Optional.empty();
        long managerTick=RadarNetworkManager.forServer(level.getServer()).currentServerTick();
        RadarRotation local=owner.rotation();
        RadarRotation world=new RadarRotation(managerTick-level.getGameTime()+local.epochTick(),local.angleAtEpoch())
                .inFrame(placement.orElseThrow().transform());
        Key next=new Key(placement.orElseThrow(),owner.antennaCount(),world,configRevision);
        if(next.equals(geometryKey) && prepared!=null) return Optional.of(prepared);
        if(geometryKey==null || next.count()!=geometryKey.count()) revision++;
        geometryKey=next;
        var center=next.placement().center();
        int radius=100*next.count();
        var area=new CylinderRadarScanArea(center,radius,-20,20);
        var profile=RadarVisualProfile.circularSector(level.dimension().location(),owner.getBlockPos(),
                center.x,center.y,center.z,radius,0,RadarVisualProfile.FULL_TURN,1)
                .withSource(endpoint).withRotation(world);
        prepared=new RadarObservationPlan(level.dimension().location(),profile,
                List.of(new RadarObservationPlan.Sector(0,area)),1,revision,configRevision,RadarTargetFilter.ELIGIBLE,
                (tick,window)->Optional.of(new ObservationWindowSchedule.ObservationWindow(
                        List.of(0),window.startTick(),window.endTick(),RadarRotation.PERIOD_TICKS)));
        return Optional.of(prepared);
    }
    private boolean ready() {
        return !owner.isRemoved() && owner.getLevel() instanceof ServerLevel level
                && level.hasChunkAt(owner.getBlockPos()) && owner.scanningReady();
    }
    @Override public Optional<RadarObservationPlan> refreshPlacement(RadarObservationPlan previous) {
        return build(previous.configRevision());
    }
    @Override public boolean isReadyForObservation(RadarObservationPlan plan) {
        return ready() && prepared!=null && plan.geometryRevision()==revision
                && owner.getLevel() instanceof ServerLevel level
                && RadarSpatial.placement(level,owner.getBlockPos()).map(Placement::endpoint).filter(endpoint::equals).isPresent();
    }
    public Optional<RadarVisualProfile> visualProfile() {
        return prepared!=null && isReadyForObservation(prepared) ? Optional.of(prepared.profile()) : Optional.empty();
    }
}
