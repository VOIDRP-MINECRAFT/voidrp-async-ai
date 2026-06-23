package ru.voidrp.asyncai.mixin;

import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Rate-limits EndDragonFight.findOrCreateDragon() to prevent spam from
 * YungsBetterEndIsland when its doInitialDragonSpawn() cannot place summoning crystals.
 * Without this guard the method fires every 5 ticks, flooding the log indefinitely.
 * A 100-tick (5 s) cooldown is imperceptible during normal play but eliminates the spam.
 */
@Mixin(EndDragonFight.class)
public class DragonFightSpamGuardMixin {

    @Unique
    private static final int COOLDOWN_TICKS = 100;

    @Unique
    private int voidrp$dragonInitCooldown = 0;

    @Inject(method = "findOrCreateDragon", at = @At("HEAD"), cancellable = true)
    private void voidrp$throttleDragonInit(CallbackInfo ci) {
        if (voidrp$dragonInitCooldown > 0) {
            voidrp$dragonInitCooldown--;
            ci.cancel();
        } else {
            voidrp$dragonInitCooldown = COOLDOWN_TICKS;
            VoidRpAsyncAI.LOGGER.debug("[VoidRP] DragonFightSpamGuard: allowing findOrCreateDragon, next in {} ticks", COOLDOWN_TICKS);
        }
    }
}
