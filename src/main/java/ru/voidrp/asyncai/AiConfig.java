package ru.voidrp.asyncai;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side TOML config — auto-created at config/voidrp_async_ai-server.toml
 */
public final class AiConfig {

    public static final ModConfigSpec SPEC;

    // ---- Feature toggles ----
    public static final ModConfigSpec.BooleanValue ASYNC_PATH_ENABLED;
    public static final ModConfigSpec.BooleanValue PATH_CACHE_ENABLED;
    public static final ModConfigSpec.BooleanValue DABS_ENABLED;
    public static final ModConfigSpec.BooleanValue BRAIN_THROTTLE_ENABLED;
    public static final ModConfigSpec.BooleanValue NAV_THROTTLE_ENABLED;
    public static final ModConfigSpec.BooleanValue HIBERNATE_ENABLED;
    public static final ModConfigSpec.BooleanValue SPAWN_THROTTLE_ENABLED;
    public static final ModConfigSpec.BooleanValue ADAPTIVE_THROTTLE_ENABLED;
    public static final ModConfigSpec.BooleanValue PARALLEL_LOS_ENABLED;
    public static final ModConfigSpec.BooleanValue CHUNK_PRELOAD_ENABLED;
    public static final ModConfigSpec.BooleanValue CHUNK_PRELOAD_MODDED_DIMS;

    // ---- Distance thresholds ----
    public static final ModConfigSpec.IntValue ASYNC_PATH_MIN_DIST;
    public static final ModConfigSpec.IntValue THROTTLE_NEAR_DIST;
    public static final ModConfigSpec.IntValue THROTTLE_FAR_DIST;
    public static final ModConfigSpec.IntValue THROTTLE_VFAR_DIST;
    public static final ModConfigSpec.IntValue HIBERNATE_DIST;

    static {
        var builder = new ModConfigSpec.Builder();

        builder.comment("VoidRP Async AI — server performance config").push("general");

        ASYNC_PATH_ENABLED = builder
            .comment("Offload PathFinder.findPath() to worker threads for distant mobs")
            .define("asyncPathEnabled", true);

        PATH_CACHE_ENABLED = builder
            .comment("Cache computed paths — reuse recent results for repeated same-target queries")
            .define("pathCacheEnabled", true);

        DABS_ENABLED = builder
            .comment("DABS: reduce goal/target selector tick rate for distant mobs")
            .define("dabsEnabled", true);

        BRAIN_THROTTLE_ENABLED = builder
            .comment("Reduce Brain.tickSensors() frequency for distant brain-driven mobs (villagers, piglins, etc.)")
            .define("brainThrottleEnabled", true);

        NAV_THROTTLE_ENABLED = builder
            .comment("Reduce PathNavigation.tick() frequency for very distant mobs")
            .define("navThrottleEnabled", true);

        HIBERNATE_ENABLED = builder
            .comment("Skip aiStep() entirely for mobs beyond hibernateDist — full AI hibernation")
            .define("hibernateEnabled", true);

        SPAWN_THROTTLE_ENABLED = builder
            .comment("Reduce spawnForChunk() call frequency for chunks far from players")
            .define("spawnThrottleEnabled", true);

        ADAPTIVE_THROTTLE_ENABLED = builder
            .comment("Automatically increase throttle ratios during TPS lag spikes")
            .define("adaptiveThrottleEnabled", true);

        PARALLEL_LOS_ENABLED = builder
            .comment("Pre-compute hasLineOfSight() in parallel before entity ticking (uses CPU/2 worker threads)")
            .define("parallelLosEnabled", true);

        CHUNK_PRELOAD_ENABLED = builder
            .comment("Predictive chunk pre-loading: submits getChunkFuture(FULL) for chunks the player will enter " +
                     "before the main thread needs them. Uses vanilla's own gen thread pool (all available cores). " +
                     "Eliminates main-thread stalls when crossing chunk borders at speed.")
            .define("chunkPreloadEnabled", true);

        CHUNK_PRELOAD_MODDED_DIMS = builder
            .comment("Allow chunk preloading in modded dimensions (Twilight Forest, Aether, etc.). " +
                     "Disabled by default: tfthreadsafetyaddon presence indicates these dims had threading issues. " +
                     "Enable only after verifying stability.")
            .define("chunkPreloadModdedDimensions", false);

        builder.pop().push("distances");

        ASYNC_PATH_MIN_DIST = builder
            .comment("Mobs closer than this (blocks) use synchronous pathfinding. Default: 32")
            .defineInRange("asyncPathMinDist", 32, 8, 256);

        THROTTLE_NEAR_DIST = builder
            .comment("Beyond this: run goals every 2 ticks (×1.5 for passives). Default: 32")
            .defineInRange("throttleNearDist", 32, 8, 256);

        THROTTLE_FAR_DIST = builder
            .comment("Beyond this: run goals every 4 ticks. Default: 64")
            .defineInRange("throttleFarDist", 64, 16, 512);

        THROTTLE_VFAR_DIST = builder
            .comment("Beyond this: run goals every 8 ticks. Default: 96")
            .defineInRange("throttleVFarDist", 96, 32, 512);

        HIBERNATE_DIST = builder
            .comment("Beyond this: skip aiStep() entirely. Default: 128")
            .defineInRange("hibernateDist", 128, 32, 1024);

        builder.pop();
        SPEC = builder.build();
    }

    public static void register(ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, SPEC);
    }
}
