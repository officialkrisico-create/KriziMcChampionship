package nl.kmc.kmccore.module;

import nl.kmc.core.KMCConstants;
import nl.kmc.core.KMCCorePlugin;
import nl.kmc.core.api.KMCApiImpl;
import nl.kmc.core.api.KMCApiProvider;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.achievements.AchievementManager;
import nl.kmc.kmccore.gui.StatsGUI;
import nl.kmc.kmccore.history.TournamentHistoryManager;
import nl.kmc.kmccore.hof.HoFNpcManager;
import nl.kmc.stats.service.AchievementService;

/**
 * Phase 2 engagement layer: achievements, history, Hall of Fame NPC, and
 * the per-player stats GUI.
 *
 * <p>Handles the V1/V2 achievement system selector and the optional V2
 * wiring through {@code KMCCoreV2}. Depends on {@link CoreModule} and
 * {@link InfraModule} being enabled first.
 */
public class EngagementModule implements PluginModule {

    private final KMCCore plugin;

    /** V1 achievement system — only created when {@code achievement-system=v1}. */
    private AchievementManager       achievementManager;
    private TournamentHistoryManager tournamentHistoryManager;
    private HoFNpcManager            hoFNpcManager;
    private StatsGUI                 statsGUI;
    /** V2 achievement service — only wired when {@code achievement-system=v2} and KMCCoreV2 present. */
    private AchievementService       achievementServiceV2;

    public EngagementModule(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public void enable() {
        boolean useV2 = "v2".equalsIgnoreCase(
                plugin.getConfig().getString("settings.achievement-system", "v1"));

        if (!useV2) {
            achievementManager = new AchievementManager(plugin);
            plugin.getLogger().info("[KMCCore] Achievement system: V1 (legacy).");
        } else {
            plugin.getLogger().info("[KMCCore] Achievement system: V2 — V1 AchievementManager skipped.");
        }

        tournamentHistoryManager = new TournamentHistoryManager(plugin);
        statsGUI                 = new StatsGUI(plugin);
        hoFNpcManager            = new HoFNpcManager(plugin);

        // Wire V2 achievement service if KMCCoreV2 is present
        KMCCorePlugin coreV2 = (KMCCorePlugin) plugin.getServer().getPluginManager()
                .getPlugin(KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 != null && useV2) {
            achievementServiceV2 = new AchievementService(plugin, coreV2.getStorage());
            achievementServiceV2.setEventNumber(coreV2.getTournamentService().getEventNumber());
            plugin.getServer().getPluginManager().registerEvents(achievementServiceV2, plugin);
            if (KMCApiProvider.get() instanceof KMCApiImpl impl)
                impl.setAchievementApi(achievementServiceV2);
            plugin.getLogger().info("[KMCCore] Achievement V2 service enabled — "
                    + achievementServiceV2.getAll().size() + " definition(s) loaded.");
        } else if (useV2) {
            plugin.getLogger().warning(
                    "[KMCCore] achievement-system=v2 but KMCCoreV2 not found — achievements disabled!");
        }
    }

    /** Starts background tasks that require all managers to be fully wired. */
    public void startBackgroundTasks() {
        hoFNpcManager.start();
    }

    @Override
    public void disable() {
        if (hoFNpcManager != null) hoFNpcManager.stop();
        // achievementManager, statsGUI, tournamentHistoryManager: no shutdown needed
    }

    public AchievementManager       getAchievementManager()       { return achievementManager; }
    public TournamentHistoryManager getTournamentHistoryManager() { return tournamentHistoryManager; }
    public HoFNpcManager            getHoFNpcManager()            { return hoFNpcManager; }
    public StatsGUI                 getStatsGUI()                 { return statsGUI; }
    public AchievementService       getAchievementServiceV2()     { return achievementServiceV2; }
}
