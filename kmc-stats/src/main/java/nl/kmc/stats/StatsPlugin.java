package nl.kmc.stats;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loader plugin for kmc-stats.
 * No logic — exists only so Paper loads this JAR and makes its classes
 * (StatisticsService, AchievementService, etc.) available to game plugins.
 */
public final class StatsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("kmc-stats loaded.");
    }
}
