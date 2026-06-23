package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.PlayerSaveWorker;

/**
 * Ensures correct ordering for player data saves during disconnect (PlayerList.remove).
 *
 * During autosave, writes are offloaded to a background thread (PlayerDataSaveAsyncMixin).
 * If a player disconnects while an autosave is still in flight, awaitPendingForPlayer()
 * waits for it to finish before the disconnect save is submitted — so the disconnect
 * save is always the last queued write for this player and contains the freshest data.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRemoveMixin {

    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void voidrp_beforeRemove(ServerPlayer player, CallbackInfo ci) {
        // Wait for any pending autosave write for this player to finish before the disconnect
        // save is submitted, so the disconnect data is always the last write for this player.
        PlayerSaveWorker.awaitPendingForPlayer(player.getUUID());
    }
}
