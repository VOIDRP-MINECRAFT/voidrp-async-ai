package ru.voidrp.asyncai.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.PendingSave;
import ru.voidrp.asyncai.PlayerSaveWorker;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPOutputStream;

/**
 * Offloads player data saves (NbtIo.writeCompressed + safeReplaceFile) from the server
 * thread to a background I/O worker to prevent watchdog hangs when the kernel write()
 * syscall blocks under page-cache / swap pressure.
 *
 * Vanilla PlayerDataStorage.save() sequence:
 *   1. Build CompoundTag
 *   2. Create temp file
 *   3. NbtIo.writeCompressed(tag, tempFile)   <-- redirected: serialize to byte[] in-memory
 *   4. Util.safeReplaceFile(playerFile, tempFile, backupFile)  <-- redirected: submit async task
 *
 * The main thread only pays for in-memory GZIP serialization (fast, CPU-bound).
 * The actual disk write + atomic rename runs on the background worker thread.
 *
 * Ordering: PlayerListRemoveMixin calls awaitPendingForPlayer() at the start of remove(),
 * so the disconnect save is always submitted after any in-flight autosave completes.
 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataSaveAsyncMixin {

    @Unique
    private static final ThreadLocal<PendingSave> VOIDRP_PENDING = new ThreadLocal<>();

    /**
     * Step 1: intercept NbtIo.writeCompressed. Instead of writing to the temp file
     * (which vanilla immediately renames in step 2), serialize to an in-memory byte[].
     * The actual disk write is deferred to voidrp_asyncRename so both operations
     * happen atomically on the background thread.
     */
    @Redirect(
        method = "save",
        at = @At(
            value  = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"
        ),
        require = 0
    )
    private void voidrp_captureWrite(CompoundTag tag, Path tempPath) throws IOException {
        if (PlayerSaveWorker.isShutdown()) {
            // Shutdown saves are synchronous: background thread may not run before process exit.
            NbtIo.writeCompressed(tag, tempPath);
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(131072);
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             DataOutputStream dos = new DataOutputStream(gzos)) {
            NbtIo.write(tag, dos);
        }
        VOIDRP_PENDING.set(new PendingSave(baos.toByteArray(), tempPath));
        // tempPath is intentionally left empty on disk — voidrp_asyncRename handles the rename
    }

    /**
     * Step 2: intercept Util.safeReplaceFile. If we have buffered bytes from step 1,
     * submit the (disk-write + atomic-rename) as a single background task, eliminating
     * the race where vanilla renamed an empty temp file before the async write finished.
     */
    @Redirect(
        method = "save",
        at = @At(
            value  = "INVOKE",
            target = "Lnet/minecraft/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"
        ),
        require = 0
    )
    private void voidrp_asyncRename(Path currentFile, Path newFile, Path backupFile) {
        PendingSave pending = VOIDRP_PENDING.get();
        VOIDRP_PENDING.remove();

        if (pending == null) {
            // voidrp_captureWrite didn't fire (Youer may patch the method differently);
            // temp file already has data written by vanilla — rename synchronously.
            voidrp$syncRename(currentFile, newFile, backupFile);
            return;
        }

        if (PlayerSaveWorker.isShutdown()) {
            // voidrp_captureWrite handled the shutdown case by writing sync, but
            // somehow still set VOIDRP_PENDING — flush and rename sync as fallback.
            try { Files.write(newFile, pending.bytes()); } catch (IOException e) {
                VoidRpAsyncAI.LOGGER.error("[VoidRP] Shutdown flush failed for {}: {}", currentFile.getFileName(), e.getMessage());
            }
            voidrp$syncRename(currentFile, newFile, backupFile);
            return;
        }

        try {
            PlayerSaveWorker.submit(pending.bytes(), pending.tempPath(), currentFile, backupFile);
        } catch (RejectedExecutionException e) {
            VoidRpAsyncAI.LOGGER.warn("[VoidRP] Executor rejected save for {} — falling back to sync", currentFile.getFileName());
            try { Files.write(newFile, pending.bytes()); } catch (IOException ex) {
                VoidRpAsyncAI.LOGGER.error("[VoidRP] Sync fallback write failed for {}: {}", currentFile.getFileName(), ex.getMessage());
                return;
            }
            voidrp$syncRename(currentFile, newFile, backupFile);
        }
    }

    /**
     * Safety net: if voidrp_asyncRename didn't fire (e.g. Youer removed the safeReplaceFile
     * call entirely), log the orphan so the problem is visible in logs.
     */
    @Inject(method = "save", at = @At("RETURN"), require = 0)
    private void voidrp_onSaveReturn(Player player, CallbackInfo ci) {
        PendingSave pending = VOIDRP_PENDING.get();
        if (pending != null) {
            VOIDRP_PENDING.remove();
            VoidRpAsyncAI.LOGGER.error(
                "[VoidRP] safeReplaceFile redirect missed — pending save for {} was not submitted. " +
                "Player data may not be saved. Check if Youer changed PlayerDataStorage.save().",
                pending.tempPath().getFileName());
        }
    }

    @Unique
    private static void voidrp$syncRename(Path current, Path newFile, Path backup) {
        try {
            Files.deleteIfExists(backup);
            Files.move(current, backup, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {}
        try {
            Files.move(newFile, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            VoidRpAsyncAI.LOGGER.error("[VoidRP] Sync rename failed for {}: {}", current.getFileName(), e.getMessage());
        }
    }

}
