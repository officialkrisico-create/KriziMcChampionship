package nl.kmc.core.api;

/**
 * Static accessor so game plugins can retrieve the API without referencing
 * the plugin instance directly. Set by KMCCorePlugin on startup.
 */
public final class KMCApiProvider {

    private static KMCApi instance;

    private KMCApiProvider() {}

    public static void set(KMCApi api) {
        if (instance != null) throw new IllegalStateException("KMCApi already registered");
        instance = api;
    }

    public static void clear() { instance = null; }

    public static KMCApi get() {
        if (instance == null) throw new IllegalStateException("KMCApi not yet available");
        return instance;
    }

    public static boolean isAvailable() { return instance != null; }
}
