/**
 * Ядро решателя наведения снарядов Create Big Cannons при постоянном линейном сопротивлении.
 *
 * <p><b>Модель.</b> Каждый тик CBC проверяет столкновения на отрезке {@code p_n → p_n + v_(n+1)}
 * ({@code AbstractCannonProjectile.clipAndDamage}), затем сдвигает снаряд на {@code v + a/2} и
 * меняет скорость на {@code a = −k·v − g·ŷ}. Для единичного направления ствола {@code u} этот
 * отрезок в координатах центра пушки равен {@code (R + f·U)·u − (g·B + f·w)·ŷ}, {@code 0 ≤ f ≤ 1}
 * ({@link com.electroradar.cannon.ballistics.Sweep}). Попадание в тик {@code n} сводится к
 * квадратному уравнению по {@code f} ({@link com.electroradar.cannon.ballistics.SweepIntersection}).</p>
 *
 * <p><b>Поиск.</b> {@link com.electroradar.cannon.ballistics.FlightHorizon} доказуемо ограничивает
 * диапазон тиков, а {@link com.electroradar.cannon.ballistics.InterceptSearch} перебирает его,
 * пропуская только тики, в которых попадание исключено оценкой. Пустой результат на доказанном
 * горизонте означает отсутствие попадания в модели, а не «не найдено».</p>
 *
 * <p><b>Соглашения.</b> Длины — в блоках, время — в тиках снаряда, координаты цели — относительно
 * центра пушки на момент запуска. Угол места положителен вверх, рыскание — в соглашении Minecraft
 * {@code atan2(−x, z)}. Пакет не зависит от классов Minecraft и CBC: преобразование игровых
 * сущностей, задержек запуска и фазы тика — задача адаптера пушки.</p>
 *
 * <p>Обоснование формул — {@code CBC_AIMING_REVIEW.md}, {@code CBC_AIMING_B1_REVIEW.md} и
 * {@code CBC_AIMING_HORIZON.md} в ветке {@code newcore}.</p>
 */
package com.electroradar.cannon.ballistics;
