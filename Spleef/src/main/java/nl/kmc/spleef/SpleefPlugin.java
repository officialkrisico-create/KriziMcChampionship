package nl.kmc.spleef;

import nl.kmc.spleef.commands.SpleefCommand;
import nl.kmc.spleef.listeners.SpleefListener;
import nl.kmc.spleef.managers.ArenaManager;
import nl.kmc.spleef.managers.FloorManager;
import nl.kmc.spleef.managers.GameManager;
import nl.kmc.spleef.managers.PowerupSpawner;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spleef — classic break-the-floor minigame for KMC tournaments.
 *
 * <p>Players spawn on a snow floor with a shovel. Break floor under
 * opponents to drop them into the void. Last player (or last team)
 * standing wins. Snowball powerups spawn periodically — pick them
 * up and throw to knock opponents around.
 */
public final class SpleefPlugin extends JavaPlugin {

    private static SpleefPlugin instance;

    public static final String GAME_ID = "spleef_teams";

    private KMCCore         kmcCore;
    private ArenaManager    arenaManager;
    private FloorManager    floorManager;
    private PowerupSpawner  powerupSpawner;
    private GameManager     gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling Spleef.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager   = new ArenaManager(this);
        floorManager   = new FloorManager(this);
        powerupSpawner = new PowerupSpawner(this);
        gameManager    = new GameManager(this);

        var cmd = new SpleefCommand(this);
        var bukkitCmd = getCommand("spleef");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new SpleefListener(this), this);

        // Auto-start hook — same pattern as the other minigames
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Spleef — preparing floor...");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("Spleef auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("Spleef enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        if (floorManager != null) floorManager.cancelTasks();
        if (powerupSpawner != null) powerupSpawner.stop();
    }

    public static SpleefPlugin getInstance() { return instance; }

    public KMCCore        getKmcCore()        { return kmcCore; }
    public ArenaManager   getArenaManager()   { return arenaManager; }
    public FloorManager   getFloorManager()   { return floorManager; }
    public PowerupSpawner getPowerupSpawner() { return powerupSpawner; }
    public GameManager    getGameManager()    { return gameManager; }
}
