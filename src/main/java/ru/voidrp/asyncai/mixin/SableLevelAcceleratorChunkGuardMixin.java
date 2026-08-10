package ru.voidrp.asyncai.mixin;

import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.MainThreadChunkLoad;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Sable's {@code LevelAccelerator} reads against a main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog HUNG_TICK dumps 2026-07-26 00:16 / 00:26, identical):
 *   ServerLevel.tick → sable tickPlotContainer → SubLevelContainer.tick
 *   → SubLevelPhysicsSystem.tick → PhysicsChunkTicketManager.update → addTicket
 *   → RapierPhysicsPipeline.handleChunkSectionAddition → VoxelNeighborhoodState.getState
 *   → LevelAccelerator.getBlockState → getChunk → grabChunkFast
 *   → Level.getChunk(FULL, create=true) → ServerChunkCache.getChunk
 *   → MainThreadExecutor.managedBlock → LockSupport.parkNanos — server thread freezes.
 *
 *   When Sable's physics ticket manager brings a new chunk-section into the simulation it reads
 *   the section's neighbour block states through {@link LevelAccelerator}, which performs a
 *   BLOCKING full chunk load. If that neighbour lies in a not-yet-loaded chunk, the main thread
 *   parks for the whole 10-13 s generation, tripping the Watchdog, then repeats for the next
 *   section. This is a DIFFERENT Sable path from {@link SablePhysicsWorldgenGuardMixin}
 *   (which only covers the world-gen re-entrant handleBlockChange), so that guard never fires
 *   here — and Sable bypasses vanilla {@code Level.getBlockState}, so
 *   {@link LevelGetBlockStateChunkGuardMixin} never fires either.
 *
 * Fix: at HEAD of the {@link LevelAccelerator} read methods, delegate to the shared
 * {@link MainThreadChunkLoad#shouldServeUnloaded} policy on the underlying ServerLevel.
 *   - Off the main thread, or when the chunk cannot be brought to FULL within a small budget,
 *     serve AIR / EMPTY fluid — the physics simulation treats the far neighbour as empty instead
 *     of parking the server. Physics sub-levels sit next to online players, so only distant edge
 *     reads hit unloaded chunks; treating those as air is safe.
 *   - On the main thread a generated-but-unloaded chunk is loaded within budget and the real
 *     read proceeds, so nearby physics stays exact.
 *
 * remap = false: LevelAccelerator is a third-party (Sable) class; its members are not remapped.
 * The Minecraft method/field descriptors resolve to official (Mojang-mapped) names at runtime.
 */
@Mixin(value = LevelAccelerator.class, remap = false)
public abstract class SableLevelAcceleratorChunkGuardMixin {

    @Shadow @Final private Level level;

    @Inject(
        method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void voidrp_guardSableGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (MainThreadChunkLoad.shouldServeUnloaded(serverLevel, cx, cz)) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] Sable getBlockState guard — chunk [{},{}] not loaded " +
                    "at block pos {},{},{} — returning AIR to prevent main-thread physics deadlock{}",
                    cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }

    @Inject(
        method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void voidrp_guardSableGetFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (MainThreadChunkLoad.shouldServeUnloaded(serverLevel, cx, cz)) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] Sable getFluidState guard — chunk [{},{}] not loaded " +
                    "at block pos {},{},{} — returning EMPTY to prevent main-thread physics deadlock{}",
                    cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }
}
