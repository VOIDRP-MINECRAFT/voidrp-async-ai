package ru.voidrp.asyncai;

/**
 * Snapshot of AiConfig values refreshed once per tick (every 20 ticks in practice).
 * Replaces per-call ModConfigSpec.ConfigValue.get() in hot paths — the NeoForge
 * config accessor does a ConcurrentHashMap lookup + holder resolution each call.
 */
public final class ConfigCache {

    // Feature toggles
    public static volatile boolean ASYNC_PATH_ENABLED      = true;
    public static volatile boolean PATH_CACHE_ENABLED      = true;
    public static volatile boolean DABS_ENABLED            = true;
    public static volatile boolean BRAIN_THROTTLE_ENABLED  = true;
    public static volatile boolean NAV_THROTTLE_ENABLED    = true;
    public static volatile boolean HIBERNATE_ENABLED       = true;
    public static volatile boolean SPAWN_THROTTLE_ENABLED  = true;
    public static volatile boolean ADAPTIVE_THROTTLE_ENABLED = true;
    public static volatile boolean PARALLEL_LOS_ENABLED    = true;
    public static volatile boolean CHUNK_PRELOAD_ENABLED   = true;
    public static volatile boolean CHUNK_PRELOAD_MODDED_DIMS = false;

    // Distance thresholds
    public static volatile int ASYNC_PATH_MIN_DIST = 32;
    public static volatile int THROTTLE_NEAR_DIST  = 32;
    public static volatile int THROTTLE_FAR_DIST   = 64;
    public static volatile int THROTTLE_VFAR_DIST  = 96;
    public static volatile int HIBERNATE_DIST       = 128;

    public static void refresh() {
        ASYNC_PATH_ENABLED       = AiConfig.ASYNC_PATH_ENABLED.get();
        PATH_CACHE_ENABLED       = AiConfig.PATH_CACHE_ENABLED.get();
        DABS_ENABLED             = AiConfig.DABS_ENABLED.get();
        BRAIN_THROTTLE_ENABLED   = AiConfig.BRAIN_THROTTLE_ENABLED.get();
        NAV_THROTTLE_ENABLED     = AiConfig.NAV_THROTTLE_ENABLED.get();
        HIBERNATE_ENABLED        = AiConfig.HIBERNATE_ENABLED.get();
        SPAWN_THROTTLE_ENABLED   = AiConfig.SPAWN_THROTTLE_ENABLED.get();
        ADAPTIVE_THROTTLE_ENABLED = AiConfig.ADAPTIVE_THROTTLE_ENABLED.get();
        PARALLEL_LOS_ENABLED     = AiConfig.PARALLEL_LOS_ENABLED.get();
        CHUNK_PRELOAD_ENABLED    = AiConfig.CHUNK_PRELOAD_ENABLED.get();
        CHUNK_PRELOAD_MODDED_DIMS = AiConfig.CHUNK_PRELOAD_MODDED_DIMS.get();
        ASYNC_PATH_MIN_DIST      = AiConfig.ASYNC_PATH_MIN_DIST.get();
        THROTTLE_NEAR_DIST       = AiConfig.THROTTLE_NEAR_DIST.get();
        THROTTLE_FAR_DIST        = AiConfig.THROTTLE_FAR_DIST.get();
        THROTTLE_VFAR_DIST       = AiConfig.THROTTLE_VFAR_DIST.get();
        HIBERNATE_DIST           = AiConfig.HIBERNATE_DIST.get();
    }
}
