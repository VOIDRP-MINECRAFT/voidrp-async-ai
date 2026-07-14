package ru.voidrp.asyncai.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Throttles the periodic full autosave to reduce player time-out kicks on slow (HDD) disks.
 *
 * Problem (this server runs on a 5400rpm HDD): vanilla fires {@code MinecraftServer.autoSave()}
 * every AUTOSAVE_INTERVAL (6000 ticks = 5 min), which calls {@code saveEverything(true,…)} —
 * a synchronous full save (level.dat + all dirty region chunks + SavedData). On a saturated
 * spinning disk this freezes the main thread ~30-35 s, so online players stop receiving
 * keep-alives and get disconnected ("timed out"). Watchdog dumps show the thread parked in
 * {@code ServerLevel.save → NbtIo.writeCompressed}.
 *
 * Fix: only let {@code autoSave()} actually run once per {@link #MIN_INTERVAL_MS} (default
 * 15 min, override with {@code -Dvoidrp.autosave.minMinutes=<n>}). Skipped calls reschedule a
 * re-check ~1 min later. This does NOT touch the shutdown save ({@code saveAllChunks} in
 * stopServer) or a manual {@code /save-all}, so no data is lost beyond the widened autosave
 * window — a crash can lose at most the configured interval of progress (the server has a
 * Watchdog auto-restart). The real cure remains moving the world to an SSD.
 */
@Mixin(MinecraftServer.class)
public abstract class AutoSaveThrottleMixin {

    @Shadow
    private int ticksUntilAutosave;

    @Unique
    private static final long MIN_INTERVAL_MS = voidrp$resolveIntervalMs();

    @Unique
    private long voidrp$lastFullAutosaveMs = 0L;

    @Unique
    private boolean voidrp$loggedInterval = false;

    @Unique
    private static long voidrp$resolveIntervalMs() {
        long minutes = 15L;
        try {
            String prop = System.getProperty("voidrp.autosave.minMinutes");
            if (prop != null) {
                minutes = Long.parseLong(prop.trim());
            }
        } catch (Throwable ignored) {
            // keep default
        }
        return Math.max(0L, minutes) * 60_000L;
    }

    @Inject(method = "autoSave", at = @At("HEAD"), cancellable = true, require = 0)
    private void voidrp$throttleAutoSave(CallbackInfo ci) {
        if (MIN_INTERVAL_MS <= 0L) {
            return; // throttling disabled
        }
        long now = System.currentTimeMillis();
        if (!voidrp$loggedInterval) {
            voidrp$loggedInterval = true;
            VoidRpAsyncAI.LOGGER.info(
                "[VoidRP] Full autosave throttled to a minimum of {} min " +
                "(override with -Dvoidrp.autosave.minMinutes). Reduces HDD save-freeze kicks.",
                MIN_INTERVAL_MS / 60_000L);
        }
        if (voidrp$lastFullAutosaveMs != 0L && now - voidrp$lastFullAutosaveMs < MIN_INTERVAL_MS) {
            // Too soon — skip this full save and re-check in ~1 min instead of every tick.
            this.ticksUntilAutosave = 1200;
            ci.cancel();
            return;
        }
        // Allow it: vanilla will saveEverything() and reset ticksUntilAutosave itself.
        voidrp$lastFullAutosaveMs = now;
    }
}
