package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Guards EndDragonFight.isArenaLoaded() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-30 21:12):
 *   EndDragonFight.tick() → YUNG's BetterEndIsland mixin (tickFight injection)
 *   → EndDragonFight.isArenaLoaded() → Level.getChunk(int, int)
 *   → ServerChunkCache.getChunk(require=true) → MainThreadExecutor.managedBlock()
 *   → LockSupport.parkNanos() — main thread parks waiting for End arena chunks.
 *
 * Fix: pre-check all 25 arena chunks (-2..2 x -2..2 in End chunk coords) non-blockingly
 * via getChunkNow(). If any chunk is absent from memory, cancel isArenaLoaded() with
 * false — the dragon fight tick defers until the chunks are fully loaded, avoiding the park.
 * When all 25 chunks are already in memory the subsequent vanilla Level.getChunk() calls
 * return from the level chunk array immediately without touching managedBlock.
 */
@Mixin(EndDragonFight.class)
public abstract class EndDragonFightArenaChunkGuardMixin {

    @Shadow
    private ServerLevel level;

    @Unique
    private static final AtomicLong voidrp$lastWarnNs = new AtomicLong(0L);

    @Inject(method = "isArenaLoaded", at = @At("HEAD"), cancellable = true)
    private void voidrp$guardArenaChunks(CallbackInfoReturnable<Boolean> cir) {
        ServerChunkCache cache = this.level.getChunkSource();
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                if (cache.getChunkNow(cx, cz) == null) {
                    long now = System.nanoTime();
                    long prev = voidrp$lastWarnNs.get();
                    if (now - prev > 5_000_000_000L && voidrp$lastWarnNs.compareAndSet(prev, now)) {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] EndDragonFight.isArenaLoaded: chunk [{},{}] not immediately available — " +
                            "returning false to prevent main-thread deadlock", cx, cz);
                    }
                    cir.setReturnValue(false);
                    return;
                }
            }
        }
    }
}
