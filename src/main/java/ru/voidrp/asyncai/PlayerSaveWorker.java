package ru.voidrp.asyncai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offloads player data disk writes + renames from the main thread to avoid watchdog hangs
 * when the kernel write() syscall blocks under page-cache pressure.
 *
 * The bytes are pre-serialized on the main thread (CPU-bound, fast) so the background
 * thread only does disk I/O. Write and rename run together in the same task so
 * they cannot race with each other.
 *
 * Two pending-task maps are maintained:
 *   - by Path   — for deduplication (newer save cancels older pending save for same file)
 *   - by UUID   — for per-player await during disconnect (PlayerListRemoveMixin)
 *
 * Disconnect ordering (PlayerListRemoveMixin):
 *   1. awaitPendingForPlayer(uuid) — wait for any in-flight autosave to finish
 *   2. PlayerDataStorage.save() — serializes to byte[] on main thread, submits async task
 *
 * This guarantees the disconnect save is always the last queued write for that player.
 */
public final class PlayerSaveWorker {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voidrp-player-save");
        t.setDaemon(false);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ConcurrentHashMap<Path, Future<?>> PENDING_PATH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Future<?>> PENDING_UUID = new ConcurrentHashMap<>();
    private static final AtomicBoolean IS_SHUTDOWN = new AtomicBoolean(false);

    private PlayerSaveWorker() {}

    public static boolean isShutdown() {
        return IS_SHUTDOWN.get();
    }

    /**
     * Submits an async save: writes {@code bytes} to {@code tempPath}, then atomically
     * renames it into place (tempPath → currentFile, currentFile → backupFile).
     * A newer call for the same player cancels any still-pending previous task.
     *
     * @throws RejectedExecutionException if the executor has already shut down
     */
    public static void submit(byte[] bytes, Path tempPath, Path currentFile, Path backupFile) {
        UUID uuid = uuidFromPath(currentFile);

        Future<?> prev = PENDING_PATH.remove(currentFile);
        if (prev != null) prev.cancel(false);
        if (uuid != null) PENDING_UUID.remove(uuid);

        Future<?> future = EXECUTOR.submit(() -> {
            try {
                Files.write(tempPath, bytes);
                try {
                    Files.deleteIfExists(backupFile);
                    Files.move(currentFile, backupFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ignored) {}
                Files.move(tempPath, currentFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                VoidRpAsyncAI.LOGGER.error(
                    "[VoidRP] Async player save failed for {}: {}",
                    currentFile.getFileName(), e.getMessage());
            } finally {
                PENDING_PATH.remove(currentFile);
                if (uuid != null) PENDING_UUID.remove(uuid);
            }
        });

        PENDING_PATH.put(currentFile, future);
        if (uuid != null) PENDING_UUID.put(uuid, future);
    }

    /**
     * Blocks until any in-flight async save for {@code uuid} completes (max 5 s).
     * Called from PlayerListRemoveMixin before a synchronous disconnect save so the
     * disconnect data always wins over a stale pending autosave.
     */
    public static void awaitPendingForPlayer(UUID uuid) {
        Future<?> f = PENDING_UUID.get(uuid);
        if (f == null) return;
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    /**
     * Marks the worker as shut down and waits up to 30 s for queued writes to finish.
     * Called from ServerStoppingEvent so in-flight async saves complete before process exit.
     */
    public static void awaitAndShutdown() {
        IS_SHUTDOWN.set(true);
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] Player save worker did not finish in 30 s — forcing shutdown");
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        PENDING_PATH.clear();
        PENDING_UUID.clear();
        VoidRpAsyncAI.LOGGER.info("[VoidRP] Player save worker shut down");
    }

    /** Parses the player UUID from a filename like "ae2e71e7-de01-3049-a19a-5f79210c89cb.dat". */
    private static UUID uuidFromPath(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".dat")) return null;
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
