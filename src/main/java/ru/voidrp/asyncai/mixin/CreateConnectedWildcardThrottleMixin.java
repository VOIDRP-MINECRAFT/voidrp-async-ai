package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Throttles Create Connected's wildcard redstone link network scan.
 *
 * Root cause (spark profile 2026-06-02):
 *   LinkWildcardNetworkHandler.updateNetworkOf() iterates ALL redstone link
 *   blocks in the level to find wildcard-matching pairs. With many link blocks
 *   this is O(N²) per signal change. Redstone clocks or frequent updates can
 *   trigger dozens of full scans per second.
 *
 * Fix: Allow at most one full wildcard scan per 500 ms globally.
 *   Intermediate updates are skipped — the next allowed scan picks up
 *   the current state.
 *
 * Implementation note: @Inject parameters must match the target method's
 *   exact descriptor. Since LinkWildcardNetworkHandler types are only available
 *   at runtime (create_connected compileOnly), we omit all method parameters
 *   and use wall-clock time for throttling instead of per-level game ticks.
 */
@Mixin(targets = "com.hlysine.create_connected.content.redstonelinkwildcard.LinkWildcardNetworkHandler", remap = false)
public abstract class CreateConnectedWildcardThrottleMixin {

    private static final long COOLDOWN_MS = 500L; // ~10 game ticks at 20 TPS

    private static volatile long lastUpdateMs = 0L;

    @Inject(
        method = "updateNetworkOf",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void voidrp_throttleWildcardScan(CallbackInfoReturnable<Boolean> cir) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < COOLDOWN_MS) {
            cir.setReturnValue(false);
            return;
        }
        lastUpdateMs = now;
    }
}
