package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Prevents the server from hanging when LevelChunk.postProcessGeneration() is called
 * re-entrantly from ServerChunkCache.managedBlock() while the main thread is already
 * blocked waiting for a neighbor-update chunk load (triggered e.g. by VaultBlock.tick).
 *
 * postProcessGeneration ticks every pending fluid in the newly-generated chunk
 * synchronously. For water-heavy chunks (oceans, rivers) this can be thousands of
 * FluidState.tick() calls, each spreading water and reading adjacent block states —
 * producing a 15-60 s main-thread freeze.
 *
 * The guard resets a ThreadLocal counter at the start of each postProcessGeneration
 * call and skips any FluidState.tick invocation beyond MAX_FLUID_TICKS_PER_CHUNK.
 * Skipped fluids are not lost: they will be scheduled and ticked normally on the
 * next server tick via the usual LevelTicks pipeline.
 */
@Mixin(LevelChunk.class)
public abstract class PostProcessFluidGuardMixin {

    @Unique
    private static final int MAX_FLUID_TICKS_PER_CHUNK = 512;

    @Unique
    private static final ThreadLocal<int[]> voidrp_fluidBudget =
            ThreadLocal.withInitial(() -> new int[]{MAX_FLUID_TICKS_PER_CHUNK});

    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void voidrp_resetFluidBudget(CallbackInfo ci) {
        voidrp_fluidBudget.get()[0] = MAX_FLUID_TICKS_PER_CHUNK;
    }

    @Redirect(
        method = "postProcessGeneration",
        at = @At(
            value  = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"
        ),
        require = 0
    )
    private void voidrp_guardedFluidTick(FluidState fluidState, Level level, BlockPos pos) {
        int[] budget = voidrp_fluidBudget.get();
        if (budget[0] > 0) {
            budget[0]--;
            fluidState.tick(level, pos);
        } else if (budget[0] == 0) {
            // Log exactly once per postProcessGeneration call (not per skipped block).
            // budget[0] was reset to MAX at HEAD, so 0 means we just hit the limit now.
            VoidRpAsyncAI.LOGGER.debug(
                "[VoidRP] postProcessGeneration fluid-tick budget ({}) exhausted near {} — " +
                "remaining fluid ticks deferred to LevelTicks pipeline",
                MAX_FLUID_TICKS_PER_CHUNK, pos);
            budget[0] = -1; // sentinel: silently skip all further ticks this call
        }
        // budget[0] < 0: silently skip — no logging
    }
}
