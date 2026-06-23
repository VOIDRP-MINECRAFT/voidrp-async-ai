package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.AsyncPathManager;
import ru.voidrp.asyncai.PathCache;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Intercepts the expensive PathFinder.findPath() call inside PathNavigation.createPath().
 *
 * Priority order for distant mobs:
 *  1. PathCache hit  → return immediately (zero thread overhead)
 *  2. Async result ready from a previous tick → return it
 *  3. Otherwise → submit to worker pool; mob keeps its old path this tick
 *
 * Mobs within asyncPathMinDist of any player always compute synchronously so
 * their response feels immediate.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {

    @Shadow protected Mob mob;

    @Redirect(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(" +
                     "Lnet/minecraft/world/level/PathNavigationRegion;" +
                     "Lnet/minecraft/world/entity/Mob;" +
                     "Ljava/util/Set;FIF)" +
                     "Lnet/minecraft/world/level/pathfinder/Path;"
        ),
        require = 0
    )
    @Nullable
    private Path asyncFindPath(
        PathFinder pathFinder,
        PathNavigationRegion region,
        Mob mob,
        Set<BlockPos> targets,
        float maxRange,
        int accuracy,
        float multiplier
    ) {
        if (!AsyncPathManager.shouldAsync(mob)) {
            // Close to a player — compute synchronously
            return pathFinder.findPath(region, mob, targets, maxRange, accuracy, multiplier);
        }

        var id = mob.getUUID();

        // 1. Path cache hit (target within 3 blocks of cached target, still fresh)
        Path cached = PathCache.retrieve(id, targets);
        if (cached != null) return cached;

        // 2. Async result ready from a previous submission
        if (AsyncPathManager.hasResult(id)) return AsyncPathManager.pollResult(id);

        // 3. Submit to worker; return null this tick so mob keeps current path.
        //    Pass distSq so PathCache picks the right TTL for the result.
        double distSq = AsyncPathManager.nearestPlayerDistSq(mob);
        AsyncPathManager.submitAsync(id, pathFinder, region, mob, targets, maxRange, accuracy, multiplier, distSq);
        return null;
    }
}
