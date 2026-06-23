package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ParallelLosManager;

import java.util.function.BooleanSupplier;

/**
 * Fires ParallelLosManager.preComputeForLevel() immediately before
 * ServerLevel starts iterating over its EntityTickList.
 *
 * The injection point — just before EntityTickList.forEach() — is the latest
 * moment where chunk state is guaranteed stable (ServerChunkCache.tick() has
 * already run) and before any entity's AI reads the LOS cache.
 */
@Mixin(ServerLevel.class)
public abstract class ParallelPreTickMixin {

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"
        ),
        require = 0
    )
    private void voidrp_parallelPreTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ParallelLosManager.preComputeForLevel((ServerLevel) (Object) this);
    }
}
