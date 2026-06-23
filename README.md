# ⚡ VoidRP Async AI

> Критически важный NeoForge мод — async pathfinding, throttle AI и 60+ миксинов защиты от chunk deadlock'ов.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen?logo=minecraft)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Mixins](https://img.shields.io/badge/Mixins-60%2B-blueviolet)
![License](https://img.shields.io/badge/license-proprietary-red)

---

## ⚠️ Критическая важность

Без этого мода сервер **регулярно зависает** из-за chunk deadlock'ов, которые вызывают сторонние моды. Мод обязателен для стабильной работы VoidRP.

---

## 🗺️ Место в экосистеме

```
  Minecraft Server (Mohist 1.21.1) — main thread
        │
  voidrp-async-ai (NeoForge мод)
        │
        ├── Mixin → ChunkGuard: блокирующий getChunk() → getChunkNow()
        ├── Mixin → EntityCollisionGuard: пропуск дорогих entity scans
        ├── Mixin → FluidGuard: лимит 512 fluid tick/chunk
        └── Async: pathfinding в worker threads
```

---

## ✨ Два типа функций

### 1. Производительность AI
- **Async Pathfinding** — `PathFinder.findPath()` выносится в worker-потоки для мобов дальше N блоков
- **Entity Hibernate** — полное отключение AI/тиков для сущностей вне зоны видимости игроков
- **Brain Throttle** — снижение частоты тиков Brain (память/поведение) для далёких мобов
- **Navigation Throttle** — реже пересчитывает пути для мобов вне активной зоны
- **Line-of-Sight Cache** — кэш результатов ray-cast видимости

### 2. Защита от chunk deadlock'ов (60+ миксинов)

Паттерн: сторонний код вызывает `ServerChunkCache.getChunk()` из main thread → main thread блокируется ожидая chunk worker → deadlock.

**Покрытые паттерны:**

| Категория | Примеры миксинов |
|---|---|
| Базовые chunk guard | `BlockCollisionsChunkGuard`, `EntityBaseTickChunkGuard` |
| Моды: движение | `PlayerTravelChunkGuard`, `LivingEntityTravelGuard` |
| Моды: структуры | `StructureManagerChunkGuard`, `AetherPortalChunkGuard` |
| Плагины | `EssentialsRespawnChunkGuard`, `CitizensChunkUnloadGuard` |
| Сторонние моды | `IafPortalDataChunkGuard`, `LeafcutterAntForageChunkGuard` |
| Entity | `ItemEntityCollisionGuard`, `ServerPlayerGameModeMixin` |
| Fluid | `PostProcessFluidGuard`, `LevelFluidStateGuard` |
| Async сохранение | `PlayerDataSaveAsync`, `ChunkActivityMapAsyncSave` |
| Misc | `DragonFightSpamGuard`, `MekanismRadiationThrottle` |

---

## 📋 Требования

| Компонент | Версия |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Java | 21 |

---

## 🚀 Сборка и деплой

```bash
cd voidrp_async_ai
./gradlew build
# → build/libs/voidrp_async_ai-1.0.0.jar

# Деплой на сервер
cp build/libs/voidrp_async_ai-1.0.0.jar \
   ../minecraft_server/mods/voidrp_async_ai-1.0.0.jar
# Перезапустить сервер
```

---

## 🏗️ Структура миксинов

```
src/main/java/ru/voidrp/asyncai/mixin/
├── *ChunkGuardMixin.java         # блокирование deadlock-путей
├── EntityCollisionGuardMixin.java # ItemEntity → skip getEntityCollisions
├── PostProcessFluidGuardMixin.java
├── PlayerDataSaveAsyncMixin.java
├── NavigationThrottleMixin.java
├── EntityHibernateMixin.java
├── BrainThrottleMixin.java
└── ...60+ файлов
```

---

## 🔗 Связанные репозитории

| Репо | Связь |
|---|---|
| [voidrp-cpm-companion](https://github.com/VOIDRP-MINECRAFT/voidrp-cpm-companion) | `CosmeticArmorSaveAsyncMixin` для CPM |
| [voidrp-anticheat](https://github.com/VOIDRP-MINECRAFT/voidrp-anticheat) | Работает параллельно на сервере |

---

<div align="center">
<a href="https://void-rp.ru">🌐 Сайт</a> ·
<a href="https://github.com/VOIDRP-MINECRAFT">🏠 Организация</a>
</div>
