package nl.kmc.parkour;

import nl.kmc.parkour.commands.ParkourCommand;
import nl.kmc.parkour.listeners.MovementListener;
import nl.kmc.parkour.managers.CourseManager;
import nl.kmc.parkour.managers.GameManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Parkour Warrior — race-through-checkpoints minigame for KMC tournaments.
 *
 * <p>Players race a multi-stage parkour course built into the existing
 * tournament world. Region-box checkpoints award points; harder = more.
 * Players respawn at last checkpoint on death/fall. Skip mechanic
 * unlocks after 3 fails on the same stage (no points for skipped).
 *
 * <p>Both solo and team scoring — individual placement gets bonus,
 * team totals naturally aggregate via KMCCore.
 */
public final class ParkourWarriorPlugin extends JavaPlugin {

    private static ParkourWarriorPlugin instance;

    public static final String GAME_ID = "parkour_warrior";

    private KMCCore       kmcCore;
    private CourseManager courseManager;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling ParkourWarrior.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        courseManager = new CourseManager(this);
        gameManager   = new GameManager(this);

        ParkourCommand cmd = new ParkourCommand(this);
        var bukkitCmd = getCommand("parkourwarrior");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new MovementListener(this), this);

        // Auto-start hook
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Parkour Warrior — launching countdown.");
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

        getLogger().info("ParkourWarrior enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        getLogger().info("ParkourWarrior disabled.");
    }

    public static ParkourWarriorPlugin getInstance() { return instance; }

    public KMCCore       getKmcCore()       { return kmcCore; }
    public CourseManager getCourseManager() { return courseManager; }
    public GameManager   getGameManager()   { return gameManager; }
}
