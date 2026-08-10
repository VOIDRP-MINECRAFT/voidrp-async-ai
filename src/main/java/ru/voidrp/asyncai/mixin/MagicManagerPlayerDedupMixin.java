package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Prevents an irons_spellbooks Counterspell cast from freezing the server tick when the
 * level's player list has been duplicated by sable / shtreimel sub-levels.
 *
 * === Observed HUNG_TICK 2026-07-25 20:30 ===
 * Server thread spun >75 s (RUNNABLE, CPU-bound, escalating Watchdog dumps) with the top
 * frames alternating between
 *   {@code CounterspellSpell.onCast -> Entity.getEyePosition} and
 *   {@code CounterspellSpell.onCast -> MagicManager.spawnParticles -> ServerLevel.sendParticles}
 * all under {@code MagicManager.tick -> level.players().forEach(...)}.
 *
 * {@link io.redspace.ironsspellbooks.capabilities.magic.MagicManager#tick} iterates
 * {@code level.players()} and, for every player currently mid-casting, invokes the spell's
 * {@code onCast}. Each {@code CounterspellSpell.onCast} is individually cheap (one raycast +
 * a particle broadcast), so with the 4 real players online this loop can never take 75 s —
 * unless the player list itself is inflated. The log was flooded (every tick) with
 * {@code [shtreimel] sub-level added/removed} around a single base: sable's inclusive
 * level-entity getter returns the same counterspell-casting player once per active sub-level,
 * so one real cast fans out into thousands of {@code onCast} + {@code sendParticles} calls per
 * tick. This is the same runaway-mod-entity family as {@link ArsProjectileSpellStormCapMixin}
 * and {@link ArsLightningHitDedupMixin}, via the player-list path instead of a getEntities scan.
 *
 * Fix: de-duplicate {@code level.players()} by UUID at the exact call site inside
 * {@code MagicManager.tick}. A healthy player list has no duplicate UUIDs, so the redirect
 * returns the ORIGINAL list unchanged — zero behavior change for normal play. Only when
 * sub-level duplication is present does it collapse the copies to one entry per real player,
 * bounding the loop to the true online count while still processing every real cast exactly
 * once.
 *
 * remap=false: {@code MagicManager} is a mod class on plain names. The redirected
 * {@code Level#players()} and {@code Player#getUUID()} use official Mojang mappings (the
 * NeoForge 1.21.1 runtime names), so no remap is needed there either. require=0: if the mod
 * or method is absent the mixin simply no-ops.
 */
@Mixin(targets = "io.redspace.ironsspellbooks.capabilities.magic.MagicManager", remap = false)
public abstract class MagicManagerPlayerDedupMixin {

    @org.spongepowered.asm.mixin.Unique
    private static volatile long voidrp$lastDedupLog = 0L;

    // --- Absolute per-window cap on cast dispatch (belt-and-suspenders over the dedup above) ---
    // The dedup redirect only collapses duplicates WITHIN a single level's players() list. If
    // sable/shtreimel instead fans the same real cast across many sub-LEVELS (one MagicManager.tick
    // per sub-level, each list internally unique), dedup finds nothing to collapse and the storm
    // survives. This cap bounds the total number of per-player cast callbacks the server thread will
    // run in any short wall-clock window, regardless of the inflation mechanism. lambda$tick$0 is the
    // exact per-player-entry callback that invokes AbstractSpell.castSpell -> CounterspellSpell.onCast,
    // so it is the true multiplier point. Under healthy play a handful of casts fire per window and the
    // cap is never approached (zero behavior change); only a runaway storm is throttled.
    @org.spongepowered.asm.mixin.Unique
    private static final int VOIDRP_MAX_CASTS_PER_WINDOW = 400;
    @org.spongepowered.asm.mixin.Unique
    private static final long VOIDRP_CAST_WINDOW_MS = 50L;
    @org.spongepowered.asm.mixin.Unique
    private static volatile long voidrp$castWindowStart = 0L;
    @org.spongepowered.asm.mixin.Unique
    private static int voidrp$castsThisWindow = 0;
    @org.spongepowered.asm.mixin.Unique
    private static volatile long voidrp$lastCapLog = 0L;

    @Inject(
        method = "lambda$tick$0",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void voidrp$capCastStorm(boolean recast, Player player, CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - voidrp$castWindowStart > VOIDRP_CAST_WINDOW_MS) {
            voidrp$castWindowStart = now;
            voidrp$castsThisWindow = 0;
        }
        if (++voidrp$castsThisWindow > VOIDRP_MAX_CASTS_PER_WINDOW) {
            if (now - voidrp$lastCapLog > 5000L) {
                voidrp$lastCapLog = now;
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP Async AI] MagicManager cast storm — capping at {} casts / {} ms to keep "
                    + "the tick alive (irons_spellbooks Counterspell sub-level fan-out guard)",
                    VOIDRP_MAX_CASTS_PER_WINDOW, VOIDRP_CAST_WINDOW_MS);
            }
            ci.cancel();
        }
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;players()Ljava/util/List;"
        ),
        require = 0,
        remap = false
    )
    private List<? extends Player> voidrp$dedupPlayers(Level level) {
        List<? extends Player> players = level.players();
        int n = players.size();
        if (n < 2) {
            return players;
        }
        HashSet<UUID> seen = new HashSet<>(n * 2);
        ArrayList<Player> deduped = new ArrayList<>(n);
        for (Player p : players) {
            if (seen.add(p.getUUID())) {
                deduped.add(p);
            }
        }
        if (deduped.size() == n) {
            // No duplicates — return the original list, guaranteeing no behavior change.
            return players;
        }
        long now = System.currentTimeMillis();
        if (now - voidrp$lastDedupLog > 5000L) {
            voidrp$lastDedupLog = now;
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP Async AI] MagicManager.tick — level.players() inflated {} -> {} by "
                + "duplicate sub-level entries; de-duplicating by UUID to keep the tick alive "
                + "(irons_spellbooks Counterspell storm guard)", n, deduped.size());
        }
        return deduped;
    }

    // --- HUNG_TICK 2026-07-29 13:53 (12 escalating Watchdog dumps, hard hang, kill -9 required) ---
    // Every sample was LOCKED (not alternating) in a single CounterspellSpell.onCast ->
    // MagicManager.spawnParticles -> ServerLevel.sendParticles chain: one spawnParticles call stuck
    // mid-forEach. spawnParticles broadcasts the particle by iterating
    // MinecraftServer.getPlayerList().getPlayers() (the global online-player list, an unmodifiable
    // view on this Mohist/Youer hybrid — matches the Collections$UnmodifiableCollection frame), NOT
    // level.players(). sable's inclusive entity/player getters inflate that global list to thousands
    // of duplicate ServerPlayer entries, so a SINGLE cast's particle broadcast walks a huge list and
    // never returns within the 10 s Watchdog window. The tick()-side dedup + cast cap above cannot
    // help: we are stuck inside one spawnParticles, having already passed lambda$tick$0.
    //
    // Fix: de-duplicate getPlayerList().getPlayers() by UUID at the exact call site inside
    // spawnParticles, mirroring the tick redirect. Healthy list has no duplicate UUIDs -> original
    // list returned unchanged (zero behavior change); only sable-inflated lists are collapsed to one
    // entry per real online player, bounding the broadcast to the true player count.
    @Redirect(
        method = "spawnParticles",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"
        ),
        require = 0,
        remap = false
    )
    private static List<net.minecraft.server.level.ServerPlayer> voidrp$dedupBroadcastPlayers(
            net.minecraft.server.players.PlayerList playerList) {
        List<net.minecraft.server.level.ServerPlayer> players = playerList.getPlayers();
        int n = players.size();
        if (n < 2) {
            return players;
        }
        HashSet<UUID> seen = new HashSet<>(n * 2);
        ArrayList<net.minecraft.server.level.ServerPlayer> deduped = new ArrayList<>(n);
        for (net.minecraft.server.level.ServerPlayer p : players) {
            if (seen.add(p.getUUID())) {
                deduped.add(p);
            }
        }
        if (deduped.size() == n) {
            return players;
        }
        long now = System.currentTimeMillis();
        if (now - voidrp$lastDedupLog > 5000L) {
            voidrp$lastDedupLog = now;
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP Async AI] MagicManager.spawnParticles — getPlayerList().getPlayers() "
                + "inflated {} -> {} by duplicate sub-level entries; de-duplicating by UUID to keep "
                + "the particle broadcast (and the tick) alive (irons_spellbooks Counterspell "
                + "storm guard)", n, deduped.size());
        }
        return deduped;
    }
}
