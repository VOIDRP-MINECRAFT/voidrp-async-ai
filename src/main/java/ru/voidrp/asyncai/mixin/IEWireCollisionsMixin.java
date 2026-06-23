package ru.voidrp.asyncai.mixin;

import blusunrize.immersiveengineering.common.wires.WireCollisions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Three-layer optimization for ImmersiveEngineering wire entity collision.
 *
 * Layer 1 — Cached config (existing):
 *   Caches IEServerConfig.WIRES.enableWireDamage every 200 calls to avoid
 *   ConcurrentHashMap lookup overhead per entity per tick.
 *
 * Layer 2 — Same-entity same-tick dedup (new):
 *   An entity can intersect multiple wire-containing blocks per tick (AABB
 *   spans up to 8 block positions). We only need to process it once per tick.
 *   Uses a 45 ms wall-clock window (≈ 1 tick at 20 TPS) to deduplicate.
 *
 * Layer 3 — Stationary entity skip (new):
 *   Entities that have not moved between ticks produce identical wire-contact
 *   results. Skip if entity position (rounded to 0.1 block) matches last tick.
 */
@Mixin(value = WireCollisions.class, remap = false)
public abstract class IEWireCollisionsMixin {

    // Layer 1
    private static final int  CACHE_REFRESH_EVERY   = 200;
    private static volatile boolean cachedEnableWireDamage = true;
    private static int cacheCounter = CACHE_REFRESH_EVERY - 1;

    // Layer 2: entityId → last processing timestamp ms
    private static final Map<Integer, Long> lastEntityTimeMs = new HashMap<>();

    // Layer 3: entityId → [quantised x*10, y*10, z*10]
    private static final Map<Integer, long[]> lastEntityPos = new HashMap<>();

    // Periodic cleanup counter
    private static int cleanupCounter = 0;

    @Inject(
        method = "handleEntityCollision",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void voidrp_deduplicateEntityCollision(
            BlockPos pos, Entity entity, CallbackInfo ci) {

        int id = entity.getId();
        long nowMs = System.currentTimeMillis();

        // Layer 2: already handled this entity this tick
        Long prevMs = lastEntityTimeMs.get(id);
        if (prevMs != null && nowMs - prevMs < 45L) {
            ci.cancel();
            return;
        }
        lastEntityTimeMs.put(id, nowMs);

        // Layer 3: entity hasn't moved
        long qx = (long)(entity.getX() * 10);
        long qy = (long)(entity.getY() * 10);
        long qz = (long)(entity.getZ() * 10);
        long[] lastPos = lastEntityPos.get(id);
        if (lastPos != null && lastPos[0] == qx && lastPos[1] == qy && lastPos[2] == qz) {
            ci.cancel();
            return;
        }
        if (lastPos == null) {
            lastEntityPos.put(id, new long[]{qx, qy, qz});
        } else {
            lastPos[0] = qx;
            lastPos[1] = qy;
            lastPos[2] = qz;
        }

        // Periodic map cleanup (~every 200 entity-collision events)
        if (++cleanupCounter >= 200) {
            cleanupCounter = 0;
            // Remove entries older than 5 seconds to prevent unbounded growth
            long cutoff = nowMs - 5000L;
            lastEntityTimeMs.values().removeIf(t -> t < cutoff);
        }
    }

    @Redirect(
        method = "handleEntityCollision",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/ModConfigSpec$BooleanValue;get()Ljava/lang/Object;",
            remap = false
        ),
        require = 0,
        remap = false
    )
    private static Object voidrp_cachedEnableWireDamage(ModConfigSpec.BooleanValue instance) {
        if (++cacheCounter >= CACHE_REFRESH_EVERY) {
            cacheCounter = 0;
            cachedEnableWireDamage = instance.get();
        }
        return cachedEnableWireDamage;
    }
}
