package com.electroradar.config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;

import com.electroradar.radar.RadarDiagnostics;

/**
 * Серверная игровая конфигурация. Значения снимаются в неизменяемый снимок на границе окна,
 * поэтому один и тот же снимок определяет и область поиска, и профиль для монитора.
 */
public final class RadarServerConfig {
    public static final ModConfigSpec SPEC;

    private static final Set<String> INVALID_RANGES_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Snapshot DEFAULT = new Snapshot(0L, 20,
            new RangeParameters(80, 20, 1_024, 1.0D),
            new ModeParameters(new VerticalRange(-20, 128), 90.0D, 1.0D),
            new ModeParameters(new VerticalRange(40, 1_500), 60.0D, 1.5D),
            new ModeParameters(new VerticalRange(-1_500, -20), 120.0D, 1.0D), 10,
            new OverviewParameters(5, 50));
    /**
     * Один volatile-снимок — единственная точка чтения конфигурации для runtime.
     * До первого события загрузки доступен полный набор стандартных значений.
     */
    private static volatile Snapshot publishedSnapshot = DEFAULT;
    private static final ModConfigSpec.IntValue MAX_PANELS;
    private static final ModConfigSpec.IntValue BASE_RANGE;
    private static final ModConfigSpec.IntValue RANGE_PER_PANEL;
    private static final ModConfigSpec.IntValue MAX_RANGE;
    private static final ModConfigSpec.DoubleValue GLOBAL_MULTIPLIER;
    private static final ModeValues GROUND;
    private static final ModeValues AIR;
    private static final ModeValues SURFACE;
    private static final ModConfigSpec.IntValue VISUAL_PARTS;
    private static final ModConfigSpec.IntValue SECTOR_TICKS;
    private static final ModConfigSpec.IntValue MAX_OVERVIEW_MODULES;
    private static final ModConfigSpec.IntValue OVERVIEW_RANGE_PER_MODULE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Maximum panel count and panel contribution.").push("panels");
        MAX_PANELS = builder.comment("Maximum number of panels accepted by a new assembly. Existing arrays are retained.")
                .translation("electroradar.config.server.max_panels")
                .defineInRange("max_panels", DEFAULT.maxPanels(), 1, 20);
        builder.pop();

        builder.comment("Common range formula: floor((base + panelCount * perPanel) * global * mode).")
                .push("range");
        BASE_RANGE = builder.comment("Base range before panel bonuses, in blocks.")
                .translation("electroradar.config.server.base_range")
                .defineInRange("base_range", DEFAULT.range().baseRange(), 0, 1_024);
        RANGE_PER_PANEL = builder.comment("Range bonus from each captured panel, in blocks.")
                .translation("electroradar.config.server.range_per_panel")
                .defineInRange("range_per_panel", DEFAULT.range().rangePerPanel(), 0, 1_024);
        MAX_RANGE = builder.comment("Hard maximum range in blocks.")
                .translation("electroradar.config.server.max_range")
                .defineInRange("max_range", DEFAULT.range().maxRange(), 0, 1_024);
        GLOBAL_MULTIPLIER = builder.comment("Global range multiplier.")
                .translation("electroradar.config.server.global_multiplier")
                .defineInRange("global_multiplier", DEFAULT.range().globalMultiplier(), 0.000001D, 1_000.0D);
        builder.pop();

        GROUND = ModeValues.define(builder, "ground", "Ordinary ground radar scan geometry.", DEFAULT.ground());
        AIR = ModeValues.define(builder, "air", "Air radar scan geometry.", DEFAULT.air());
        SURFACE = ModeValues.define(builder, "surface", "Surface radar scan geometry.", DEFAULT.surface());

        builder.comment("Overview aperture limits and range contribution.").push("overview");
        MAX_OVERVIEW_MODULES = builder.comment("Maximum overview modules accepted by a new assembly.")
                .translation("electroradar.config.server.overview.max_modules")
                .defineInRange("max_modules", DEFAULT.overview().maxModules(), 1, 20);
        OVERVIEW_RANGE_PER_MODULE = builder.comment("Range bonus from each overview module, in blocks.")
                .translation("electroradar.config.server.overview.range_per_module")
                .defineInRange("range_per_module", DEFAULT.overview().rangePerModule(), 0, 1_024);
        builder.pop();

        builder.comment("Duration of a sector visit in server ticks.").push("scanning");
        SECTOR_TICKS = builder.comment("Ticks per sector; applied at the next scan boundary.")
                .translation("electroradar.config.server.scanning.sector_ticks")
                .defineInRange("sector_ticks", DEFAULT.sectorTicks(), 1, 200);
        builder.pop();

        builder.comment("Deprecated settings retained for existing configurations.").push("visual");
        VISUAL_PARTS = builder.comment("Deprecated and ignored: current radars always use seven sectors.")
                .translation("electroradar.config.server.visual.parts")
                .defineInRange("parts", 7, 1, 64);
        builder.pop();
        SPEC = builder.build();
    }

    private RadarServerConfig() {
    }

    /**
     * NeoForge вызывает Loading/Reloading после установки нового целого Config и сброса кешей
     * ConfigValue; обработчик синхронизирован самим ModConfig на время этого вызова. Здесь
     * читаются значения, собирается готовый immutable record и только затем одной volatile
     * записью публикуется новый набор. Сканирующий поток никогда не читает ConfigValue по одному.
     */
    public static void onConfigChanged(ModConfigEvent event) {
        if (!(event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)
                || event.getConfig().getSpec() != SPEC) {
            return;
        }
        Snapshot next;
        synchronized (RadarServerConfig.class) {
            long revision = publishedSnapshot.revision() + 1L;
            next = readSnapshot(revision);
            publishedSnapshot = next;
        }
        RadarDiagnostics.log("server_config_changed", null, null, null, null,
                "configRevision=" + next.revision() + " apply=next_scan_window");
    }

    /**
     * Возвращает только опубликованную ссылку. RadarServerRuntime закрепляет этот record при
     * открытии окна, поэтому уже начавшийся проход не смешивает параметры до и после reload.
     */
    public static Snapshot snapshot() {
        return publishedSnapshot;
    }

    private static Snapshot readSnapshot(long revision) {
        RangeParameters range = new RangeParameters(BASE_RANGE.get(), RANGE_PER_PANEL.get(), MAX_RANGE.get(),
                positive(GLOBAL_MULTIPLIER.get(), DEFAULT.range().globalMultiplier()));
        int legacyParts = VISUAL_PARTS.get();
        if (legacyParts != 7) {
            RadarDiagnostics.log("config_legacy_visual_parts_ignored", null, null, null, null,
                    "configRevision=" + revision + " configured=" + legacyParts + " used=7");
        }
        return new Snapshot(revision, MAX_PANELS.get(), range,
                GROUND.read(DEFAULT.ground(), "ground", revision),
                AIR.read(DEFAULT.air(), "air", revision),
                SURFACE.read(DEFAULT.surface(), "surface", revision), SECTOR_TICKS.get(),
                new OverviewParameters(MAX_OVERVIEW_MODULES.get(), OVERVIEW_RANGE_PER_MODULE.get()));
    }

    /** Одна схема TOML для трёх режимов; ключи и ограничения остаются одинаковыми. */
    private record ModeValues(ModConfigSpec.IntValue minY, ModConfigSpec.IntValue maxY,
                              ModConfigSpec.DoubleValue angle, ModConfigSpec.DoubleValue multiplier) {
        static ModeValues define(ModConfigSpec.Builder builder, String mode, String comment,
                                 ModeParameters defaults) {
            builder.comment(comment).push(mode);
            String translation = "electroradar.config.server." + mode + ".";
            ModConfigSpec.IntValue min = builder.comment("Lower Y offset from the controller center, in blocks.")
                    .translation(translation + "min_y")
                    .defineInRange("min_y", defaults.verticalRange().minY(), -4_096, 4_096);
            ModConfigSpec.IntValue max = builder.comment("Upper Y offset from the controller center, in blocks.")
                    .translation(translation + "max_y")
                    .defineInRange("max_y", defaults.verticalRange().maxY(), -4_096, 4_096);
            ModConfigSpec.DoubleValue angle = builder.comment("Horizontal field of view in degrees.")
                    .translation(translation + "horizontal_angle_degrees")
                    .defineInRange("horizontal_angle_degrees", defaults.horizontalAngle(), 0.001D, 360.0D);
            ModConfigSpec.DoubleValue multiplier = builder.comment("Radar range multiplier for this mode.")
                    .translation(translation + "range_multiplier")
                    .defineInRange("range_multiplier", defaults.rangeMultiplier(), 0.000001D, 1_000.0D);
            builder.pop();
            return new ModeValues(min, max, angle, multiplier);
        }

        ModeParameters read(ModeParameters defaults, String mode, long revision) {
            VerticalRange vertical = range(minY.get(), maxY.get(), defaults.verticalRange().minY(),
                    defaults.verticalRange().maxY(), mode, revision);
            return new ModeParameters(vertical, boundedAngle(angle.get(), defaults.horizontalAngle()),
                    positive(multiplier.get(), defaults.rangeMultiplier()));
        }
    }

    private static VerticalRange range(int min, int max, int defaultMin, int defaultMax,
                                       String mode, long revision) {
        if (min <= max) return new VerticalRange(min, max);
        if (INVALID_RANGES_LOGGED.add(revision + ":" + mode)) {
            RadarDiagnostics.log("config_invalid_y_range", null, null, null, null,
                    "mode=" + mode + " min=" + min + " max=" + max
                            + " action=use_defaults min=" + defaultMin + " max=" + defaultMax);
        }
        return new VerticalRange(defaultMin, defaultMax);
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static double boundedAngle(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D && value <= 360.0D ? value : fallback;
    }

    public record VerticalRange(int minY, int maxY) {
    }

    public record RangeParameters(int baseRange, int rangePerPanel, int maxRange, double globalMultiplier) {
        public RangeParameters {
            if (baseRange < 0 || rangePerPanel < 0 || maxRange < 0 || maxRange > 1_024) {
                throw new IllegalArgumentException("Invalid radar range parameters");
            }
        }
    }

    public record ModeParameters(VerticalRange verticalRange, double horizontalAngle, double rangeMultiplier) {
    }

    public record OverviewParameters(int maxModules, int rangePerModule) {
        public OverviewParameters {
            if (maxModules < 1 || maxModules > 20 || rangePerModule < 0 || rangePerModule > 1_024) {
                throw new IllegalArgumentException("Invalid overview radar configuration");
            }
        }
    }

    public record Snapshot(long revision, int maxPanels, RangeParameters range,
                           ModeParameters ground, ModeParameters air, ModeParameters surface, int sectorTicks,
                           OverviewParameters overview) {
        public Snapshot {
            if (maxPanels < 1 || maxPanels > 20 || sectorTicks < 1 || sectorTicks > 200
                    || overview == null) {
                throw new IllegalArgumentException("Invalid server radar configuration snapshot");
            }
        }
    }
}
