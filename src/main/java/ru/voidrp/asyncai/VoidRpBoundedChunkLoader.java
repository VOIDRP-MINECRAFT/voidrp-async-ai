package ru.voidrp.asyncai;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Duck-typing interface implemented (via mixin) by {@code net.minecraft.server.level.ServerChunkCache}.
 *
 * Lets the main-thread chunk guards (getBlockState / getBlockEntity) attempt a
 * TIME-BOUNDED synchronous chunk load instead of unconditionally returning AIR/null.
 *
 * A chunk that already exists on disk (e.g. a Waystone destination the player built)
 * loads in a few milliseconds — well within the budget — so the read returns the real
 * block and features like Waystones teleport work. A chunk that would require terrain
 * generation (e.g. a Create machine reading across an unexplored border 20k blocks out)
 * exceeds the budget and yields null, so the guard falls back to AIR/null and the main
 * thread never parks for the multi-second generation that used to trip the Watchdog.
 *
 * Cast a ServerChunkCache to this interface: {@code ((VoidRpBoundedChunkLoader) chunkSource)}.
 */
public interface VoidRpBoundedChunkLoader {

    /**
     * Attempt to bring chunk (cx, cz) to FULL status on the current (server) thread,
     * pumping the chunk executor for at most {@code budgetNs} nanoseconds.
     *
     * MUST be called on the main server thread only.
     *
     * @return the loaded {@link LevelChunk}, or null if it was not available within the budget.
     */
    LevelChunk voidrp$loadChunkBounded(int cx, int cz, long budgetNs);
}
