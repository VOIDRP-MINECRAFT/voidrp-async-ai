# VoidRP Async AI

NeoForge 1.21.1 мод — оптимизация серверной производительности: выносит pathfinding мобов в рабочие потоки и троттлирует AI удалённых сущностей.

## Зачем

На загруженном сервере pathfinding сотен мобов блокирует главный поток и роняет TPS. Этот мод разгружает главный поток:
- вычисляет пути в фоновых потоках
- снижает частоту AI-тиков для мобов вдали от игроков
- полностью «засыпает» мобов за пределами заданной дистанции
- кэширует линию видимости и пути

## Требования

- NeoForge 21.1.218 (Minecraft 1.21.1)
- Java 21

## Сборка

```bash
cd voidrp_async_ai
./gradlew build
# → build/libs/voidrp_async_ai-*.jar
```

## Возможности

| Функция | Описание |
|---|---|
| **Async Pathfinding** | `PathFinder.findPath()` в worker-потоках для мобов дальше `asyncPathMinDist` блоков |
| **Path Cache** | Повторные запросы пути к той же цели возвращают кэшированный результат |
| **DABS** | Снижение частоты goal-селекторов для удалённых мобов |
| **Brain Throttle** | Снижение частоты `tickSensors()` для мобов с Brain (жители, пиглины) |
| **Nav Throttle** | Снижение частоты `PathNavigation.tick()` для очень далёких мобов |
| **AI Hibernate** | Полное отключение `aiStep()` для мобов за пределами `hibernateDist` |
| **Spawn Throttle** | Снижение частоты `spawnForChunk()` для далёких чанков |
| **Adaptive Throttle** | Автоувеличение троттлинга при просадках TPS |
| **Parallel LoS** | Параллельный расчёт `hasLineOfSight()` до тика сущностей |
| **Chunk Preload** | Предзагрузка чанков по вектору движения игрока |

## Конфигурация

После первого запуска создаётся `voidrp_async_ai-server.toml`:

```toml
[general]
asyncPathEnabled = true
pathCacheEnabled = true
dabsEnabled = true
brainThrottleEnabled = true
navThrottleEnabled = true
hibernateEnabled = true
spawnThrottleEnabled = true
adaptiveThrottleEnabled = true
parallelLosEnabled = true
chunkPreloadEnabled = true

asyncPathMinDist = 32       # ближе — синхронный pathfinding
throttleNearDist = 32       # goals каждые 2 тика
throttleFarDist = 64        # goals каждые 4 тика
hibernateDist = 128         # полный AI hibernate

chunkPreloadModdedDimensions = false
```

## Миксины (ключевые)

- `MobAiMixin`, `NavigationThrottleMixin`, `BrainThrottleMixin` — троттлинг AI
- `PathNavigationMixin` — перехват pathfinding → async worker
- `BlockCollisionsChunkGuardMixin` — защита от NPE при телепортации в незагруженный чанк
- `PostProcessFluidGuardMixin` — лимит `FluidState.tick()` при загрузке чанков
- `PlayerDataSaveAsyncMixin` — асинхронное сохранение данных игроков
- `EntityHibernateMixin` — skip AI для засыпающих мобов
