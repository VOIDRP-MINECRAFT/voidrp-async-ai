package ru.voidrp.asyncai.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import org.spongepowered.asm.mixin.Mixin;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Prevents a single broken entity spawn/pairing packet from crashing the whole server tick.
 *
 * Root cause (crash 2026-07-14 15:11): a Draconic Evolution {@code draconic_arrow} entity
 * builds its add-entity packet via BrandonsCore/CodeChickenLib, which calls
 * {@code writeRegistryId} with a bad id ("entity.draconicevolution.draconic_arrow" — a
 * translation key, not a registry key). The registry lookup throws IllegalArgumentException
 * from inside {@code ServerEntity.sendPairingData} → {@code ChunkMap.TrackedEntity.updatePlayer}
 * → {@code ChunkMap.tick} → {@code ServerLevel.tick}, so one bad entity near a player crashes
 * "Exception ticking world" and the server shuts down.
 *
 * Fix: wrap {@code sendPairingData} in a try/catch. A throwing entity is skipped for that
 * player (it just won't be shown to them) and logged, instead of taking down the server.
 * Rate-limited log so a persistent bad entity does not spam.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntitySpawnPacketGuardMixin {

    private static final AtomicLong voidrp$lastWarnNs = new AtomicLong(0L);

    @WrapMethod(method = "sendPairingData")
    private void voidrp$guardSpawnPacket(
            ServerPlayer player,
            PacketAndPayloadAcceptor consumer,
            Operation<Void> original) {
        try {
            original.call(player, consumer);
        } catch (Throwable t) {
            long now = System.nanoTime();
            long prev = voidrp$lastWarnNs.get();
            if (now - prev > 5_000_000_000L && voidrp$lastWarnNs.compareAndSet(prev, now)) {
                VoidRpAsyncAI.LOGGER.error(
                    "[VoidRP] Skipped a broken entity spawn/pairing packet (bad mod entity) — " +
                    "prevented a server-crashing 'Exception ticking world'. Entity not shown to this player.",
                    t);
            }
        }
    }
}
