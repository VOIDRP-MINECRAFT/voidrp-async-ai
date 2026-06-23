package ru.voidrp.asyncai;

import com.mojang.brigadier.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.literal;

@Mod(VoidRpAsyncAI.MODID)
public final class VoidRpAsyncAI {

    public static final String MODID = "voidrp_async_ai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    private static final int CLEANUP_INTERVAL  = 200;  // ticks (~10 sec)
    private static final int STATS_LOG_INTERVAL = 6000; // ticks (~5 min)
    private static int tickCounter = 0;

    public VoidRpAsyncAI(IEventBus modBus, ModContainer container) {
        AiConfig.register(container);

        NeoForge.EVENT_BUS.addListener(VoidRpAsyncAI::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(VoidRpAsyncAI::onServerTick);
        NeoForge.EVENT_BUS.addListener(VoidRpAsyncAI::onServerStarted);
        NeoForge.EVENT_BUS.addListener(VoidRpAsyncAI::onServerStopping);
        NeoForge.EVENT_BUS.addListener(VoidRpAsyncAI::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(VoidRpAsyncAI::onPlayerLogout);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ConfigCache.refresh();
        ParallelLosManager.init();
        ChunkPreloadManager.init();
        LOGGER.info(
            "[VoidRP Async AI] loaded — {} pathfinder threads | " +
            "async={} cache={} dabs={} brain={} nav={} hibernate={} spawn={} adaptive={} parallelLos={} chunkPreload={}",
            AsyncPathManager.getThreadCount(),
            AiConfig.ASYNC_PATH_ENABLED.get(),
            AiConfig.PATH_CACHE_ENABLED.get(),
            AiConfig.DABS_ENABLED.get(),
            AiConfig.BRAIN_THROTTLE_ENABLED.get(),
            AiConfig.NAV_THROTTLE_ENABLED.get(),
            AiConfig.HIBERNATE_ENABLED.get(),
            AiConfig.SPAWN_THROTTLE_ENABLED.get(),
            AiConfig.ADAPTIVE_THROTTLE_ENABLED.get(),
            AiConfig.PARALLEL_LOS_ENABLED.get(),
            AiConfig.CHUNK_PRELOAD_ENABLED.get()
        );
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        AsyncPathManager.shutdown();
        ParallelLosManager.shutdown();
        ChunkPreloadManager.shutdown();
        AdaptiveThrottle.reset();
        PlayerSaveWorker.awaitAndShutdown();
        LOGGER.info("[VoidRP Async AI] thread pools shut down");
    }

    private static void onServerTickPre(ServerTickEvent.Pre event) {
        AdaptiveThrottle.onTickStart();
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        ChunkPreloadManager.onPlayerLeave(id);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        AdaptiveThrottle.onTickEnd();
        tickCounter++;
        if (tickCounter % 20 == 0) ConfigCache.refresh();
        ChunkPreloadManager.onTick(event.getServer());

        MinecraftServer server = event.getServer();

        if (tickCounter % CLEANUP_INTERVAL == 0) {
            List<Mob> allMobs = collectAllMobs(server);
            EntityThrottle.cleanup(allMobs);
            PathCache.cleanup(allMobs);
            // Evict stale LOS cache entries (once per ~10 sec is enough)
            long gameTime = server.overworld().getGameTime();
            LineOfSightCache.evictStale(gameTime);
        }

        if (tickCounter % STATS_LOG_INTERVAL == 0) {
            LOGGER.debug(
                "[VoidRP Async AI] stats — TPS: {} | load: {}x | in-flight: {} | pending: {}",
                String.format("%.1f", AdaptiveThrottle.getCurrentTps()),
                String.format("%.2f", AdaptiveThrottle.getLoadFactor()),
                AsyncPathManager.getInFlightCount(),
                AsyncPathManager.getPendingCount()
            );
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            literal("voidrpai")
                .requires(src -> src.hasPermission(2))
                .then(literal("stats").executes(ctx -> {
                    double tps  = AdaptiveThrottle.getCurrentTps();
                    double load = AdaptiveThrottle.getLoadFactor();
                    String tpsStr  = String.format("%.1f", tps);
                    String loadStr = String.format("%.2f", load);
                    String tpsTag  = tps < 18.0
                        ? ChatFormatting.RED + " (lagging)" + ChatFormatting.RESET
                        : ChatFormatting.GREEN + " (ok)" + ChatFormatting.RESET;

                    ctx.getSource().sendSuccess(() -> Component.literal(
                        ChatFormatting.GOLD + "[VoidRP Async AI] stats:" + ChatFormatting.RESET +
                        "\n  TPS                : " + tpsStr + tpsTag +
                        "\n  Adaptive load      : " + loadStr + "x" +
                        "\n  Pathfinder threads : " + AsyncPathManager.getThreadCount() +
                        "\n  In-flight paths    : " + AsyncPathManager.getInFlightCount() +
                        "\n  Pending results    : " + AsyncPathManager.getPendingCount() +
                        "\n  Async path dist    : " + AiConfig.ASYNC_PATH_MIN_DIST.get() + " blocks" +
                        "\n  DABS near/far/vfar : " +
                            AiConfig.THROTTLE_NEAR_DIST.get() + "/" +
                            AiConfig.THROTTLE_FAR_DIST.get() + "/" +
                            AiConfig.THROTTLE_VFAR_DIST.get() + " blocks" +
                        "\n  Hibernate dist     : " + AiConfig.HIBERNATE_DIST.get() + " blocks" +
                        "\n  Chunk preload      : in-flight=" + ChunkPreloadManager.getInFlightCount() +
                            " pending=" + ChunkPreloadManager.getPendingCount()
                    ), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("reload").executes(ctx -> {
                    ctx.getSource().sendSuccess(() ->
                        Component.literal(ChatFormatting.GREEN + "[VoidRP Async AI] config reloaded"), false);
                    return Command.SINGLE_SUCCESS;
                }))
        );
    }

    private static List<Mob> collectAllMobs(MinecraftServer server) {
        List<Mob> mobs = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            level.getEntities().getAll().forEach(e -> {
                if (e instanceof Mob m) mobs.add(m);
            });
        }
        return mobs;
    }
}
