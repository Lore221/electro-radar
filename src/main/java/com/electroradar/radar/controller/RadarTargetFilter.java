package com.electroradar.radar.controller;

import java.util.function.Predicate;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Какие сущности стационарный радар считает целями.
 *
 * <p>Исключаются объекты, которые не являются «телами» на карте: предметы на земле, опыт,
 * снаряды, облака эффектов, служебные display/interaction/marker и апертуры самих радаров
 * (иначе каждый радар видел бы свои панели как неподвижную цель). Остальное — мобы, игроки,
 * транспорт, прочие Create-конструкции — остаётся целями.</p>
 */
final class RadarTargetFilter {
    static final Predicate<Entity> ELIGIBLE = RadarTargetFilter::isTarget;

    private RadarTargetFilter() {
    }

    private static boolean isTarget(Entity entity) {
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof Projectile
                || entity instanceof AreaEffectCloud || entity instanceof Display
                || entity instanceof Interaction || entity instanceof Marker) {
            return false;
        }
        return !(entity instanceof AbstractContraptionEntity contraption
                && contraption.getContraption() instanceof RadarPanelContraption);
    }
}
