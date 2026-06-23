package ru.voidrp.asyncai.mixin;

import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards BlockPredicate.matches() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-31 17:52:59):
 *   LivingEntity.onChangedBlock() → EnchantmentHelper.runLocationChangedEffects()
 *   → ConditionalEffect.matches() → EntityPredicate.matches() → LocationPredicate.matches()
 *   → BlockPredicate.matches() → BlockAndTintGetter.getBlockState() → Level.getBlockState()
 *   → ServerChunkCache.getChunk(FULL, true) → MainThreadExecutor.managedBlock()
 *   → LockSupport.parkNanos() — main thread blocks waiting for chunk load.
 *
 * When a player moves to a new block, enchantment location-changed effects fire.
 * If an enchantment has a ConditionalEffect with a LocationPredicate that includes a
 * BlockPredicate, it calls Level.getBlockState() which may block if the chunk (possibly
 * at an offset position) is not loaded.
 *
 * Fix: inject at HEAD of BlockPredicate.matches(). If the level is a ServerLevel and
 * the chunk at pos is not loaded, return false immediately — the predicate cannot match
 * a block in an unloaded chunk, so false is correct behaviour and prevents the hang.
 */
@Mixin(BlockPredicate.class)
public abstract class BlockPredicateChunkGuardMixin {

    @Inject(
        method = "matches",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardChunkLoad(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (level.getChunkSource().getChunkNow(cx, cz) == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] BlockPredicate chunk guard — chunk [{},{}] not loaded at " +
                    "{},{},{} — returning false to prevent main-thread deadlock{}",
                    cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(false);
        }
    }
}
