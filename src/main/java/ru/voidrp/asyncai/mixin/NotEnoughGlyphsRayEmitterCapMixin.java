package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Bounds the per-cast particle work of a not_enough_glyphs Ray-form glyph so one cast can
 * never freeze the server tick.
 *
 * === Observed HUNG_TICK 2026-07-27 20:42 (main server / youer) ===
 * Watchdog fired repeatedly; the Server thread was RUNNABLE and walking frames inside a
 * SINGLE synchronous spell cast:
 * <pre>
 *   PacketCastSpell.onServerReceived -> AbstractCaster.castSpell -> SpellResolver.onCastOnBlock
 *     -> MethodRay.onCastOnBlock -> fireRay(5-arg) -> fireRay(7-arg) -> send
 *       -> resolveEmitter -> SpellContext.getParticleTimeline -> TimelineMap.get
 *          -> SimpleParticleTimelineType.create -> RayTimeline.<init>
 *             -> PropertyParticleOptions.<init> -> ParticleTypeProperty.<init>
 * </pre>
 * The player was near a sable/shtreimel sub-level in far ungenerated terrain (log full of
 * getBlockState / seekLeaves / getFluidState guards at inflated coords). The ray's per-step
 * loop called {@code send(...)} an enormous number of times; every {@code send} rebuilds the
 * whole particle timeline/emitter from scratch and broadcasts a particle packet. The tick
 * hung 75+ seconds and did NOT self-recover — it had to be killed. Same failure family as
 * {@link ArsProjectileSpellStormCapMixin} and {@link MagicManagerPlayerDedupMixin}, but via
 * the not_enough_glyphs Ray form.
 *
 * A wall-clock window (as in {@link ArsProjectileSpellStormCapMixin}) does NOT work here: the
 * whole storm is one non-yielding call on the main thread, so time-based windows reset
 * mid-loop and never bite. Instead we cap {@code send(...)} invocations PER CAST: the counter
 * is reset at the head of the top-level {@code fireRay} (once per cast) and each {@code send}
 * increments it; past {@link #VOIDRP_MAX_SENDS_PER_CAST} the send is cancelled. Cancelling
 * {@code send} only drops excess *particle emission* — {@code fireRay} still resolves the
 * ray's hits/damage — so spell mechanics are untouched. A legitimate ray (even with heavy
 * Pierce/AOE) emits only a handful of sends per cast, far under budget.
 *
 * All targets are matched by string with remap=false (mod class on plain names). Signatures
 * are referenced only inside the {@code method=} descriptor string, so ars_nouveau does not
 * need to be on the compile classpath (raw {@link CallbackInfoReturnable} avoids importing
 * {@code CastResolveType}). This mirrors the proven pattern of {@link ArsProjectileSpellStormCapMixin}.
 */
@Mixin(targets = "alexthw.not_enough_glyphs.common.glyphs.forms.MethodRay", remap = false)
public abstract class NotEnoughGlyphsRayEmitterCapMixin {

    /** Max particle {@code send(...)} calls processed per single ray cast before excess are dropped. */
    @Unique
    private static final int VOIDRP_MAX_SENDS_PER_CAST = 512;

    /** Per-thread send counter for the current cast (spell casts run on the main server thread). */
    @Unique
    private static final ThreadLocal<int[]> voidrp$sendCount = ThreadLocal.withInitial(() -> new int[1]);

    @Unique
    private static volatile long voidrp$lastStormLog = 0L;

    /**
     * Reset the per-cast budget at the entry of the top-level (5-arg) fireRay. The 7-arg
     * fireRay is the recursive/per-segment variant and is deliberately NOT matched, so the
     * budget spans the whole cast rather than resetting on every recursion.
     */
    @Inject(
        method = "fireRay(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;",
        at = @At("HEAD"),
        remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void voidrp$resetSendBudget(CallbackInfoReturnable cir) {
        voidrp$sendCount.get()[0] = 0;
    }

    /** Cap the expensive per-step particle emission. send is a unique private void, matched by name. */
    @Inject(method = "send", at = @At("HEAD"), cancellable = true, remap = false)
    private void voidrp$capSend(CallbackInfo ci) {
        if (++voidrp$sendCount.get()[0] > VOIDRP_MAX_SENDS_PER_CAST) {
            ci.cancel();
            long now = System.currentTimeMillis();
            if (now - voidrp$lastStormLog > 5000L) {
                voidrp$lastStormLog = now;
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP Async AI] not_enough_glyphs MethodRay emitter storm — >{} particle sends "
                    + "in one cast; suppressing excess to keep the server tick alive", VOIDRP_MAX_SENDS_PER_CAST);
            }
        }
    }
}
