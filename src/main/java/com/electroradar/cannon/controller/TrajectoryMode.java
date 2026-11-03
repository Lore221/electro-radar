package com.electroradar.cannon.controller;

import java.util.Objects;
import java.util.function.Function;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;

import com.electroradar.cannon.ballistics.ArcFamily;

/**
 * Режим траектории на боковой панели контроллера. Значение хранится в самом контроллере и задаёт
 * семейство решения ядра.
 *
 * <p>Иконки рисуются только на клиенте, поэтому их поставщик подключает клиентская настройка; на
 * сервере значение не используется и остаётся пустой иконкой Create.</p>
 */
public enum TrajectoryMode implements INamedIconOptions {
    FLAT(ArcFamily.LOW),
    HIGH(ArcFamily.HIGH);

    private static volatile Function<TrajectoryMode, AllIcons> icons = mode -> AllIcons.I_NONE;

    private final ArcFamily family;

    TrajectoryMode(ArcFamily family) {
        this.family = family;
    }

    public ArcFamily family() {
        return family;
    }

    /** Вызывается клиентской настройкой мода один раз. */
    public static void useIcons(Function<TrajectoryMode, AllIcons> provider) {
        icons = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public AllIcons getIcon() {
        return icons.apply(this);
    }

    @Override
    public String getTranslationKey() {
        return "message.electroradar.target_controller.trajectory." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
