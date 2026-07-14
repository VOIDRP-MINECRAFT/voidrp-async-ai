package ru.voidrp.asyncai.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents a server crash when a message is sent to a player whose connection is null.
 *
 * Root cause (crash 2026-07-14 20:52): a Create Large Water Wheel ticked and awarded an
 * advancement to a player who was mid-disconnect ({@code player.connection == null}). The
 * clickadv mod's PlayerAdvancements mixin ({@code clickadv$onGrant}) then called
 * {@code ServerPlayer.displayClientMessage} → {@code sendSystemMessage} →
 * {@code connection.send(...)} → NullPointerException, thrown from {@code ServerLevel.tickBlock},
 * crashing "Exception ticking world" and shutting the server down.
 *
 * Fix: guard both {@code sendSystemMessage} overloads at HEAD — if the connection is null the
 * player cannot receive anything anyway, so skip silently instead of NPE-crashing the tick.
 * {@code require = 0} keeps a descriptor mismatch non-fatal (the mod still loads).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerNullConnectionMessageGuardMixin {

    @Shadow
    public ServerGamePacketListenerImpl connection;

    @Inject(
        method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp$guardNullConnection(Component message, CallbackInfo ci) {
        if (this.connection == null) {
            ci.cancel();
        }
    }

    @Inject(
        method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp$guardNullConnectionOverlay(Component message, boolean bypassHiddenChat, CallbackInfo ci) {
        if (this.connection == null) {
            ci.cancel();
        }
    }
}
