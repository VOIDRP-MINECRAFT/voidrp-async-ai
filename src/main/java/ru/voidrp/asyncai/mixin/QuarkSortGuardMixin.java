package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks Quark's sort button when a plugin-created GUI is open.
 * Sorting a virtual plugin inventory would corrupt its item layout.
 */
@Mixin(targets = "org.violetmoon.quark.base.network.message.SortInventoryMessage", remap = false)
public class QuarkSortGuardMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void voidrp_blockPluginInventorySort(ServerPlayer player, CallbackInfo ci) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null &&
            menu.getClass().getName().equals("org.bukkit.craftbukkit.inventory.CraftContainer")) {
            ci.cancel();
        }
    }
}
