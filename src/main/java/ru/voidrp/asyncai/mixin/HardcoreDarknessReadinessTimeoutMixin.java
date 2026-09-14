package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops Hardcore True Darkness from kicking players whose client is still loading.
 *
 * <p>The mod waits for a "client readiness report" and disconnects a player who has not
 * sent one within {@code statusTimeoutTicks} with "Hardcore True Darkness did not receive a
 * valid client readiness report". The config caps that at 1200 ticks (60 s), but the report
 * is sent from the client tick, and on this pack the client main thread is busy for up to a
 * minute after joining (JEI/recipe reload): across 77 joins 2026-09-10..14 the report took a
 * median 4 s but p90 56 s, max 62 s — and every kick of Onlyezz, SigmaStep22800, Mega_Vaflya
 * and sin_bosina landed at exactly 59-60 s. They had the right mod; they were just slow.
 *
 * <p>The timeout is raised to {@value #TIMEOUT_TICKS} ticks. A client without the mod still
 * never reports and is still kicked, and a report with a wrong protocol or setup is still
 * rejected the moment it arrives.
 */
@Mixin(targets = "com.hyrrx.hardcoretruedarkness.server.ServerHardeningHandler", remap = false)
public abstract class HardcoreDarknessReadinessTimeoutMixin {

    private static final int TIMEOUT_TICKS = 20 * 60 * 10;

    @Redirect(
            method = "onPlayerTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hyrrx/hardcoretruedarkness/config/HardcoreTrueDarknessConfig;statusTimeoutTicks()I"))
    private static int voidrp$patientReadinessTimeout() {
        return TIMEOUT_TICKS;
    }
}
