package ru.voidrp.asyncai.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Throttles the periodic full autosave to reduce player time-out kicks on slow (HDD) disks.
 *
 * Problem (this server runs on a 5400rpm HDD): the periodic autosave calls
 * {@code MinecraftServer.saveEverything(true, false, false)} — a synchronous full save
 * (level.dat + all dirty region chunks + SavedData). On a saturated spinning disk this
 * freezes the main thread ~30-35 s, so online players stop receiving keep-alives and get
 * disconnected ("timed out"). Watchdog dumps park in {@code saveEverything → NbtIo.writeCompressed}.
 *
 * We hook {@code saveEverything(ZZZ)Z} rather than the vanilla {@code autoSave()} because
 * this Youer/Purpur build patches its tick loop to call {@code saveEverything} directly and
 * never routes through {@code autoSave()} (an earlier autoSave() mixin never fired).
 *
 * Distinguishing autosave from a save that MUST run: the periodic autosave is the only caller
 * that passes {@code flush=false, forced=false}. Manual {@code /save-all} and the shutdown save
 * pass {@code flush}/{@code forced == true}, so they are never throttled — no data loss beyond
 * the widened autosave window (default 15 min, {@code -Dvoidrp.autosave.minMinutes} override).
 * The server has a Watchdog auto-restart; the real cure is moving the world to an SSD.
 */
@Mixin(MinecraftServer.class)
public abstract class AutoSaveThrottleMixin {

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

    @Inject(
        method = "saveEverything(ZZZ)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp$throttleAutoSave(boolean suppressLog, boolean flush, boolean forced,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (MIN_INTERVAL_MS <= 0L) {
            return; // throttling disabled
        }
        // Only the periodic autosave passes flush=false & forced=false.
        // Manual /save-all and shutdown pass true — always let those run.
        if (flush || forced) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!voidrp$loggedInterval) {
            voidrp$loggedInterval = true;
            VoidRpAsyncAI.LOGGER.info(
                "[VoidRP] Periodic autosave throttled to a minimum of {} min " +
                "(override with -Dvoidrp.autosave.minMinutes). Reduces HDD save-freeze kicks.",
                MIN_INTERVAL_MS / 60_000L);
        }
        if (voidrp$lastFullAutosaveMs != 0L && now - voidrp$lastFullAutosaveMs < MIN_INTERVAL_MS) {
            long agoS = (now - voidrp$lastFullAutosaveMs) / 1000L;
            VoidRpAsyncAI.LOGGER.info(
                "[VoidRP] Skipping periodic autosave ({}s since last; min {}min) — " +
                "avoids HDD save-freeze kick.", agoS, MIN_INTERVAL_MS / 60_000L);
            cir.setReturnValue(true); // report "saved" and skip the freeze
            return;
        }
        voidrp$lastFullAutosaveMs = now; // allow it (vanilla proceeds with the full save)
    }
}
