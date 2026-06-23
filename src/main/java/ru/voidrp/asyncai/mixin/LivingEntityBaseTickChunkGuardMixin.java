package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards LivingEntity.baseTick() against main-thread chunk-load deadlock caused by
 * the isInWaterRainOrBubble() → isInBubbleColumn() → getInBlockState() call chain.
 *
 * LivingEntity.baseTick() calls isInWaterRainOrBubble() BEFORE calling super.baseTick(),
 * so EntityBaseTickChunkGuardMixin fires too late to prevent this code path.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBaseTickChunkGuardMixin {

    @Inject(
        method = "baseTick",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_skipLivingBaseTickInUnloadedChunk(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel serverLevel)) return;
        int cx = self.blockPosition().getX() >> 4;
        int cz = self.blockPosition().getZ() >> 4;
        if (serverLevel.getChunkSource().getChunkNow(cx, cz) == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] LivingEntity.baseTick chunk guard — {} at chunk [{},{}] not loaded " +
                    "— skipping baseTick to prevent isInBubbleColumn deadlock{}",
                    self.getClass().getSimpleName(), cx, cz,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            ci.cancel();
        }
    }
}
