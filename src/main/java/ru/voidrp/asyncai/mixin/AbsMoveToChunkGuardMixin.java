package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Entity.absMoveTo() against main-thread deadlock from NeoForge's "ensure chunk loaded" patch.
 *
 * Root cause (Watchdog HUNG_TICK dump 2026-07-21 16:46:59):
 *   ServerboundMovePlayerPacket$Pos.handle → ServerGamePacketListenerImpl.handleMovePlayer
 *   → Entity.absMoveTo(x,y,z,yRot,xRot) [Entity.java:1820]
 *   → Entity.absMoveTo(x,y,z)           [Entity.java:1839]
 *   → this.level().getChunk(cx, cz)   NeoForge patch: "ensure target chunk is loaded"
 *   → Level.getChunk(int,int) → ServerChunkCache.getChunk(FULL, require=true)
 *   → managedBlock() → parkNanos() → BLOCKS the main thread.
 *
 * Same fire-and-forget preload side-effect as {@link SetPosRawChunkGuardMixin} but at the
 * absMoveTo call site. Trigger: a player moving into ungenerated far terrain (chunk ~[-53,-833],
 * block -833,-119,-13397, ~13 km from spawn). Each movement packet forced a synchronous
 * chunk-generation of that region; with heavy Sable worldgen fluid-settling there, the block-load
 * exceeded 10 s and the Watchdog fired HUNG_TICK.
 *
 * The return value of getChunk() is DISCARDED in absMoveTo (it is purely a chunk-preload
 * side-effect — the player's position was already set via setPos before this call), so returning
 * null when the chunk is not immediately resident is completely safe: no NPE, no broken tracking.
 *
 * Fix: redirect Level.getChunk(int,int) inside absMoveTo to getChunkNow() (non-blocking).
 * If the chunk is not immediately available, skip the preload and return null. The chunk will be
 * loaded asynchronously by the normal player chunk-ticket system a moment later.
 */
@Mixin(Entity.class)
public abstract class AbsMoveToChunkGuardMixin {

    @Redirect(
        // Pin the exact overload: absMoveTo has two (DDDFF)/(DDD); only (DDD) contains the
        // Level.getChunk(II) preload. A name-only selector matched both overloads and the
        // redirect silently failed to bind (require=0) — the guard never fired despite the
        // target being present. Sibling single-overload guards (setPosRaw) worked fine.
        method = "absMoveTo(DDD)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
        ),
        require = 0
    )
    private LevelChunk voidrp_safeGetChunkInAbsMoveTo(Level level, int cx, int cz) {
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    if (suppressed > 0) {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] absMoveTo chunk guard — chunk [{},{}] not immediately available" +
                            " — skipping Forge chunk preload to prevent main-thread deadlock (+{} suppressed)",
                            cx, cz, suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] absMoveTo chunk guard — chunk [{},{}] not immediately available" +
                            " — skipping Forge chunk preload to prevent main-thread deadlock",
                            cx, cz);
                    }
                }
            }
            return chunk; // null is safe — return value is discarded in Entity.absMoveTo
        }
        return level.getChunk(cx, cz);
    }
}
