package com.electroradar.radar.visual;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Неизменяемый визуальный контракт радара: ключ источника и готовая геометрия в мировых X/Z.
 *
 * <p>Монитор ничего не знает о форме радара. Новый радар сам строит контур, линии и части,
 * после чего общий кодек и оба клиентских рендера используют одни и те же примитивы. У сектора
 * есть готовая внешняя граница и части анимации. Сервер проверяет hitbox по объединению тех же
 * треугольников, поэтому контур и обнаружение не расходятся. Поставщик определяет, влияет ли
 * число частей на форму: у треугольного веера оно задаёт также внешнюю ломаную.
 * {@code inactiveSectorFadeStyle} задаёт пресет визуального следа выключенной зоны;
 * его длительность берётся из клиентского конфига и не управляет наблюдением или памятью целей.</p>
 */
public record RadarVisualProfile(ResourceLocation dimension, BlockPos radarPosition,
                                 double centerX, double centerY, double centerZ,
                                 RadarVisualGeometry.Coverage coverage,
                                 RadarVisualAnimation animation, int sectorOrderVariant,
                                 RadarVisualFadeStyle inactiveSectorFadeStyle) {
    public static final int DEFAULT_SECTOR_COUNT = 7;
    public static final int MIN_SECTOR_COUNT = 1;
    public static final int MAX_SECTOR_COUNT = 64;
    public static final double FULL_TURN = Math.PI * 2.0D;

    public RadarVisualProfile {
        Objects.requireNonNull(dimension, "dimension");
        radarPosition = Objects.requireNonNull(radarPosition, "radarPosition").immutable();
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(animation, "animation");
        Objects.requireNonNull(inactiveSectorFadeStyle, "inactiveSectorFadeStyle");
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                || coverage.sectors().size() < MIN_SECTOR_COUNT
                || coverage.sectors().size() > MAX_SECTOR_COUNT
                || !RadarSectorOrder.validVariant(sectorOrderVariant)) {
            throw new IllegalArgumentException("Invalid radar visual profile");
        }
    }

    /** Профили без собственного варианта используют первый порядок и выключенный след. */
    public RadarVisualProfile(ResourceLocation dimension, BlockPos radarPosition,
                              double centerX, double centerY, double centerZ,
                              RadarVisualGeometry.Coverage coverage, RadarVisualAnimation animation) {
        this(dimension, radarPosition, centerX, centerY, centerZ, coverage, animation, 1, RadarVisualFadeStyle.NONE);
    }

    /** Совместимый путь для существующих направленных профилей: по умолчанию они идут секторами. */
    public RadarVisualProfile(ResourceLocation dimension, BlockPos radarPosition,
                              double centerX, double centerY, double centerZ,
                              RadarVisualGeometry.Coverage coverage) {
        this(dimension, radarPosition, centerX, centerY, centerZ, coverage,
                RadarVisualAnimation.SECTOR_SWEEP);
    }

    /** Прямоугольная Coverage; по умолчанию направленные/секторные поставщики используют sweep. */
    public static RadarVisualProfile rectangle(ResourceLocation dimension, BlockPos radarPosition,
                                               double centerX, double centerY, double centerZ,
                                               double halfX, double halfZ, int sectorCount) {
        return rectangle(dimension, radarPosition, centerX, centerY, centerZ,
                halfX, halfZ, sectorCount, RadarVisualAnimation.SECTOR_SWEEP);
    }

    /** Создаёт прямоугольный профиль с выбранным самим радаром способом анимации. */
    public static RadarVisualProfile rectangle(ResourceLocation dimension, BlockPos radarPosition,
                                               double centerX, double centerY, double centerZ,
                                               double halfX, double halfZ, int sectorCount,
                                               RadarVisualAnimation animation) {
        return new RadarVisualProfile(dimension, radarPosition, centerX, centerY, centerZ,
                RadarVisualGeometry.rectangle(centerX, centerZ, halfX, halfZ, sectorCount), animation);
    }

    /** Готовый построитель будущей направленной панели; монитор использует тот же контракт. */
    public static RadarVisualProfile circularSector(ResourceLocation dimension, BlockPos radarPosition,
                                                    double centerX, double centerY, double centerZ,
                                                    double radius, double startAngle, double sweepAngle,
                                                    int sectorCount) {
        return circularSector(dimension, radarPosition, centerX, centerY, centerZ,
                radius, startAngle, sweepAngle, sectorCount, RadarVisualAnimation.SECTOR_SWEEP);
    }

    /** Направленная геометрия принимает любой поддерживаемый угол и число частей. */
    public static RadarVisualProfile circularSector(ResourceLocation dimension, BlockPos radarPosition,
                                                    double centerX, double centerY, double centerZ,
                                                    double radius, double startAngle, double sweepAngle,
                                                    int sectorCount, RadarVisualAnimation animation) {
        return new RadarVisualProfile(dimension, radarPosition, centerX, centerY, centerZ,
                RadarVisualGeometry.circularSector(centerX, centerZ, radius,
                        startAngle, sweepAngle, sectorCount), animation);
    }

    /** Прямые треугольные части с заданными источником порядком загорания и пресетом следа. */
    public static RadarVisualProfile triangularFan(ResourceLocation dimension, BlockPos radarPosition,
                                                   double centerX, double centerY, double centerZ,
                                                   double radius, double startAngle, double sweepAngle,
                                                   int sectorCount, int sectorOrderVariant,
                                                   RadarVisualFadeStyle inactiveSectorFadeStyle) {
        return new RadarVisualProfile(dimension, radarPosition, centerX, centerY, centerZ,
                RadarVisualGeometry.triangularFan(centerX, centerZ, radius, startAngle, sweepAngle, sectorCount),
                RadarVisualAnimation.SECTOR_SWEEP, sectorOrderVariant, inactiveSectorFadeStyle);
    }

    public int sectorCount() {
        return coverage.sectors().size();
    }

    /** Стабильный ключ установленного источника, независимый от изменений его области. */
    public Key key() {
        return new Key(dimension, radarPosition);
    }

    public record Key(ResourceLocation dimension, BlockPos radarPosition) {
        public Key {
            Objects.requireNonNull(dimension, "dimension");
            radarPosition = Objects.requireNonNull(radarPosition, "radarPosition").immutable();
        }
    }
}
