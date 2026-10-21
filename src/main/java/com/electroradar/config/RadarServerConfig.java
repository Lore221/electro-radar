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
    /**
     * Один volatile-снимок — единственная точка чтения конфигурации для runtime.
     * До первого события загрузки доступен полный набор стандартных значений.
     */
    private static volatile Snapshot publishedSnapshot;
    private static final ModConfigSpec.IntValue MAX_PANELS;
    private static final ModConfigSpec.IntValue BASE_RANGE;
    private static final ModConfigSpec.IntValue RANGE_PER_PANEL;
    private static final ModConfigSpec.IntValue MAX_RANGE;
    private static final ModConfigSpec.DoubleValue GLOBAL_MULTIPLIER;
    private static final ModConfigSpec.DoubleValue GROUND_MULTIPLIER;
    private static final ModConfigSpec.DoubleValue AIR_MULTIPLIER;
    private static final ModConfigSpec.DoubleValue SURFACE_MULTIPLIER;
    private static final ModConfigSpec.IntValue GROUND_MIN_Y;
    private static final ModConfigSpec.IntValue GROUND_MAX_Y;
    private static final ModConfigSpec.DoubleValue GROUND_ANGLE;
    private static final ModConfigSpec.IntValue AIR_MIN_Y;
    private static final ModConfigSpec.IntValue AIR_MAX_Y;
    private static final ModConfigSpec.DoubleValue AIR_ANGLE;
    private static final ModConfigSpec.IntValue SURFACE_MIN_Y;
    private static final ModConfigSpec.IntValue SURFACE_MAX_Y;
    private static final ModConfigSpec.DoubleValue SURFACE_ANGLE;
    private static final ModConfigSpec.IntValue VISUAL_PARTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Maximum panel count and panel contribution.").push("panels");
        MAX_PANELS = builder.comment("Maximum number of panels accepted by a new assembly. Existing arrays are retained.")
                .translation("electroradar.config.server.max_panels")
                .defineInRange("max_panels", 20, 1, 20);
        builder.pop();

        builder.comment("Common range formula: floor((base + panelCount * perPanel) * global * mode).")
                .push("range");
        BASE_RANGE = builder.comment("Base range before panel bonuses, in blocks.")
                .translation("electroradar.config.server.base_range").defineInRange("base_range", 80, 0, 1_024);
        RANGE_PER_PANEL = builder.comment("Range bonus from each captured panel, in blocks.")
                .translation("electroradar.config.server.range_per_panel")
                .defineInRange("range_per_panel", 20, 0, 1_024);
        MAX_RANGE = builder.comment("Hard maximum range in blocks.")
                .translation("electroradar.config.server.max_range").defineInRange("max_range", 1_024, 0, 1_024);
        GLOBAL_MULTIPLIER = builder.comment("Global range multiplier.")
                .translation("electroradar.config.server.global_multiplier")
                .defineInRange("global_multiplier", 1.0D, 0.000001D, 1_000.0D);
        builder.pop();

        builder.comment("Ordinary ground radar scan geometry.").push("ground");
        GROUND_MIN_Y = builder.comment("Lower Y offset from the controller center, in blocks.")
                .translation("electroradar.config.server.ground.min_y")
                .defineInRange("min_y", -20, -4_096, 4_096);
        GROUND_MAX_Y = builder.comment("Upper Y offset from the controller center, in blocks.")
                .translation("electroradar.config.server.ground.max_y")
                .defineInRange("max_y", 128, -4_096, 4_096);
        GROUND_ANGLE = builder.comment("Horizontal field of view in degrees.")
                .translation("electroradar.config.server.ground.horizontal_angle_degrees")
                .defineInRange("horizontal_angle_degrees", 90.0D, 0.001D, 360.0D);
        GROUND_MULTIPLIER = builder.comment("Ground radar range multiplier.")
                .translation("electroradar.config.server.ground.range_multiplier")
                .defineInRange("range_multiplier", 1.0D, 0.000001D, 1_000.0D);
        builder.pop();

        builder.comment("Air radar scan geometry.").push("air");
        AIR_MIN_Y = builder.comment("Lower Y offset from the controller center, in blocks.")
                .translation("electroradar.config.server.air.min_y").defineInRange("min_y", 40, -4_096, 4_096);
        AIR_MAX_Y = builder.comment("Upper Y offset from the controller center, in blocks.")
                .translation("electroradar.config.server.air.max_y")
                .defineInRange("max_y", 1_500, -4_096, 4_096);
        AIR_ANGLE = builder.comment("Horizontal field of view in degrees.")
                .translation("electroradar.config.server.air.horizontal_angle_degrees")
                .defineInRange("horizontal_angle_degrees", 60.0D, 0.001D, 360.0D);
        AIR_MULTIPLIER = builder.comment("Air radar range multiplier.")
                .translation("electroradar.config.server.air.range_multiplier")
                .defineInRange("range_multiplier", 1.5D, 0.000001D, 1_000.0D);
        builder.pop();

        builder.comment("Surface radar scan geometry.").push("surface");
        SURFACE_MIN_Y = builder.comment("Lower Y offset from the controller center, in blocks.")
                .translation("electroradar.config.server.surface.min_y")
                .defineInRange("min_y", -1_500, -4_096, 4_096);
        SURFACE_MAX_Y = builder.comment("Upper Y offset from the controller center, in blocks.")
                .translation("electroradar.config.server.surface.max_y")
                .defineInRange("max_y", -20, -4_096, 4_096);
        SURFACE_ANGLE = builder.comment("Horizontal field of view in degrees.")
                .translation("electroradar.config.server.surface.horizontal_angle_degrees")
                .defineInRange("horizontal_angle_degrees", 120.0D, 0.001D, 360.0D);
        SURFACE_MULTIPLIER = builder.comment("Surface radar range multiplier.")
                .translation("electroradar.config.server.surface.range_multiplier")
                .defineInRange("range_multiplier", 1.0D, 0.000001D, 1_000.0D);
        builder.pop();

        builder.comment("Display-only number of sweep parts; scan cadence remains fixed at 40 ticks.")
                .push("visual");
        VISUAL_PARTS = builder.comment("Number of animated sector parts, not scan slices.")
                .translation("electroradar.config.server.visual.parts").defineInRange("parts", 8, 1, 64);
        builder.pop();
        SPEC = builder.build();
        publishedSnapshot = defaults();
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
        int panels = MAX_PANELS.get();
        int base = BASE_RANGE.get();
        int perPanel = RANGE_PER_PANEL.get();
        int max = MAX_RANGE.get();
        double global = positive(GLOBAL_MULTIPLIER.get(), 1.0D);
        double groundMultiplier = positive(GROUND_MULTIPLIER.get(), 1.0D);
        double airMultiplier = positive(AIR_MULTIPLIER.get(), 1.5D);
        double surfaceMultiplier = positive(SURFACE_MULTIPLIER.get(), 1.0D);
        return new Snapshot(revision, panels, base, perPanel, max, global,
                range(GROUND_MIN_Y.get(), GROUND_MAX_Y.get(), -20, 128, "ground", revision),
                boundedAngle(GROUND_ANGLE.get(), 90.0D), groundMultiplier,
                range(AIR_MIN_Y.get(), AIR_MAX_Y.get(), 40, 1_500, "air", revision),
                boundedAngle(AIR_ANGLE.get(), 60.0D), airMultiplier,
                range(SURFACE_MIN_Y.get(), SURFACE_MAX_Y.get(), -1_500, -20, "surface", revision),
                boundedAngle(SURFACE_ANGLE.get(), 120.0D), surfaceMultiplier,
                VISUAL_PARTS.get());
    }

    private static Snapshot defaults() {
        return new Snapshot(0L, 20, 80, 20, 1_024, 1.0D,
                new VerticalRange(-20, 128), 90.0D, 1.0D,
                new VerticalRange(40, 1_500), 60.0D, 1.5D,
                new VerticalRange(-1_500, -20), 120.0D, 1.0D, 8);
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

    public record Snapshot(
            long revision,
            int maxPanels,
            int baseRange,
            int rangePerPanel,
            int maxRange,
            double globalMultiplier,
            VerticalRange groundY,
            double groundAngle,
            double groundMultiplier,
            VerticalRange airY,
            double airAngle,
            double airMultiplier,
            VerticalRange surfaceY,
            double surfaceAngle,
            double surfaceMultiplier,
            int visualParts
    ) {
        public Snapshot {
            if (maxPanels < 1 || maxPanels > 20 || baseRange < 0 || rangePerPanel < 0
                    || maxRange < 0 || maxRange > 1_024 || visualParts < 1 || visualParts > 64) {
                throw new IllegalArgumentException("Invalid server radar configuration snapshot");
            }
        }
    }
}
