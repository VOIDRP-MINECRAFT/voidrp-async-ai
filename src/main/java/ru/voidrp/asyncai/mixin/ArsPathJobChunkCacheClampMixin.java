package ru.voidrp.asyncai.mixin;

import com.hollingsworth.arsnouveau.common.entity.pathfinding.ChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Prevents an ars_nouveau (minecolonies-derived) path job from OOM-killing the server.
 *
 * === Observed CRASH / OOM 2026-07-29 19:19:59 ===
 * {@code java.lang.OutOfMemoryError: Java heap space} thrown by an entity tick:
 * <pre>
 *   ChunkCache.&lt;init&gt;(ChunkCache.java:58)
 *   AbstractPathJob.&lt;init&gt;(AbstractPathJob.java:151/129)
 *   PathJobMoveAwayFromLocation.&lt;init&gt;
 *   MinecoloniesAdvancedPathNavigate.moveAwayFromXYZ
 *   PathingStuckHandler.tryUnstuck -&gt; checkStuck
 *   MinecoloniesAdvancedPathNavigate.tick -&gt; Mob.serverAiStep
 *   Starbuncle.tick   (world -11733,64,-22083)
 * </pre>
 * The offending Starbuncle sat inside a shtreimel/sable sub-level (plot 10000,10028 at
 * exactly that block pos). Sable sub-levels carry a synthetic coordinate space, so when the
 * "stuck" handler computes a {@code moveAwayFromXYZ} target the resulting {@code end} pos
 * lands astronomically far from {@code start}. {@link net.minecraft.core.BlockPos start} and
 * {@code end} are ~10^5+ blocks apart, so {@code AbstractPathJob}'s ChunkCache tries to
 * allocate a {@code LevelChunk[spanX/16][spanZ/16]} 2-D array of billions of references
 * ({@code multianewarray} at ChunkCache.java:58) → heap exhausted. guardEntityTick caught
 * the throw and removed the entity, but the allocation attempt still stalled the tick and
 * tripped the Watchdog (server "has not responded for 11 seconds").
 *
 * Fix: redirect the {@code new ChunkCache(world, from, to, range, dimType)} construction in
 * {@code AbstractPathJob.<init>} and clamp the {@code to} (max) corner so the box never spans
 * more than {@link #VOIDRP_MAX_SPAN} blocks per axis beyond the {@code from} (min) corner.
 * The corners are already {@code min/max(start,end) ± range/2}, so {@code from <= to} always;
 * clamping the upper corner bounds the ChunkCache array to a few thousand references. AI
 * navigation for these entities only ever operates over a short range (maxRange is tens of
 * blocks), so a legitimate path can never exceed the clamp — only the degenerate
 * far-away-target case does, and clamping it simply yields a bounded local search instead of
 * a 14 GB allocation.
 *
 * remap=false: mod class on plain names; the referenced MC types (BlockPos/Level/
 * DimensionType) use official Mojang mappings on NeoForge 1.21.1, so no remap is needed.
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.common.entity.pathfinding.pathjobs.AbstractPathJob", remap = false)
public abstract class ArsPathJobChunkCacheClampMixin {

    /** Max block span per axis between the ChunkCache corners (well beyond any real AI path). */
    @Unique
    private static final int VOIDRP_MAX_SPAN = 1024;

    @Unique
    private static volatile long voidrp$lastClampLog = 0L;

    @Redirect(
        method = "<init>",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/level/dimension/DimensionType;)Lcom/hollingsworth/arsnouveau/common/entity/pathfinding/ChunkCache;"
        ),
        remap = false
    )
    private ChunkCache voidrp$clampChunkCache(
        Level world, BlockPos from, BlockPos to, int range, DimensionType dimType) {
        long spanX = (long) to.getX() - from.getX();
        long spanZ = (long) to.getZ() - from.getZ();
        if (spanX > VOIDRP_MAX_SPAN || spanZ > VOIDRP_MAX_SPAN) {
            int clampedX = spanX > VOIDRP_MAX_SPAN ? from.getX() + VOIDRP_MAX_SPAN : to.getX();
            int clampedZ = spanZ > VOIDRP_MAX_SPAN ? from.getZ() + VOIDRP_MAX_SPAN : to.getZ();
            long now = System.currentTimeMillis();
            if (now - voidrp$lastClampLog > 5000L) {
                voidrp$lastClampLog = now;
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] ars_nouveau path-job ChunkCache guard — clamped a {}x{}-block "
                    + "pathfinding box (from {} to {}) to <={} blocks/axis to prevent heap-exhausting "
                    + "allocation (sable/shtreimel sub-level far-target)",
                    spanX, spanZ, from, to, VOIDRP_MAX_SPAN);
            }
            to = new BlockPos(clampedX, to.getY(), clampedZ);
        }
        return new ChunkCache(world, from, to, range, dimType);
    }
}
