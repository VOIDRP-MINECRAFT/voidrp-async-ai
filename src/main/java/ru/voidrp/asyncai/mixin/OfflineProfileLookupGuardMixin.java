package ru.voidrp.asyncai.mixin;

import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Skips Mojang API profile lookups for version-3 (offline/cracked) UUIDs.
 *
 * Offline servers generate UUID v3 for every player via
 * nameUUIDFromBytes("OfflinePlayer:<name>"). Mojang's API returns 403 for these
 * because they don't exist in the live database. The 403 is harmless but spams
 * the log. Returning null here means the session service treats the profile as
 * "not found" immediately, skipping the HTTP round-trip entirely.
 *
 * require=0: fails silently if the method signature doesn't match.
 */
@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public abstract class OfflineProfileLookupGuardMixin {

    @Inject(
        method = "fetchProfile",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void voidrp_skipOfflineProfileLookup(UUID uuid, boolean requireSecure,
                                                  CallbackInfoReturnable<Object> cir) {
        if (uuid != null && uuid.version() == 3) {
            cir.setReturnValue(null);
        }
    }
}
