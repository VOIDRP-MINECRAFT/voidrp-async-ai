package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.EntityThrottle;

/**
 * Entity hibernation: completely skip Mob.aiStep() for entities beyond
 * {@link ru.voidrp.asyncai.AiConfig#HIBERNATE_DIST} blocks from any player.
 *
 * Physics (gravity, knockback, fluid buoyancy) still apply because those
 * run in LivingEntity.tick() BEFORE aiStep() is called.
 * Only AI — goals, navigation, targeting, brain — is suspended.
 *
 * Mob.setNoAi() is intentionally NOT used: it persists to NBT and may
 * interfere with other mods. We simply skip the method call.
 */
@Mixin(Mob.class)
public abstract class EntityHibernateMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void hibernateAiStep(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (!self.level().isClientSide() && EntityThrottle.shouldHibernate(self)) {
            ci.cancel();
        }
    }
}
