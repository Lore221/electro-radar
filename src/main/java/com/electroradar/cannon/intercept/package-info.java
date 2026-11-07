/**
 * Прототип ядра перехвата снарядов больших пушек CBC зенитной автопушкой.
 *
 * <p>Три задачи и их классы:</p>
 * <ol>
 *   <li><b>Выбор снаряда</b> — {@link com.electroradar.cannon.intercept.ThreatSelector}: прогноз
 *       падения, угроза обороняемой зоне, срочность и удержание цели.</li>
 *   <li><b>Траектория, окно и сопровождение</b> — {@link com.electroradar.cannon.intercept.HostileShell}
 *       (точный прогноз по рекурсии CBC, {@link com.electroradar.cannon.intercept.LinearDragMotion}),
 *       {@link com.electroradar.cannon.intercept.EngagementPlanner} (перебор момента пуска поверх
 *       решателя {@code cannon.ballistics}, окно огня, очередь) и
 *       {@link com.electroradar.cannon.intercept.SlewModel} (успеет ли установка).</li>
 *   <li><b>Уничтожение</b> — {@link com.electroradar.cannon.intercept.InterceptFuze} (командно-неконтактный
 *       взрыватель с упреждением подрыва) и {@link com.electroradar.cannon.intercept.FragmentKillModel}
 *       (фугасная зона и осколочный конус CBC).</li>
 * </ol>
 *
 * <p>Пакет, как и {@code cannon.ballistics}, не зависит от Minecraft и CBC: чтение сущностей,
 * взрыватель как предмет CBC и применение поражения — задача адаптеров. Модель, обоснования и
 * план встраивания — {@code INTERCEPTOR_MODEL.md} в корне репозитория.</p>
 */
package com.electroradar.cannon.intercept;
