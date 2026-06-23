package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * SkinsRestorer's bStats (MetricsBase.submitData) is scheduled as a Bukkit sync task
 * via CraftScheduler and runs on the main server thread once per ~30 min.  It blocks
 * for up to 31+ seconds collecting reflection-based config diffs and doing an HTTP POST.
 *
 * Root cause (Watchdog dump 2026-06-04 15:32:00):
 *   MetricsBase.submitData → collectConfigDiff → Field.get() / Reflection.getCallerClass()
 *   → HTTP POST to bStats, all running on Server thread via CraftTask → CraftScheduler.
 *   Server hung 31 seconds; Watchdog fired at "has not responded for 31 seconds".
 *
 * Fix: redirect Runnable.run() inside CraftTask.run().  When the runnable class name
 * contains "bstats" and we're on the main thread, fire it on a daemon thread instead.
 * bStats is anonymous telemetry; skipping a tick is harmless.
 */
@Mixin(targets = "org.bukkit.craftbukkit.scheduler.CraftTask", remap = false)
public class BukkitBStatsAsyncMixin {

    @Redirect(
        method = "run",
        at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
        require = 0
    )
    private void voidrp_offloadBStatsToAsync(Runnable runnable) {
        String cn = runnable.getClass().getName();
        if ("Server thread".equals(Thread.currentThread().getName())
                && cn.contains("bstats")) {
            Thread t = new Thread(runnable, "voidrp-bstats-async");
            t.setDaemon(true);
            t.start();
            VoidRpAsyncAI.LOGGER.info(
                "[VoidRP] BukkitBStatsGuard — offloaded {} to background thread (prevents main-thread block)",
                cn);
        } else {
            runnable.run();
        }
    }
}
