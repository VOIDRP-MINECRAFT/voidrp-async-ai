package ru.voidrp.asyncai;

/**
 * Signals to PlayerDataSaveAsyncMixin that the current save is triggered by a player
 * disconnect (PlayerList.remove) rather than a periodic autosave.
 * Disconnect saves must be synchronous — the player is leaving and we cannot risk
 * losing their data to an in-flight async task that may not complete before a crash.
 */
public final class DisconnectContext {

    private static final ThreadLocal<Boolean> DISCONNECTING = ThreadLocal.withInitial(() -> false);

    private DisconnectContext() {}

    public static boolean isDisconnecting() {
        return DISCONNECTING.get();
    }

    public static void set(boolean value) {
        DISCONNECTING.set(value);
    }
}
