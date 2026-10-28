package com.electroradar.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;

import com.electroradar.radar.visual.RadarVisualFadeStyle;

/** Клиентская палитра и длительность визуального следа; серверное наблюдение от них не зависит. */
public final class RadarClientConfig {
    public static final ModConfigSpec SPEC;
    private static final int MAX_FADE_TICKS = 1_200;

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
    private static final ModConfigSpec.IntValue SECTOR_FADE_TICKS;
    private static final ModConfigSpec.IntValue OVERVIEW_FADE_TICKS;
    private static volatile Snapshot snapshot;

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
        builder.comment("Monitor sector colors and afterglow on the GUI and the physical display block.").push("sector");
        GUI_BASE = argb(builder, "sector.gui_base_outline", "gui_base_outline", "#28D8FFE8");
        GUI_ACTIVE_FILL = argb(builder, "sector.gui_active_fill", "gui_active_fill", "#20D8FFE8");
        GUI_ACTIVE_EDGE = argb(builder, "sector.gui_active_edge", "gui_active_edge", "#80D8FFE8");
        GUI_FLASH_FILL = argb(builder, "sector.gui_flash_fill", "gui_flash_fill", "#3CD8FFE8");
        GUI_FLASH_EDGE = argb(builder, "sector.gui_flash_edge", "gui_flash_edge", "#88D8FFE8");
        WORLD_BASE = argb(builder, "sector.world_base_outline", "world_base_outline", "#80D8FFE8");
        WORLD_ACTIVE_EDGE = argb(builder, "sector.world_active_edge", "world_active_edge", "#D0D8FFE8");
        WORLD_FLASH_FILL = argb(builder, "sector.world_flash_fill", "world_flash_fill", "#34D8FFE8");
        WORLD_FLASH_EDGE = argb(builder, "sector.world_flash_edge", "world_flash_edge", "#A0D8FFE8");
        SECTOR_FADE_TICKS = builder.comment("Inactive sector afterglow duration in client ticks; 0 disables it.")
                .translation("electroradar.config.client.sector.fade_ticks")
                .defineInRange("fade_ticks", RadarVisualFadeStyle.SECTOR.defaultTicks(), 0, MAX_FADE_TICKS);
        OVERVIEW_FADE_TICKS = builder.comment("Inactive overview zone afterglow duration in client ticks; 0 disables it.")
                .translation("electroradar.config.client.sector.overview_fade_ticks")
                .defineInRange("overview_fade_ticks", RadarVisualFadeStyle.OVERVIEW.defaultTicks(), 0, MAX_FADE_TICKS);
        builder.pop();
        SPEC = builder.build();
        refreshSettings();
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
        if (event.getConfig().getSpec() == SPEC) refreshSettings();
    }

    /** Строки парсятся только при загрузке/перезагрузке; кадры получают готовый immutable record. */
    public static Palette palette() {
        return snapshot.palette();
    }

    /** Новое затухание закрепляет текущий срок; уже начатые следы сохраняют свои таймеры. */
    public static SectorFadeTimes sectorFadeTimes() {
        return snapshot.sectorFadeTimes();
    }

    private static void refreshSettings() {
        TargetColors targets = new TargetColors(read(PLAYER), read(HOSTILE), read(PASSIVE), read(OTHER));
        ScreenColors screen = new ScreenColors(read(SELECTED_FRAME), read(HOVERED_FRAME), read(GUI_GRID),
                read(WORLD_GRID), read(STATUS_WARNING), read(STATUS_NEUTRAL));
        GuiSectorColors gui = new GuiSectorColors(read(GUI_BASE), read(GUI_ACTIVE_FILL), read(GUI_ACTIVE_EDGE),
                read(GUI_FLASH_FILL), read(GUI_FLASH_EDGE));
        WorldSectorColors world = new WorldSectorColors(read(WORLD_BASE), read(WORLD_ACTIVE_EDGE),
                read(WORLD_FLASH_FILL), read(WORLD_FLASH_EDGE));
        Palette palette = new Palette(targets, screen, new SectorColors(gui, world));
        SectorFadeTimes fadeTimes = new SectorFadeTimes(readTicks(SECTOR_FADE_TICKS), readTicks(OVERVIEW_FADE_TICKS));
        snapshot = new Snapshot(palette, fadeTimes);
    }

    private static int readTicks(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private static int read(ModConfigSpec.ConfigValue<String> color) {
        String value = SPEC.isLoaded() ? color.get() : color.getDefault();
        return (int) Long.parseLong(value.substring(1), 16);
    }

    public record TargetColors(int player, int hostile, int passive, int other) {
    }

    public record ScreenColors(int selectedFrame, int hoveredFrame, int guiGrid, int worldGrid,
                               int statusWarning, int statusNeutral) {
    }

    public record GuiSectorColors(int base, int activeFill, int activeEdge, int flashFill, int flashEdge) {
    }

    public record WorldSectorColors(int base, int activeEdge, int flashFill, int flashEdge) {
    }

    public record SectorColors(GuiSectorColors gui, WorldSectorColors world) {
    }

    public record Palette(TargetColors targets, ScreenColors screen, SectorColors sector) {
    }

    public record SectorFadeTimes(int sectorTicks, int overviewTicks) {
        public SectorFadeTimes {
            if (sectorTicks < 0 || sectorTicks > MAX_FADE_TICKS || overviewTicks < 0 || overviewTicks > MAX_FADE_TICKS) {
                throw new IllegalArgumentException("Invalid client sector fade duration");
            }
        }

        public int durationTicks(RadarVisualFadeStyle style) {
            return switch (style) {
                case NONE -> 0;
                case SECTOR -> sectorTicks;
                case OVERVIEW -> overviewTicks;
            };
        }
    }

    private record Snapshot(Palette palette, SectorFadeTimes sectorFadeTimes) { }
}
