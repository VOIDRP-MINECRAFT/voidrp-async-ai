package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tick time budget for Mekanism EnergyNetwork.tickEmit().
 *
 * Root cause (thread dump 2026-06-04 15:33):
 *   EnergyNetwork.tickEmit() iterates every energy acceptor in the network,
 *   calling getContainers()+insertEnergy() on each machine. A large cable
 *   network with thousands of acceptors blocked the main thread for 13+ s,
 *   triggering the Minecraft Watchdog.
 *
 * Fix: per-network instance tracking via @Unique fields. If tickEmit() took
 *   > EMIT_BUDGET_MS last time, skip this network for SKIP_TICKS_ON_OVERRUN
 *   game ticks, then resume. Energy is delayed at most SKIP_TICKS * 50 ms
 *   for the slow network — imperceptible at normal 20-TPS throughput rates.
 */
@Mixin(targets = "mekanism.common.content.network.EnergyNetwork", remap = false)
public abstract class MekanismEnergyNetworkThrottleMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("voidrp_async_ai");
    private static final long EMIT_BUDGET_MS = 50L;
    private static final int SKIP_TICKS_ON_OVERRUN = 2;

    @Unique
    private long voidrp_emitStartMs = 0L;
    @Unique
    private int voidrp_emitSkipTicks = 0;

    @Inject(
        method = "tickEmit",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void voidrp_guardTickEmit(CallbackInfo ci) {
        if (voidrp_emitSkipTicks > 0) {
            voidrp_emitSkipTicks--;
            ci.cancel();
            return;
        }
        voidrp_emitStartMs = System.currentTimeMillis();
    }

    @Inject(
        method = "tickEmit",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private void voidrp_measureTickEmit(CallbackInfo ci) {
        long elapsed = System.currentTimeMillis() - voidrp_emitStartMs;
        if (elapsed > EMIT_BUDGET_MS) {
            voidrp_emitSkipTicks = SKIP_TICKS_ON_OVERRUN;
            LOGGER.warn("[VoidRP] Mekanism EnergyNetwork.tickEmit() took {}ms (budget {}ms) — throttling for {} ticks",
                elapsed, EMIT_BUDGET_MS, SKIP_TICKS_ON_OVERRUN);
        }
    }
}
