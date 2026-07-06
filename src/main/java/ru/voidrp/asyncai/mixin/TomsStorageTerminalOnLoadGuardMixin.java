package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Tom's Storage StorageTerminalBlockEntity.onLoad() against a crash caused by our own
 * LevelGetBlockStateChunkGuardMixin returning AIR during a chunk-promotion race.
 *
 * Root cause (Watchdog dump 2026-06-24 13:35, HUNG_SHUTDOWN):
 *   StorageTerminalBlockEntity.onLoad() reads its OWN block:
 *     BlockState s = level.getBlockState(worldPosition);
 *     Direction d = s.getValue(AbstractStorageTerminalBlock.FACING);   // <-- line 67
 *   During heavy chunk churn (player exploring far at -11xxx,-12xxx, hundreds of
 *   "chunk not loaded" guard warnings), the terminal's own chunk was momentarily not
 *   promoted in getChunkSource().getChunkNow(), so LevelGetBlockStateChunkGuardMixin
 *   returned Blocks.AIR. Tom's Storage then called air.getValue(FACING):
 *     IllegalArgumentException: Cannot get property facing ... does not exist in Block{minecraft:air}
 *   This threw inside ServerLevel.tick() → tickBlockEntities → crashed the tick →
 *   triggered server shutdown, which then hung (the observed HUNG_SHUTDOWN).
 *
 * Fix: redirect the getBlockState() call inside onLoad(). If the level lookup yields AIR
 * (the transient guard fallback), substitute the BlockEntity's own cached blockState, which
 * always carries the real terminal block (with FACING / TERMINAL_POS). The network wiring then
 * proceeds normally instead of crashing. No external Tom's Storage classes are referenced, so
 * this compiles without a dependency on the mod.
 */
@Mixin(targets = "com.tom.storagemod.block.entity.StorageTerminalBlockEntity", remap = false)
public abstract class TomsStorageTerminalOnLoadGuardMixin {

    @Redirect(
        method = "onLoad",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0,
        remap = true
    )
    private BlockState voidrp_safeTerminalState(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            BlockState cached = ((BlockEntity) (Object) this).getBlockState();
            if (!cached.isAir()) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] TomsStorage onLoad guard — getBlockState returned AIR at {},{},{} " +
                    "(chunk-promotion race); using cached BlockEntity state to prevent tick crash",
                    pos.getX(), pos.getY(), pos.getZ());
                return cached;
            }
        }
        return state;
    }
}
