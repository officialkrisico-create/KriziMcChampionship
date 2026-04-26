package nl.kmc.elytra;

import nl.kmc.elytra.commands.ElytraCommand;
import nl.kmc.elytra.listeners.MovementListener;
import nl.kmc.elytra.managers.CourseManager;
import nl.kmc.elytra.managers.GameManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Elytra Endrium — race through hoops with elytra. Boost hoops give
 * velocity kicks. Crash = brief spectator + auto-launch back to last
 * checkpoint. First to finish wins. Both individual + team scoring.
 */
public final class ElytraEndriumPlugin extends JavaPlugin {

    private static ElytraEndriumPlugin instance;

    public static final String GAME_ID = "elytra_endrium";

    private KMCCore       kmcCore;
    private CourseManager courseManager;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling ElytraEndrium.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        courseManager = new CourseManager(this);
        gameManager   = new GameManager(this);

        var cmd = new ElytraCommand(this);
        var bukkitCmd = getCommand("elytraendrium");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new MovementListener(this), this);

        // Auto-start hook — same pattern as the other minigames
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Elytra Endrium — launching countdown.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startCountdown();
                if (error != null) {
                    getLogger().warning("Auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("Elytra Endrium enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
    }

    public static ElytraEndriumPlugin getInstance() { return instance; }

    public KMCCore       getKmcCore()       { return kmcCore; }
    public CourseManager getCourseManager() { return courseManager; }
    public GameManager   getGameManager()   { return gameManager; }
}
