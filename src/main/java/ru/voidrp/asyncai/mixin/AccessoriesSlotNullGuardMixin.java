package ru.voidrp.asyncai.mixin;

import io.wispforest.accessories.api.menu.AccessoriesSlotGenerator;
import io.wispforest.accessories.api.slot.SlotTypeReference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.Arrays;
import java.util.Objects;

/**
 * Guards AccessoriesSlotGenerator.adjustTypes(SlotTypeReference[]) against NPE
 * when one or more SlotTypeReference elements in the array are null.
 *
 * Root cause: Aether 1.5.10 with accessories config "use_default_accessories_menu=true"
 * skips AetherAccessorySlots.registerSlots(), leaving GLOVES_SLOT/RING_SLOT/etc. null.
 * OpenAccessoriesPacket.execute() does not check this config and always constructs
 * AetherAccessoriesMenu, which passes these null refs to AccessoriesSlotGenerator.of().
 *
 * Fix: when nulls are detected, re-invoke adjustTypes with a filtered array so the menu
 * opens with zero Aether-specific slots (empty panel) rather than crashing the server.
 */
@Mixin(value = AccessoriesSlotGenerator.class, remap = false)
public abstract class AccessoriesSlotNullGuardMixin {

    @Inject(
        method = "adjustTypes([Lio/wispforest/accessories/api/slot/SlotTypeReference;)Lio/wispforest/accessories/api/menu/AccessoriesSlotGenerator;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_filterNullSlotRefs(SlotTypeReference[] refs, CallbackInfoReturnable<AccessoriesSlotGenerator> cir) {
        boolean hasNulls = false;
        for (SlotTypeReference ref : refs) {
            if (ref == null) { hasNulls = true; break; }
        }
        if (!hasNulls) return;

        VoidRpAsyncAI.LOGGER.warn(
            "[VoidRP] AccessoriesSlotGenerator.adjustTypes — {} null SlotTypeReference(s) detected " +
            "(Aether use_default_accessories_menu=true skips slot registration). " +
            "Opening accessories menu with available slots only.",
            Arrays.stream(refs).filter(Objects::isNull).count());

        SlotTypeReference[] filtered = Arrays.stream(refs)
            .filter(Objects::nonNull)
            .toArray(SlotTypeReference[]::new);

        // Re-invoke with null-free array. The inject fires again but exits at !hasNulls,
        // so the original method body runs cleanly. Returns `this` (AccessoriesSlotGenerator).
        AccessoriesSlotGenerator result = ((AccessoriesSlotGenerator) (Object) this).adjustTypes(filtered);
        cir.setReturnValue(result);
    }
}
