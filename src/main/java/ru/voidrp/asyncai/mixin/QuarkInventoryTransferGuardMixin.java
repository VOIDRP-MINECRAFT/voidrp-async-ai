package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks Quark's inventory transfer (extract/deposit/restock) and sort buttons
 * when a plugin-created GUI is open. Plugin GUIs use CraftContainer, which is
 * not a real block inventory — allowing transfer would let players steal items
 * from virtual screens like the market or Battle Pass GUI.
 */
@Mixin(targets = "org.violetmoon.quark.base.handler.InventoryTransferHandler", remap = false)
public class QuarkInventoryTransferGuardMixin {

    @Inject(method = "accepts", at = @At("HEAD"), cancellable = true)
    private static void voidrp_blockPluginInventoryAccept(
            AbstractContainerMenu menu, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (isPluginInventory(menu)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "transfer", at = @At("HEAD"), cancellable = true)
    private static void voidrp_blockPluginInventoryTransfer(
            Player player, boolean restock, boolean smart, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp && isPluginInventory(sp.containerMenu)) {
            ci.cancel();
        }
    }

    private static boolean isPluginInventory(AbstractContainerMenu menu) {
        return menu != null &&
               menu.getClass().getName().equals("org.bukkit.craftbukkit.inventory.CraftContainer");
    }
}
