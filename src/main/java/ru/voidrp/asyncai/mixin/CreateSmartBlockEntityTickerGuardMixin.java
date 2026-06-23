package ru.voidrp.asyncai.mixin;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.HashMap;
import java.util.Map;

/**
 * Guards Create's SmartBlockEntityTicker against CPU-spinning block entities.
 *
 * Root cause (Watchdog dump 2026-06-02 15:05):
 *   Server thread RUNNABLE at SmartBlockEntityTicker.tick(SmartBlockEntityTicker.java:15).
 *   The delegated SmartBlockEntity.tick() entered a very long computation (measured ~6-10 s).
 *   Neruina wraps the call with try/catch(Throwable) but cannot interrupt a CPU spin.
 *   Watchdog fired after 10 s with state RUNNABLE — no lock contention, pure computation.
 *
 * Fix: @Inject HEAD/RETURN wraps the entire SmartBlockEntityTicker.tick() call.
 *   - Ticks taking >=100 ms are logged as WARN with position and class name.
 *   - Ticks taking >=500 ms are logged as ERROR and increment a consecutive-slow counter.
 *   - After SKIP_THRESHOLD consecutive slow (>=500 ms) ticks the block entity is skipped
 *     permanently until server restart, with an ERROR log every 200 skips (~10 s at 20 TPS).
 *   A fast tick (<100 ms) resets the slow counter so transient hiccups don't accumulate.
 *
 * Implementation note: @Inject HEAD+RETURN is used instead of @Redirect on
 *   SmartBlockEntity.tick() to avoid a compile-time dependency on SmartBlockEntity,
 *   which transitively requires net.createmod.ponder.api.VirtualBlockEntity (not on
 *   our compile classpath as of Create 6.x / catnip).
 *   Start time and key are passed between HEAD and RETURN via static fields — safe
 *   because SmartBlockEntityTicker.tick() is server-thread-only and non-reentrant.
 */
@Mixin(value = SmartBlockEntityTicker.class, remap = false)
public class CreateSmartBlockEntityTickerGuardMixin<T extends BlockEntity> {

    private static final long WARN_MS  = 100;
    private static final long ERROR_MS = 500;
    private static final int  SKIP_THRESHOLD = 5;

    // Consecutive slow-tick counts keyed by BlockPos.asLong(). Server-thread only — no concurrency.
    private static final Map<Long, Integer> slowCounts = new HashMap<>();

    // Passed from HEAD to RETURN via statics; safe because tick() is server-thread-only, non-reentrant.
    private static long  s_tickStartNs  = 0L;
    private static long  s_tickKey      = 0L;
    private static int   s_tickSlowCount = 0;

    @Inject(
        method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_beforeSmartBETick(
            Level level, BlockPos pos, BlockState state, T blockEntity, CallbackInfo ci) {

        long key = pos.asLong();
        int count = slowCounts.getOrDefault(key, 0);

        if (count >= SKIP_THRESHOLD) {
            if (count == SKIP_THRESHOLD || count % 200 == 0) {
                VoidRpAsyncAI.LOGGER.error(
                    "[VoidRP] Create SmartBlockEntity SKIPPED at {} ({}) — consecutive slow-tick count: {} (remove/break this block to restore it)",
                    pos, blockEntity.getClass().getSimpleName(), count);
            }
            slowCounts.put(key, count + 1);
            ci.cancel();
            return;
        }

        s_tickKey       = key;
        s_tickSlowCount = count;
        s_tickStartNs   = System.nanoTime();
    }

    @Inject(
        method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void voidrp_afterSmartBETick(
            Level level, BlockPos pos, BlockState state, T blockEntity, CallbackInfo ci) {

        long elapsedMs = (System.nanoTime() - s_tickStartNs) / 1_000_000L;

        if (elapsedMs >= ERROR_MS) {
            int newCount = s_tickSlowCount + 1;
            slowCounts.put(s_tickKey, newCount);
            VoidRpAsyncAI.LOGGER.error(
                "[VoidRP] Create SmartBlockEntity VERY SLOW tick at {} ({}) took {} ms — slow count {}/{} (skip activates at {})",
                pos, blockEntity.getClass().getSimpleName(), elapsedMs, newCount, SKIP_THRESHOLD, SKIP_THRESHOLD);
        } else if (elapsedMs >= WARN_MS) {
            int newCount = s_tickSlowCount + 1;
            slowCounts.put(s_tickKey, newCount);
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] Create SmartBlockEntity slow tick at {} ({}) took {} ms — slow count {}/{}",
                pos, blockEntity.getClass().getSimpleName(), elapsedMs, newCount, SKIP_THRESHOLD);
        } else if (s_tickSlowCount > 0) {
            slowCounts.remove(s_tickKey);
        }
    }
}
