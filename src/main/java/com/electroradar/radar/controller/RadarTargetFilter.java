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

/**
 * Какие сущности стационарный радар считает целями.
 *
 * <p>Цель — физическое тело, которое имеет смысл показать на карте или передать прибору:
 * мобы, игроки, транспорт, Create-конструкции и снаряды. Снаряды (стрелы, фаерболы, трезубцы,
 * фейерверки) оставлены намеренно: это быстрые реальные объекты, и будущим боевым приборам
 * нужно их видеть. Цена — воткнувшиеся стрелы видны, пока не исчезнут; отделять их стоит
 * классификацией, а не фильтром поиска.</p>
 *
 * <p>Исключены только объекты, которые не являются телами: предметы на земле и опыт (их
 * десятки у любой фермы), облака эффектов, служебные display/interaction/marker и апертуры
 * радаров. Апертура — неподвижная часть самого прибора; без исключения каждый радар видел бы
 * свои и соседние панели как вечную цель.</p>
 */
final class RadarTargetFilter {
    static final Predicate<Entity> ELIGIBLE = RadarTargetFilter::isTarget;

    private RadarTargetFilter() {
    }

    private static boolean isTarget(Entity entity) {
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || entity instanceof AreaEffectCloud || entity instanceof Display
                || entity instanceof Interaction || entity instanceof Marker) {
            return false;
        }
        return !(entity instanceof AbstractContraptionEntity contraption
                && contraption.getContraption() instanceof RadarPanelContraption);
    }
}
