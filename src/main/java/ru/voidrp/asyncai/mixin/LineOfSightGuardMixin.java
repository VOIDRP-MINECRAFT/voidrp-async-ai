package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.LineOfSightCache;

/**
 * Two-layer optimisation for LivingEntity.hasLineOfSight():
 *
 * Layer 1 — Unloaded-chunk guard:
 *   If the *target* block position is not in a loaded chunk, return false
 *   immediately without starting a raycast that would force a synchronous
 *   chunk load.  This fixes 10–80 s main-thread freezes caused by tamed
 *   Ice&Fire dragons calling OwnerHurtTargetGoal.canUse() while their owner
 *   was hit by something in an unloaded area.
 *
 * Layer 2 — LOS result cache:
 *   Cache the raycast result for each (source, target) pair.  TTL scales with
 *   distance (2–20 ticks) so close-combat remains responsive while distant-mob
 *   checks are throttled.  At the end of a real computation the result is
 *   stored; the next N calls for the same pair return immediately.
 *
 * The @Unique flag voidrp_losFromCache prevents the RETURN inject from
 * re-caching a value that was already served from cache (avoids a pointless
 * HashMap write on every cache hit).
 */
@Mixin(LivingEntity.class)
public abstract class LineOfSightGuardMixin {

    /** True when the current hasLineOfSight() call was answered from cache. */
    @Unique
    private boolean voidrp_losFromCache = false;

    @Inject(
        method = "hasLineOfSight",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardAndCacheLOS(Entity target, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        voidrp_losFromCache = false;

        if (self.level().isClientSide()) return;

        // Layer 1: unloaded chunk guard
        if (!self.level().isLoaded(target.blockPosition())) {
            voidrp_losFromCache = true; // skip re-cache in RETURN inject
            cir.setReturnValue(false);
            return;
        }

        // Layer 2: cache lookup
        if (self.level() instanceof ServerLevel sl) {
            long gt = sl.getGameTime();
            double distSq = self.distanceToSqr(target);
            Boolean cached = LineOfSightCache.get(
                    self.getUUID(), target.getUUID(), gt, distSq);
            if (cached != null) {
                voidrp_losFromCache = true;
                cir.setReturnValue(cached);
            }
        }
    }

    @Inject(
        method = "hasLineOfSight",
        at = @At("RETURN"),
        require = 0
    )
    private void voidrp_storeLOSResult(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (voidrp_losFromCache) return; // already came from cache, nothing to store

        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel sl) {
            LineOfSightCache.put(
                    self.getUUID(), target.getUUID(),
                    cir.getReturnValue(), sl.getGameTime());
        }
    }
}
