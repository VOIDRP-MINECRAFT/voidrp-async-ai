package ru.voidrp.asyncai.mixin;

import net.neoforged.neoforge.registries.DeferredHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Eliminates ResourceKey.toString() overhead in DeferredHolder.value() NPE path.
 *
 * Root cause (profiler: 0.44% self-time in ResourceKey.toString):
 *   DeferredHolder.value() calls bind(true), then if holder == null throws:
 *     new NullPointerException("Holder not present: " + String.valueOf(this.key))
 *   String.valueOf(key) calls ResourceKey.toString() which allocates a new String
 *   every time. Several mods (Aether, MowziesMobs, fluid checks) trigger this path
 *   frequently during entity ticking, likely due to deferred registration timing.
 *
 * Fix: @Redirect replaces String.valueOf(key) with a static empty string.
 * The NPE is still thrown (preserving error visibility in crash reports).
 * The key name is omitted from the message — acceptable tradeoff vs. 0.44% overhead.
 */
@Mixin(value = DeferredHolder.class, remap = false)
public abstract class DeferredHolderValueMixin {

    @Redirect(
        method = "value",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/String;valueOf(Ljava/lang/Object;)Ljava/lang/String;",
            remap = false
        ),
        require = 0,
        remap = false
    )
    private String voidrp_skipResourceKeyToString(Object key) {
        return "?";
    }
}
