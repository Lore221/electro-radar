# Electro Radar

Проект мода для Minecraft 1.21.1 на NeoForge.

## Требования

- JDK 21 (64-bit)
- Gradle устанавливать отдельно не нужно: используется Gradle Wrapper.

## Команды

```bash
./gradlew runClient
./gradlew build
```

Готовый мод появится в `build/libs/`.

## Диагностика

Подробный журнал радаров (`[ElectroRadar/RadarDebug]`) по умолчанию выключен; ошибки пишутся
всегда. Его можно включить JVM-флагами без пересборки:

- `-Delectroradar.diagnostics=true` — подробные события сети, посещений секторов и мониторов;
- `-Delectroradar.diagnostics.queries=true` — дополнительно писать запросы потребителей;
- `-Delectroradar.diagnostics.visuals=true` — дополнительно писать частые клиентские события секторов.
