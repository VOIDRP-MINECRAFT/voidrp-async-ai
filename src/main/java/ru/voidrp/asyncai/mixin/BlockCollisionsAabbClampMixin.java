package ru.voidrp.asyncai.mixin;

import net.minecraft.world.level.BlockCollisions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Clamps the {@link net.minecraft.core.Cursor3D} bounds of a {@link BlockCollisions}
 * scan when the source AABB is pathologically large.
 *
 * Root cause (Watchdog HUNG_TICK, 2026-07-26, ~55 s tick):
 *   A corrupt / inflated entity bounding box (~1040 blocks wide in X, sweeping in Z —
 *   the classic "sable dup" AABB inflation) reached the collision path. BlockCollisions'
 *   constructor builds a Cursor3D spanning the ENTIRE box, so computeNext() then steps
 *   through tens of millions of positions.
 *
 * {@link BlockCollisionsChunkGuardMixin} already prevents the hard deadlock (it returns
 * null for not-yet-loaded chunks instead of blocking on chunk generation), but it cannot
 * stop the iteration itself: the cursor still walks every block position in the giant box,
 * which is what burned the ~55 s tick (millions of getChunk() calls, all skipped).
 *
 * Fix: intercept the {@code new Cursor3D(x0,y0,z0,x1,y1,z1)} call inside the constructor.
 * No legitimate collision query is wider than a few dozen blocks (largest vanilla/modded
 * entity AABB + movement is well under 100). If any horizontal span exceeds
 * {@link #MAX_SPAN} we clamp the cursor bounds symmetrically around the box centre,
 * bounding the iteration to a sane window. This never affects real collisions (their
 * boxes are tiny); it only defuses corrupt boxes, which would otherwise freeze the
 * server. In the incident every scanned chunk was unloaded anyway, so the clamped result
 * is identical (empty) — just produced in microseconds instead of seconds.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsAabbClampMixin {

    /** Max half-window (blocks) kept on each side of the box centre when clamping. */
    private static final int MAX_HALF = 256;
    /** Horizontal span (blocks) above which a collision AABB is treated as corrupt. */
    private static final long MAX_SPAN = 512L;

    private static final long LOG_INTERVAL_NS = 5_000_000_000L;
    private static final AtomicLong LAST_LOG_NS = new AtomicLong(0L);

    @ModifyArgs(
        method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Cursor3D;<init>(IIIIII)V"),
        require = 0
    )
    private void voidrp_clampCursorBounds(Args args) {
        int x0 = args.get(0);
        int y0 = args.get(1);
        int z0 = args.get(2);
        int x1 = args.get(3);
        int y1 = args.get(4);
        int z1 = args.get(5);

        long spanX = (long) x1 - x0;
        long spanZ = (long) z1 - z0;

        if (spanX <= MAX_SPAN && spanZ <= MAX_SPAN) {
            return; // normal-sized collision box — leave untouched
        }

        // Corrupt / inflated AABB: clamp horizontal bounds around the box centre.
        int cx = (int) (((long) x0 + x1) >> 1);
        int cz = (int) (((long) z0 + z1) >> 1);
        int nx0 = Math.max(x0, cx - MAX_HALF);
        int nx1 = Math.min(x1, cx + MAX_HALF);
        int nz0 = Math.max(z0, cz - MAX_HALF);
        int nz1 = Math.min(z1, cz + MAX_HALF);

        args.set(0, nx0);
        args.set(3, nx1);
        args.set(2, nz0);
        args.set(5, nz1);

        long now = System.nanoTime();
        long last = LAST_LOG_NS.get();
        if (now - last >= LOG_INTERVAL_NS && LAST_LOG_NS.compareAndSet(last, now)) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] BlockCollisions AABB clamp — pathological collision box spanX={} spanZ={} " +
                "(x[{}..{}] z[{}..{}]) clamped to x[{}..{}] z[{}..{}] around centre [{},{}] to prevent main-thread freeze",
                spanX, spanZ, x0, x1, z0, z1, nx0, nx1, nz0, nz1, cx, cz);
        }
    }
}
