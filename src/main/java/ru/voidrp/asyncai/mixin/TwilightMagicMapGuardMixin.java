package ru.voidrp.asyncai.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.function.Function;

/**
 * Guards TF MagicMapItem.update() against main-thread hang from biome computation.
 *
 * Root cause (Watchdog dump 2026-05-30 01:20):
 *   Player teleported to TF at x=-47689, z=-38180 with a Magic Map in inventory.
 *   MagicMapItem.update() calls computeIfAbsent for all uncached map pixels.
 *   Each miss triggers TFBiomeProvider.getNoiseBiome → deep LazyArea recursion
 *   (ZoomLayer → CastleTransformer → FilteredBiomeLayer → ...).
 *   At extreme coordinates the recursion is extremely slow (>10 s per tick).
 *
 * Fix: limit biome computeIfAbsent calls to MAX_BIOMES_PER_UPDATE per update().
 *   Already-cached positions return instantly (don't count against budget).
 *   Uncached positions beyond the budget return the last valid biome as a
 *   visual placeholder WITHOUT caching it — so they retry next tick.
 *   All map pixels fill in correctly over ~(total_pixels / 32) ticks.
 */
@Mixin(targets = "twilightforest.item.MagicMapItem", remap = false)
public abstract class TwilightMagicMapGuardMixin {

    @Unique
    private static final int MAX_BIOMES_PER_UPDATE = 32;

    @Unique
    private static final ThreadLocal<int[]> voidrp$callCount = ThreadLocal.withInitial(() -> new int[1]);

    @Unique
    private static final ThreadLocal<Holder[]> voidrp$lastBiome = ThreadLocal.withInitial(() -> new Holder[1]);

    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void voidrp$resetCallCount(CallbackInfo ci) {
        voidrp$callCount.get()[0] = 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/HashMap;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"
        ),
        remap = false,
        require = 0
    )
    private Object voidrp$limitedComputeIfAbsent(HashMap map, Object key, Function function) {
        // Fast path: already cached — don't count against budget
        Object cached = map.get(key);
        if (cached != null) return cached;

        int[] count = voidrp$callCount.get();
        if (count[0]++ >= MAX_BIOMES_PER_UPDATE) {
            // Budget exceeded: return last valid biome as visual placeholder.
            // Not stored in the map so this position is retried next tick.
            Holder[] last = voidrp$lastBiome.get();
            if (last[0] != null) return last[0];
            // Fallback for very first call somehow over budget: compute it anyway
        }

        Object result = map.computeIfAbsent(key, function);
        if (result != null) {
            voidrp$lastBiome.get()[0] = (Holder) result;
        }
        return result;
    }
}
