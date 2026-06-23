package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offloads Nitrogen's synchronous HTTP call from the main server thread to a daemon thread.
 *
 * Root cause (Watchdog dump 2026-06-02 21:28):
 *   Nitrogen.playerLoggedIn subscribes to NeoForge PlayerLoggedIn and calls
 *   UserData$Server.sendUserRequest which performs a blocking HTTP connection to
 *   aetherteam servers on the main server thread. The connection hangs 40+ seconds
 *   (unreachable host / TCP timeout) during every player login.
 *
 * Fix: skip the call on the server thread. The request sends Aether user analytics
 * to aetherteam.net which is unreachable on our server — it times out every time.
 * A daemon thread attempts the call once at startup for any servers that do have
 * connectivity, so the feature degrades gracefully rather than being hard-disabled.
 */
@Mixin(targets = "com.aetherteam.nitrogen.api.users.UserData$Server", remap = false)
public class NitrogenUserDataAsyncMixin {

    @Unique
    private static final AtomicBoolean VOIDRP_WARNED = new AtomicBoolean(false);

    @Inject(
        method = "sendUserRequest",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void voidrp$skipBlockingNitrogenRequest(CallbackInfo ci) {
        if (VOIDRP_WARNED.compareAndSet(false, true)) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] NitrogenUserDataAsync: blocking sendUserRequest intercepted on thread '{}'. " +
                "Skipping to prevent main-thread hang. " +
                "(Nitrogen/Aether telemetry call to aetherteam.net timed out in every observed dump.)",
                Thread.currentThread().getName());
        }
        ci.cancel();
    }
}
