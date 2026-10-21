package com.electroradar.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;

/** Клиентская палитра монитора. Серверные классы не обращаются к этим цветам. */
public final class RadarClientConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<String> PLAYER;
    private static final ModConfigSpec.ConfigValue<String> HOSTILE;
    private static final ModConfigSpec.ConfigValue<String> PASSIVE;
    private static final ModConfigSpec.ConfigValue<String> OTHER;
    private static final ModConfigSpec.ConfigValue<String> SELECTED_FRAME;
    private static final ModConfigSpec.ConfigValue<String> HOVERED_FRAME;
    private static final ModConfigSpec.ConfigValue<String> GUI_GRID;
    private static final ModConfigSpec.ConfigValue<String> WORLD_GRID;
    private static final ModConfigSpec.ConfigValue<String> GUI_BASE;
    private static final ModConfigSpec.ConfigValue<String> GUI_ACTIVE_FILL;
    private static final ModConfigSpec.ConfigValue<String> GUI_ACTIVE_EDGE;
    private static final ModConfigSpec.ConfigValue<String> GUI_FLASH_FILL;
    private static final ModConfigSpec.ConfigValue<String> GUI_FLASH_EDGE;
    private static final ModConfigSpec.ConfigValue<String> WORLD_BASE;
    private static final ModConfigSpec.ConfigValue<String> WORLD_ACTIVE_EDGE;
    private static final ModConfigSpec.ConfigValue<String> WORLD_FLASH_FILL;
    private static final ModConfigSpec.ConfigValue<String> WORLD_FLASH_EDGE;
    private static final ModConfigSpec.ConfigValue<String> STATUS_WARNING;
    private static final ModConfigSpec.ConfigValue<String> STATUS_NEUTRAL;
    private static volatile Palette palette = Palette.DEFAULT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Monitor target marker colors in RGB format.").push("targets");
        PLAYER = rgb(builder, "targets.player", "player", "#0000FF");
        HOSTILE = rgb(builder, "targets.hostile", "hostile", "#FF0000");
        PASSIVE = rgb(builder, "targets.passive", "passive", "#55FF55");
        OTHER = rgb(builder, "targets.other", "other", "#FFFF00");
        builder.pop();
        builder.comment("Monitor selection frames and status labels.").push("screen");
        SELECTED_FRAME = rgb(builder, "screen.selected_frame", "selected_frame", "#FF0000");
        HOVERED_FRAME = rgb(builder, "screen.hovered_frame", "hovered_frame", "#FFFFFF");
        GUI_GRID = argb(builder, "screen.grid", "grid", "#2ED8FFE8");
        // Раньше мировая сетка брала RGB GUI-сетки и принудительно рисовалась непрозрачно.
        // Это значение сохраняет прежний мировой оттенок/альфу, но теперь альфу можно менять.
        WORLD_GRID = argb(builder, "screen.world_grid", "world_grid", "#FFD8FFE8");
        STATUS_WARNING = argb(builder, "screen.status_warning", "status_warning", "#FFFFC66D");
        STATUS_NEUTRAL = argb(builder, "screen.status_neutral", "status_neutral", "#FFB7D1D5");
        builder.pop();
        builder.comment("Monitor sector colors on the GUI and the physical display block.").push("sector");
        GUI_BASE = argb(builder, "sector.gui_base_outline", "gui_base_outline", "#28D8FFE8");
        GUI_ACTIVE_FILL = argb(builder, "sector.gui_active_fill", "gui_active_fill", "#20D8FFE8");
        GUI_ACTIVE_EDGE = argb(builder, "sector.gui_active_edge", "gui_active_edge", "#80D8FFE8");
        GUI_FLASH_FILL = argb(builder, "sector.gui_flash_fill", "gui_flash_fill", "#3CD8FFE8");
        GUI_FLASH_EDGE = argb(builder, "sector.gui_flash_edge", "gui_flash_edge", "#88D8FFE8");
        WORLD_BASE = argb(builder, "sector.world_base_outline", "world_base_outline", "#80D8FFE8");
        WORLD_ACTIVE_EDGE = argb(builder, "sector.world_active_edge", "world_active_edge", "#D0D8FFE8");
        WORLD_FLASH_FILL = argb(builder, "sector.world_flash_fill", "world_flash_fill", "#34D8FFE8");
        WORLD_FLASH_EDGE = argb(builder, "sector.world_flash_edge", "world_flash_edge", "#A0D8FFE8");
        builder.pop();
        SPEC = builder.build();
        refreshPalette();
    }

    private RadarClientConfig() {
    }

    private static ModConfigSpec.ConfigValue<String> rgb(ModConfigSpec.Builder builder, String translation,
                                                         String key, String value) {
        return color(builder, translation, key, value, 6);
    }

    private static ModConfigSpec.ConfigValue<String> argb(ModConfigSpec.Builder builder, String translation,
                                                          String key, String value) {
        return color(builder, translation, key, value, 8);
    }

    private static ModConfigSpec.ConfigValue<String> color(
            ModConfigSpec.Builder builder, String translation, String key, String value, int digits) {
        return builder.comment(digits == 6 ? "Format: #RRGGBB." : "Format: #AARRGGBB.")
                .translation("electroradar.config.client." + translation).define(key, value,
                candidate -> candidate instanceof String text
                        && text.matches("#[0-9a-fA-F]{" + digits + "}"));
    }

    public static void onConfigChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) refreshPalette();
    }

    /** Строки парсятся только при загрузке/перезагрузке; кадры получают готовый immutable record. */
    public static Palette palette() {
        return palette;
    }

    private static void refreshPalette() {
        palette = new Palette(parse(value(PLAYER)), parse(value(HOSTILE)), parse(value(PASSIVE)), parse(value(OTHER)),
                parse(value(SELECTED_FRAME)), parse(value(HOVERED_FRAME)), parse(value(GUI_GRID)),
                parse(value(WORLD_GRID)),
                parse(value(GUI_BASE)), parse(value(GUI_ACTIVE_FILL)), parse(value(GUI_ACTIVE_EDGE)),
                parse(value(GUI_FLASH_FILL)), parse(value(GUI_FLASH_EDGE)), parse(value(WORLD_BASE)),
                parse(value(WORLD_ACTIVE_EDGE)), parse(value(WORLD_FLASH_FILL)), parse(value(WORLD_FLASH_EDGE)),
                parse(value(STATUS_WARNING)), parse(value(STATUS_NEUTRAL)));
    }

    private static String value(ModConfigSpec.ConfigValue<String> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private static int parse(String value) {
        return (int) Long.parseLong(value.substring(1), 16);
    }

    public record Palette(
            int player, int hostile, int passive, int other,
            int selectedFrame, int hoveredFrame, int guiGrid, int worldGrid,
            int guiBase, int guiActiveFill, int guiActiveEdge,
            int guiFlashFill, int guiFlashEdge, int worldBase,
            int worldActiveEdge, int worldFlashFill, int worldFlashEdge,
            int statusWarning, int statusNeutral
    ) {
        private static final Palette DEFAULT = new Palette(
                0x0000FF, 0xFF0000, 0x55FF55, 0xFFFF00,
                0xFFFF0000, 0xFFFFFFFF, 0x2ED8FFE8, 0xFFD8FFE8,
                0x28D8FFE8, 0x20D8FFE8, 0x80D8FFE8,
                0x3CD8FFE8, 0x88D8FFE8, 0x80D8FFE8,
                0xD0D8FFE8, 0x34D8FFE8, 0xA0D8FFE8,
                0xFFFFC66D, 0xFFB7D1D5);
    }
}
