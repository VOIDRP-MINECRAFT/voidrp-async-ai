package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.EntityThrottle;

/**
 * DABS-style AI throttle: reduces how often the goal selector ticks for mobs
 * far from any player, without affecting physics or movement.
 *
 * Skip schedule (distance from nearest player):
 *  > 32 blocks  → run goals every 2 ticks
 *  > 64 blocks  → run goals every 4 ticks
 *  > 96 blocks  → run goals every 8 ticks
 */
@Mixin(Mob.class)
public abstract class MobAiMixin {

    /** Cached skip decision for this tick — shared by both goal and target selectors. */
    @Unique
    private boolean voidrp_skipAiThisTick = false;

    /**
     * Wraps the goalSelector.tick() call in Mob.aiStep() — ordinal 0 (goal selector).
     * Computes and caches the skip decision for the whole tick here.
     */
    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V",
            ordinal = 0
        ),
        require = 0
    )
    private void throttleGoalSelector(GoalSelector goalSelector) {
        Mob self = (Mob) (Object) this;
        voidrp_skipAiThisTick = EntityThrottle.shouldSkipGoals(self);
        if (!voidrp_skipAiThisTick) {
            goalSelector.tick();
        }
    }

    /**
     * Wraps the targetSelector.tick() call in Mob.aiStep() — ordinal 1 (target selector).
     * Reuses the skip decision from throttleGoalSelector to avoid double-counting.
     */
    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V",
            ordinal = 1
        ),
        require = 0
    )
    private void throttleTargetSelector(GoalSelector goalSelector) {
        if (!voidrp_skipAiThisTick) {
            goalSelector.tick();
        }
    }
}
