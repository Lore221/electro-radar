package com.electroradar.radar.visual;

import java.util.Optional;

import com.electroradar.radar.network.NetworkId;

/**
 * Узкий контракт установленного сетевого прибора, который может показать область обзора.
 *
 * <p>Его вызывает только серверный корень монитора после чтения зарегистрированных endpoint,
 * проверки загруженности чанка и совпадения сети. Optional нужен для жизненного цикла до
 * первого server tick, когда новый блок ещё не получил сохранённый NetworkId.</p>
 */
public interface RadarVisualProfileSource {
    /** Возвращает сеть, с которой этот загруженный экземпляр сейчас связан. */
    Optional<NetworkId> visualNetworkId();

    /** Возвращает геометрию обзора либо пусто, пока профиль нельзя безопасно построить. */
    Optional<RadarVisualProfile> radarVisualProfile();
}
