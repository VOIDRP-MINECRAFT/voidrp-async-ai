package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards LeafcutterAntAIForageLeaves.isValidTarget() against main-thread deadlock.
 *
 * Root cause (Watchdog dumps 2026-05-28, 2026-06-03):
 *   EntityLeafcutterAnt.tick() → Mob.serverAiStep() → GoalSelector.tick()
 *   → LeafcutterAntAIForageLeaves.canUse() → findNearestBlock() → isValidTarget()
 *   → BlockGetter.getBlockState() → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos()
 *
 * The original fix (2026-05-28) targeted Level.getBlockState (INVOKEVIRTUAL) but
 * isValidTarget(LevelReader, BlockPos) calls getBlockState through the LevelReader
 * parameter, which generates INVOKEINTERFACE BlockGetter.getBlockState — so the
 * redirect silently did not fire (require = 0). Also remap = true on @Redirect
 * caused isValidTarget (third-party method) to be looked up in Minecraft mappings
 * and silently skipped.
 *
 * Fix (2026-06-03): target BlockGetter.getBlockState (the interface dispatch form),
 * set remap = false so the injection point is not remapped through MC mappings.
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.ai.LeafcutterAntAIForageLeaves", remap = false)
public abstract class LeafcutterAntForageChunkGuardMixin {

    @Redirect(
        method = "isValidTarget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0,
        remap = false
    )
    private BlockState voidrp_safeGetBlockStateInForage(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] LeafcutterAnt forage guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning AIR to prevent main-thread deadlock{}",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                        suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                }
                return Blocks.AIR.defaultBlockState();
            }
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
}
