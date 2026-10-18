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

Подробный журнал радаров (`[ElectroRadar/RadarDebug]`) включён по умолчанию. Его можно
настроить JVM-флагами без пересборки:

- `-Delectroradar.diagnostics=false` — выключить подробные события; ошибки останутся в журнале;
- `-Delectroradar.diagnostics.queries=true` — дополнительно писать запросы потребителей.
