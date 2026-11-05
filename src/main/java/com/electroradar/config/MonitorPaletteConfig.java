package com.electroradar.config;

import java.util.HashMap;
import java.util.Map;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Палитра мониторов принадлежит серверу и приходит всем клиентам вместе с SERVER-конфигом. */
final class MonitorPaletteConfig {
    private static final Map<String, ModConfigSpec.ConfigValue<String>> VALUES = new HashMap<>();
    private static final Map<String, String> DEFAULTS = new HashMap<>();
    private static volatile RadarClientConfig.Palette palette;

    private MonitorPaletteConfig() { }

    static void define(ModConfigSpec.Builder builder) {
        builder.comment("Shared monitor colors; RGB or ARGB hex. Replaces the legacy client palette.").push("monitor_palette");
        builder.push("targets");
        color(builder, "targets.player", "player", "#0000FF");
        color(builder, "targets.hostile", "hostile", "#FF0000");
        color(builder, "targets.passive", "passive", "#55FF55");
        color(builder, "targets.other", "other", "#FFFF00");
        builder.pop().push("screen");
        color(builder, "screen.selected_frame", "selected_frame", "#FF0000");
        color(builder, "screen.hovered_frame", "hovered_frame", "#FFFFFF");
        color(builder, "screen.grid", "grid", "#2ED8FFE8");
        color(builder, "screen.world_grid", "world_grid", "#FFD8FFE8");
        color(builder, "screen.status_warning", "status_warning", "#FFFFC66D");
        color(builder, "screen.status_neutral", "status_neutral", "#FFB7D1D5");
        builder.pop().push("sector");
        color(builder, "sector.gui_base_outline", "gui_base_outline", "#28D8FFE8");
        color(builder, "sector.gui_active_fill", "gui_active_fill", "#20D8FFE8");
        color(builder, "sector.gui_active_edge", "gui_active_edge", "#80D8FFE8");
        color(builder, "sector.gui_flash_fill", "gui_flash_fill", "#3CD8FFE8");
        color(builder, "sector.gui_flash_edge", "gui_flash_edge", "#88D8FFE8");
        color(builder, "sector.world_base_outline", "world_base_outline", "#80D8FFE8");
        color(builder, "sector.world_active_edge", "world_active_edge", "#D0D8FFE8");
        color(builder, "sector.world_flash_fill", "world_flash_fill", "#34D8FFE8");
        color(builder, "sector.world_flash_edge", "world_flash_edge", "#A0D8FFE8");
        builder.pop().pop();
        refresh(false);
    }

    private static void color(ModConfigSpec.Builder builder, String name, String key, String value) {
        DEFAULTS.put(name, value);
        int digits = value.length() - 1;
        VALUES.put(name, builder.comment(digits == 6 ? "Format: #RRGGBB." : "Format: #AARRGGBB.")
                .translation("electroradar.config.client." + name).define(key, value,
                        candidate -> candidate instanceof String text && text.matches("#[0-9a-fA-F]{" + digits + "}")));
    }

    static RadarClientConfig.Palette palette() {
        // Штатный путь всегда определяет настройки при создании RadarServerConfig.SPEC.
        // Это также обеспечивает defaults, если палитру запросили раньше первого мира.
        if (palette == null) {
            @SuppressWarnings("unused") ModConfigSpec spec = RadarServerConfig.SPEC;
        }
        return palette;
    }

    static void refresh(boolean loaded) {
        Map<String, Integer> colors = new HashMap<>();
        DEFAULTS.forEach((name, value) -> colors.put(name,
                (int) Long.parseLong((loaded ? VALUES.get(name).get() : value).substring(1), 16)));
        palette = new RadarClientConfig.Palette(new RadarClientConfig.TargetColors(
                colors.get("targets.player"), colors.get("targets.hostile"), colors.get("targets.passive"), colors.get("targets.other")),
                new RadarClientConfig.ScreenColors(colors.get("screen.selected_frame"), colors.get("screen.hovered_frame"),
                        colors.get("screen.grid"), colors.get("screen.world_grid"), colors.get("screen.status_warning"), colors.get("screen.status_neutral")),
                new RadarClientConfig.SectorColors(new RadarClientConfig.GuiSectorColors(
                        colors.get("sector.gui_base_outline"), colors.get("sector.gui_active_fill"), colors.get("sector.gui_active_edge"),
                        colors.get("sector.gui_flash_fill"), colors.get("sector.gui_flash_edge")),
                        new RadarClientConfig.WorldSectorColors(colors.get("sector.world_base_outline"), colors.get("sector.world_active_edge"),
                                colors.get("sector.world_flash_fill"), colors.get("sector.world_flash_edge"))));
    }
}
