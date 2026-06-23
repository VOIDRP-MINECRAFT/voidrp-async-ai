package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ConfigCache;

/**
 * Throttles natural mob spawning for chunks far from any player.
 *
 * By default Minecraft calls spawnForChunk() every tick for every loaded chunk.
 * For chunks where all players are far away we can safely skip most spawn
 * attempts — mobs will still spawn eventually, just less frantically, and
 * no player is close enough to notice the difference.
 *
 * Skip schedule (nearest player to chunk center):
 *   ≤ VFAR_DIST     : no skip (normal spawn rate)
 *   VFAR→HIBERNATE  : attempt 1 in 4 ticks
 *   > HIBERNATE_DIST : attempt 1 in 8 ticks
 *
 * Different chunks skip on different ticks (via chunk-coordinate hash offset)
 * so the server doesn't burst-spawn everything on the one allowed tick.
 *
 * Uses require = 0 to degrade gracefully if the method signature changes
 * across MC patch versions.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnMixin {

    @Inject(
        method = "spawnForChunk",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void voidrp_throttleSpawnForChunk(
            ServerLevel level,
            LevelChunk chunk,
            NaturalSpawner.SpawnState spawnState,
            boolean spawnFriendlies,
            boolean spawnEnemies,
            boolean spawningOtherMobs,
            CallbackInfo ci) {
        if (!ConfigCache.SPAWN_THROTTLE_ENABLED) return;

        var chunkPos = chunk.getPos();
        double minDistSq = nearestPlayerDistSq(level,
            chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ());

        double vfar      = ConfigCache.THROTTLE_VFAR_DIST;
        double hibernate = ConfigCache.HIBERNATE_DIST;

        int skipMod;
        if      (minDistSq > hibernate * hibernate) skipMod = 8;
        else if (minDistSq > vfar     * vfar)      skipMod = 4;
        else return; // close enough — always spawn normally

        // Stagger chunks so the allowed tick is different per chunk
        long gameTime  = level.getGameTime();
        int  chunkHash = Math.abs(chunkPos.x * 31 + chunkPos.z);
        if ((gameTime + chunkHash) % skipMod != 0) {
            ci.cancel();
        }
    }

    private static double nearestPlayerDistSq(ServerLevel level, int blockX, int blockZ) {
        var players = level.players();
        if (players.isEmpty()) return Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (var player : players) {
            double dx = player.getX() - blockX;
            double dz = player.getZ() - blockZ;
            double d = dx * dx + dz * dz;
            if (d < min) min = d;
        }
        return min;
    }
}
