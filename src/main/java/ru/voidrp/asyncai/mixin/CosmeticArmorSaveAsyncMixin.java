package ru.voidrp.asyncai.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prevents cosmeticarmorreworked (lain.mods.cos) from blocking the main thread with a
 * synchronous NbtIo.write() call during PlayerSavingEvent.
 *
 * Root cause (Watchdog dump 2026-05-30 18:35):
 *   PlayerList.saveAll() → PlayerDataStorage.save() → PlayerSavingEvent
 *   → InventoryManager.handleSaveToFile() → InventoryManager.saveInventory()
 *   → NbtIo.write(tag, path) → DataOutputStream.close() → BufferedOutputStream.flush()
 *   → FileChannelImpl.write → UnixFileDispatcherImpl.write0 (native, blocks under I/O pressure
 *   with 318 MB swap in use and 112k+ overworld chunks being saved concurrently)
 *
 * Fix: redirect NbtIo.write(CompoundTag, Path) inside saveInventory to serialize the tag to
 * a byte[] in-memory on the main thread (CPU-only, no kernel I/O syscall), then submit the
 * actual disk write to a dedicated background IO thread via atomic tmp-rename.
 */
@Mixin(targets = "lain.mods.cos.impl.InventoryManager", remap = false)
public abstract class CosmeticArmorSaveAsyncMixin {

    @Unique
    private static final ExecutorService VOIDRP_COSAR_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voidrp-cosar-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    @Redirect(
        method = "saveInventory",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;write(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"
        ),
        require = 0,
        remap = true
    )
    private void voidrp_cosarNbtWriteAsync(CompoundTag tag, Path path) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(baos))) {
            NbtIo.write(tag, dos);
        }
        byte[] bytes = baos.toByteArray();
        VOIDRP_COSAR_IO.submit(() -> {
            Path tmp = path.resolveSibling(path.getFileName() + ".voidrp_tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                VoidRpAsyncAI.LOGGER.error(
                    "[VoidRP] CosAR async save failed for {}: {}", path.getFileName(), e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }
}
