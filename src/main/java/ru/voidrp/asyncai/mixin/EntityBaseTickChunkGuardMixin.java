package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Entity.baseTick() against main-thread chunk-load deadlock caused by
 * third-party mixin handlers (e.g. sliceanddice) that call Level.getBlockState()
 * from within baseTick on an unloaded chunk.
 *
 * Root cause (Watchdog dump 2026-05-30 14:43):
 *   ServerPlayer.doTick() → LivingEntity.tick() → Entity.tick()
 *   → LivingEntity.baseTick(482) → Entity.baseTick()
 *   → Entity.handler$eng000$sliceanddice$baseTick(19212)
 *   → Level.getBlockState() → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → parkNanos() — main thread blocked.
 *   Player IncX teleported to chunk [-871,311] (x=-13923,z=4978); the chunk
 *   was still loading when baseTick fired, causing sliceanddice's injected
 *   handler to call getBlockState on the unloaded chunk.
 *
 * Fix: @Redirect cannot target the getBlockState call inside the synthetic
 * handler$ method. Instead, cancel Entity.baseTick() entirely at HEAD when
 * the entity's own chunk is not yet at FULL status (getChunkNow() == null).
 * Skipping baseTick for one tick is safe: no fire/portal/freeze processing
 * for one tick while the chunk loads is far better than a 10+ second freeze.
 */
@Mixin(Entity.class)
public abstract class EntityBaseTickChunkGuardMixin {

    @Shadow
    public abstract net.minecraft.world.level.Level level();

    @Shadow
    public abstract net.minecraft.core.BlockPos blockPosition();

    @Inject(
        method = "baseTick",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_skipBaseTickInUnloadedChunk(CallbackInfo ci) {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        int cx = blockPosition().getX() >> 4;
        int cz = blockPosition().getZ() >> 4;
        if (serverLevel.getChunkSource().getChunkNow(cx, cz) == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                Entity self = (Entity) (Object) this;
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] Entity.baseTick chunk guard — {} at chunk [{},{}] not loaded " +
                    "— skipping baseTick to prevent sliceanddice deadlock{}",
                    self.getClass().getSimpleName(), cx, cz,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            ci.cancel();
        }
    }
}
