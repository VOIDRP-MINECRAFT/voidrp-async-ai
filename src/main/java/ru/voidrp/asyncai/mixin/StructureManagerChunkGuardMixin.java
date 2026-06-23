package ru.voidrp.asyncai.mixin;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Guards StructureManager structure lookups against crash during chunk decoration.
 *
 * Root cause #1 (2026-05-26 12:37): ValkyrieQueen.finalizeSpawn() → getStructureAt
 * Root cause #2 (2026-05-27 12:00): wetland_whimsy$WitchSetPersistance + FriendsAndFoes
 *   IllusionerEntity.finalizeSpawn() → Raider.finalizeSpawn
 *   → getStructureWithPieceAt(BlockPos,Structure) ← 2-arg, guarded below
 *   → startsForStructure(SectionPos,Structure) → Level.getChunk → IllegalStateException
 *
 * Root cause #3 (2026-05-27 19:01): Cat.finalizeSpawn() on Worker-Main chunk gen thread
 *   → getStructureWithPieceAt(BlockPos,TagKey)
 *   → getStructureWithPieceAt(BlockPos,Predicate)   ← different overload, NOT guarded before
 *   → startsForStructure(ChunkPos,Predicate) → ServerLevel.getChunk → ServerChunkCache.getChunk
 *   → our ServerChunkCacheGetChunkGuardMixin returns null
 *   → Level.getChunk line 400 throws on null → crash
 *
 * Root cause #4 (2026-06-02 17:19): NaturalSpawner.spawnForChunk (main thread tick)
 *   → NaturalSpawner.mobsAt → ChunkGenerator.getMobsAt
 *   → StructureManager.fillStartsForStructure → LevelReader.getChunk(x,z)
 *   → ServerChunkCache.getChunk → MainThreadExecutor.managedBlock → LockSupport.parkNanos
 *   Main thread parks indefinitely waiting for an unloaded structure chunk; Watchdog fires.
 *
 * Implementation note on @Shadow removal:
 *   @Shadow private LevelReader level was replaced with reflection-based field lookup.
 *   Reason: the Mixin annotation processor was not generating a refmap (confirmed by jar
 *   inspection — mixins.voidrp_async_ai.refmap.json absent). Without a refmap, @Shadow on
 *   vanilla (obfuscated) fields fails with "No refMap loaded" at class transformation time,
 *   causing MixinTransformerError and crashing the server. Reflection finds the first
 *   LevelReader-typed field by type rather than by name, which is robust to obfuscation.
 */
@Mixin(StructureManager.class)
public abstract class StructureManagerChunkGuardMixin {

    // Reflection-based access to StructureManager.level (private LevelReader).
    // Found by type (not by name) to survive obfuscation and NeoForge remapping without a refmap.
    private static final Field LEVEL_FIELD;
    static {
        Field found = null;
        for (Field f : StructureManager.class.getDeclaredFields()) {
            if (LevelReader.class.isAssignableFrom(f.getType())) {
                try { f.setAccessible(true); found = f; } catch (Exception ignored) {}
                break;
            }
        }
        LEVEL_FIELD = found;
    }

    private static final ConcurrentHashMap<Long, Long> WARN_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final long WARN_COOLDOWN_MS = 10_000L;

    private static boolean shouldWarn(int x, int z) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        long now = System.currentTimeMillis();
        Long last = WARN_TIMESTAMPS.put(key, now);
        return last == null || now - last >= WARN_COOLDOWN_MS;
    }

    @Redirect(
        method = "getStructureAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"
        ),
        require = 0
    )
    private List<StructureStart> voidrp_safeStartsForStructureAt(
            StructureManager instance, SectionPos sectionPos, Structure structure) {
        try {
            return instance.startsForStructure(sectionPos, structure);
        } catch (Exception e) {
            if (shouldWarn(sectionPos.x(), sectionPos.z())) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] StructureManager chunk guard (getStructureAt) — lookup failed at" +
                    " section [{},{}] during chunk decoration ({}), skipping",
                    sectionPos.x(), sectionPos.z(), e.getMessage());
            }
            return List.of();
        }
    }

    @Redirect(
        method = "getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"
        ),
        require = 0
    )
    private List<StructureStart> voidrp_safeStartsForStructureWithPieceAt(
            StructureManager instance, SectionPos sectionPos, Structure structure) {
        try {
            return instance.startsForStructure(sectionPos, structure);
        } catch (Exception e) {
            if (shouldWarn(sectionPos.x(), sectionPos.z())) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] StructureManager chunk guard (getStructureWithPieceAt) — lookup failed at" +
                    " section [{},{}] during chunk decoration ({}), skipping",
                    sectionPos.x(), sectionPos.z(), e.getMessage());
            }
            return List.of();
        }
    }

    @Redirect(
        method = "getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Predicate;)Ljava/util/List;"
        ),
        require = 0
    )
    private List<StructureStart> voidrp_safeStartsForStructureWithPieceAtPredicate(
            StructureManager instance, ChunkPos chunkPos, Predicate<Structure> predicate) {
        try {
            return instance.startsForStructure(chunkPos, predicate);
        } catch (Exception e) {
            if (shouldWarn(chunkPos.x, chunkPos.z)) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] StructureManager chunk guard (getStructureWithPieceAt/Tag) — lookup failed at" +
                    " chunk [{},{}] during decoration ({}), skipping",
                    chunkPos.x, chunkPos.z, e.getMessage());
            }
            return List.of();
        }
    }

    // Root cause #4: reimplement fillStartsForStructure using getChunkNow (non-blocking).
    // Chunks not yet loaded are skipped — structure-mob spawning is simply deferred until the
    // chunk loads, which is semantically safe.
    @Inject(
        method = "fillStartsForStructure",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_safePopulateFillStartsForStructure(
            Structure structure, LongSet structureRefs, Consumer<StructureStart> consumer, CallbackInfo ci) {
        if (LEVEL_FIELD == null) return; // reflection setup failed — fall through to vanilla

        LevelReader levelReader;
        try {
            levelReader = (LevelReader) LEVEL_FIELD.get(this);
        } catch (Exception e) {
            return;
        }

        if (!(levelReader instanceof ServerLevel serverLevel)) return;

        ServerChunkCache cache = serverLevel.getChunkSource();

        for (LongIterator it = structureRefs.iterator(); it.hasNext(); ) {
            long chunkRef = it.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunkRef);
            LevelChunk levelChunk = cache.getChunkNow(chunkPos.x, chunkPos.z);
            if (levelChunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(chunkPos.x, chunkPos.z);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] StructureManager.fillStartsForStructure guard — chunk [{},{}] not immediately available" +
                        " — skipping to prevent main-thread chunk-load deadlock{}",
                        chunkPos.x, chunkPos.z, suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                }
                continue;
            }
            StructureStart start = levelChunk.getStartForStructure(structure);
            if (start != null && start.isValid()) {
                consumer.accept(start);
            }
        }

        ci.cancel();
    }
}
