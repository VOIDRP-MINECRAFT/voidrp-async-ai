package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttles Mekanism RadiationManager.tickServerWorld() to every 4 game ticks.
 *
 * Root cause (spark profile 2026-06-02):
 *   RadiationManager.tickServerWorld() runs every tick for every loaded
 *   dimension, updating player radiation exposure, handling meltdown spread,
 *   and decaying radioactive sources. With multiple dimensions this fires
 *   6-8 times per server tick.
 *
 * Fix: Process only once every 4 ticks per level (200 ms). Radiation changes
 * on a timescale of minutes to hours in-game, so 200 ms granularity has zero
 * visible gameplay impact. Meltdown spread and player exposure accuracy are
 * unaffected because they are inherently slow processes.
 *
 * Uses per-level game time to avoid drift between dimensions.
 */
@Mixin(targets = "mekanism.common.lib.radiation.RadiationManager", remap = false)
public abstract class MekanismRadiationThrottleMixin {

    private static final int RADIATION_TICK_EVERY = 4;

    @Inject(
        method = "tickServerWorld",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void voidrp_throttleRadiationTick(ServerLevel level, CallbackInfo ci) {
        if ((level.getGameTime() % RADIATION_TICK_EVERY) != 0L) {
            ci.cancel();
        }
    }
}
