package nl.kmc.core;

/**
 * Shared constants used across KMC modules and game plugins.
 *
 * <p>Centralising these prevents magic-string duplication — a single rename
 * here propagates to every consumer automatically.
 */
public final class KMCConstants {

    private KMCConstants() {}

    /**
     * The Bukkit plugin name of the V2 core plugin (KMCCoreV2).
     * Used by game plugins to locate the plugin via
     * {@code Bukkit.getPluginManager().getPlugin(KMCConstants.CORE_V2_PLUGIN_NAME)}.
     */
    public static final String CORE_V2_PLUGIN_NAME = "KMCCoreV2";
}
