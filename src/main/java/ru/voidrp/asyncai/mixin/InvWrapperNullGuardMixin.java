package ru.voidrp.asyncai.mixin;

import net.minecraft.world.Container;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards InvWrapper methods against null underlying inventory.
 * Jade's ItemStorageProvider calls getSlots() on wrappers whose backing
 * Container has already been invalidated, causing NPE spam in logs.
 * Returning 0 slots makes Jade skip the inventory gracefully.
 */
@Mixin(value = InvWrapper.class, remap = false)
public abstract class InvWrapperNullGuardMixin {

    @Shadow
    public abstract Container getInv();

    @Inject(method = "getSlots", at = @At("HEAD"), cancellable = true)
    private void voidrp_guardGetSlots(CallbackInfoReturnable<Integer> cir) {
        if (getInv() == null) cir.setReturnValue(0);
    }

    @Inject(method = "getStackInSlot", at = @At("HEAD"), cancellable = true)
    private void voidrp_guardGetStackInSlot(int slot, CallbackInfoReturnable<net.minecraft.world.item.ItemStack> cir) {
        if (getInv() == null) cir.setReturnValue(net.minecraft.world.item.ItemStack.EMPTY);
    }
}
