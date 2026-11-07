package com.electroradar.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import com.electroradar.radar.visual.RadarVisualFadeStyle;

/** Общие экспериментальные настройки изображения в синхронизируемом SERVER-конфиге. */
public final class MonitorSweepConfig {
    private static final SweepParameters PANEL_DEFAULT = new SweepParameters(7, 1, 10, 70, 10, SweepOrder.SHUFFLED);
    private static final SweepParameters OVERVIEW_DEFAULT = new SweepParameters(18, 3, 20, 120, 40, SweepOrder.SHUFFLED);
    private static volatile Settings settings = new Settings(PANEL_DEFAULT, OVERVIEW_DEFAULT);
    private static Options panel;
    private static Options overview;

    private MonitorSweepConfig() { }

    static void define(ModConfigSpec.Builder builder) {
        builder.comment("Temporary monitor development controls, synchronized to all clients. Times are world ticks and independent of scanning.")
                .push("monitor_sweep");
        panel = new Options(builder, "panel", PANEL_DEFAULT);
        overview = new Options(builder, "overview", OVERVIEW_DEFAULT);
        builder.pop();
    }

    static void refresh() { settings = new Settings(panel.read(), overview.read()); }
    static void reset() { settings = new Settings(PANEL_DEFAULT, OVERVIEW_DEFAULT); }

    public static SweepParameters sweep(RadarVisualFadeStyle style) {
        Settings current = settings;
        return style == RadarVisualFadeStyle.OVERVIEW ? current.overview() : current.panel();
    }

    public enum SweepOrder { SHUFFLED, SEQUENTIAL }

    /** Время активности допускает доли тика; продолжительность начатого следа хранится у отметки. */
    public record SweepParameters(int sectors, int beams, double activeTicks, double targetFadeTicks,
                                  double sectorFadeTicks, SweepOrder order) { }

    private record Settings(SweepParameters panel, SweepParameters overview) { }

    private static final class Options {
        private final ModConfigSpec.IntValue sectors;
        private final ModConfigSpec.IntValue beams;
        private final ModConfigSpec.DoubleValue active;
        private final ModConfigSpec.DoubleValue targetFade;
        private final ModConfigSpec.DoubleValue sectorFade;
        private final ModConfigSpec.EnumValue<SweepOrder> order;

        Options(ModConfigSpec.Builder builder, String name, SweepParameters defaults) {
            builder.push(name);
            String prefix = "electroradar.config.server.monitor_sweep." + name + ".";
            sectors = builder.comment("Client subsector count; preserves the server's outer polygon.")
                    .translation(prefix + "subsectors").defineInRange("subsectors", defaults.sectors(), 1, 64);
            beams = builder.comment("Simultaneous beams; clamped to subsector count.")
                    .translation(prefix + "beams").defineInRange("beams", defaults.beams(), 1, 64);
            active = builder.comment("World ticks per active subsector. Fractional times are allowed.")
                    .translation(prefix + "active_ticks").defineInRange("active_ticks", defaults.activeTicks(), 0.25D, 1200.0D);
            targetFade = builder.comment("Frozen marker fade. 0 hides immediately. Server access and memory still bound its lifetime.")
                    .translation(prefix + "target_fade_ticks").defineInRange("target_fade_ticks", defaults.targetFadeTicks(), 0.0D, 1200.0D);
            sectorFade = builder.comment("Inactive sector afterglow; 0 disables it.")
                    .translation(prefix + "sector_fade_ticks").defineInRange("sector_fade_ticks", defaults.sectorFadeTicks(), 0.0D, 1200.0D);
            order = builder.comment("SHUFFLED: saved reproducible radar variant. SEQUENTIAL: along the outline.")
                    .translation(prefix + "order").defineEnum("order", defaults.order());
            builder.pop();
        }

        SweepParameters read() {
            return new SweepParameters(sectors.get(), Math.min(sectors.get(), beams.get()), active.get(),
                    targetFade.get(), sectorFade.get(), order.get());
        }
    }
}
