package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks Sophisticated Core's "deposit all" / "extract all" transfer buttons
 * when a plugin-created GUI is open.
 */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.network.TransferItemsPayload", remap = false)
public class SophisticatedCoreTransferGuardMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void voidrp_blockPluginInventoryTransfer(ServerPlayer player, CallbackInfo ci) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null &&
            menu.getClass().getName().equals("org.bukkit.craftbukkit.inventory.CraftContainer")) {
            ci.cancel();
        }
    }
}
