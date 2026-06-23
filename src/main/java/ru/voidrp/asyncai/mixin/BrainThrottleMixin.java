package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ConfigCache;
import ru.voidrp.asyncai.EntityThrottle;

/**
 * Throttles Brain.tickSensors() for distant entities with memory/sensor brains.
 *
 * Vanilla villagers, piglins, hoglin, goats, and other brain-driven mobs
 * run their Sensor list every tick. NearestLivingEntitySensor, NearestBedSensor,
 * VillagerHostileSensor, etc. are expensive and don't need full-rate updates
 * for entities far from any player.
 *
 * Skip schedule (distance to nearest player):
 *   ≤ NEAR_DIST  : run every tick (unchanged)
 *   NEAR→FAR     : run every 4 ticks
 *   FAR→VFAR     : run every 8 ticks
 *   > VFAR       : run every 20 ticks
 * Multiplied by {@link ru.voidrp.asyncai.AdaptiveThrottle#getLoadFactor()} during lag.
 * Boss mobs are never throttled.
 *
 * Note: tickSensors() is private in Brain — Mixin operates at bytecode level
 * and does not respect Java access modifiers.
 */
@Mixin(Brain.class)
public abstract class BrainThrottleMixin {

    @Inject(
        method = "tickSensors",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_throttleSensors(ServerLevel level, LivingEntity owner, CallbackInfo ci) {
        if (!ConfigCache.BRAIN_THROTTLE_ENABLED) return;
        if (!(owner instanceof Mob mob)) return;
        if (mob.level().isClientSide()) return;
        if (EntityThrottle.isBoss(mob)) return;

        if (EntityThrottle.shouldSkipBrainSensors(mob)) {
            ci.cancel();
        }
    }
}
