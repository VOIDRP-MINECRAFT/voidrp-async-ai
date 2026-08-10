package ru.voidrp.asyncai.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.MainThreadChunkLoad;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards {@code LevelReader.getNoiseBiome(int,int,int)} against a main-thread deadlock when an
 * unloaded chunk is touched by an incidental biome lookup.
 *
 * Root cause (Watchdog dump 2026-07-26 07:25:58):
 *   A KubeJS script hooked on the entity-spawned event calls {@code LevelBlock.getBiomeId()}
 *   for a freshly spawned entity. The entity is a Ballistarian bracer (reliquified_ars_nouveau)
 *   projectile spawned ahead of a player flying through ungenerated far terrain
 *   (~ -19500,-22300 → chunk -1222,-1395). The vanilla default getNoiseBiome calls
 *   {@code Level.getChunk(cx,cz)} (FULL, true) → ServerChunkCache.getChunk →
 *   MainThreadExecutor.managedBlock → LockSupport.parkNanos — the server thread parks while the
 *   ungenerated chunk is generated, tripping the 10 s Watchdog every tick the player advances.
 *
 * The sibling guards (getBlockState → AIR, BlockCollisions → skip, Lodestone stream → empty,
 * StructureManager → skip) were already firing for this same player's movement; the biome path
 * was the one remaining unguarded synchronous chunk load in the entity-spawn call chain.
 *
 * Fix: when the biome-section chunk is not immediately available (see
 * {@link MainThreadChunkLoad#shouldServeUnloaded}), return {@code getUncachedNoiseBiome} — the
 * exact fallback vanilla's own getNoiseBiome uses when the chunk is null. It computes the biome
 * directly from the generator's biome source + climate sampler with NO chunk load, so the result
 * is correct and no main-thread park occurs. If the chunk can be brought to FULL within the small
 * bounded budget, vanilla proceeds against the real chunk.
 *
 * The {@code instanceof ServerLevel} guard keeps world generation (WorldGenRegion overrides
 * getNoiseBiome anyway) and the client untouched.
 */
@Mixin(LevelReader.class)
public interface LevelGetNoiseBiomeChunkGuardMixin {

    @Inject(
        method = "getNoiseBiome(III)Lnet/minecraft/core/Holder;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardGetNoiseBiome(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = QuartPos.toSection(x);
        int cz = QuartPos.toSection(z);
        if (MainThreadChunkLoad.shouldServeUnloaded(serverLevel, cx, cz)) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] getNoiseBiome guard — chunk [{},{}] not loaded (quart {},{},{}) — " +
                    "returning uncached noise biome to prevent main-thread chunk-load deadlock{}",
                    cx, cz, x, y, z,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(serverLevel.getUncachedNoiseBiome(x, y, z));
        }
    }
}
