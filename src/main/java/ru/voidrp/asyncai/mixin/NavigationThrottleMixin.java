package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.EntityThrottle;

/**
 * Throttles PathNavigation.tick() for very distant mobs.
 * When navigation is skipped the mob simply doesn't follow its current path
 * that tick — safe since distant mobs are not visible to players.
 */
@Mixin(PathNavigation.class)
public abstract class NavigationThrottleMixin {

    @Shadow protected Mob mob;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void throttleNavTick(CallbackInfo ci) {
        if (EntityThrottle.shouldSkipNavigation(mob)) {
            ci.cancel();
        }
    }
}
