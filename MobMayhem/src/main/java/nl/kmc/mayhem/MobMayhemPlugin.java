package nl.kmc.mayhem;

import nl.kmc.mayhem.commands.MobMayhemCommand;
import nl.kmc.mayhem.listeners.MobListener;
import nl.kmc.mayhem.managers.ArenaManager;
import nl.kmc.mayhem.managers.GameManager;
import nl.kmc.mayhem.managers.KitManager;
import nl.kmc.mayhem.managers.WorldCloner;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mob Mayhem — wave-based survival minigame.
 *
 * <p>Each team plays in their own cloned arena (template world is
 * cloned once per team at game start). 10 waves of escalating
 * difficulty, with random modifiers per wave (speed mobs, poison
 * touch, explosive on death, etc.). Boss waves at 7 (mini) and 10
 * (final). Wooden starter kit + scaling loot drops between waves.
 *
 * <p>Win condition: highest wave survived. Tiebreak: most mob kills.
 */
public final class MobMayhemPlugin extends JavaPlugin {

    private static MobMayhemPlugin instance;

    public static final String GAME_ID = "mob_mayhem";

    private KMCCore       kmcCore;
    private ArenaManager  arenaManager;
    private WorldCloner   worldCloner;
    private KitManager    kitManager;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling Mob Mayhem.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager = new ArenaManager(this);
        worldCloner  = new WorldCloner(this);
        kitManager   = new KitManager(this);
        gameManager  = new GameManager(this);

        var cmd = new MobMayhemCommand(this);
        var bukkitCmd = getCommand("mobmayhem");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new MobListener(this), this);

        // Auto-start hook — same pattern as QuakeCraft / Bingo / ParkourWarrior
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Mob Mayhem — preparing arenas...");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("Mob Mayhem auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("Mob Mayhem enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        if (worldCloner != null) worldCloner.disposeAll();
        getLogger().info("Mob Mayhem disabled.");
    }

    public static MobMayhemPlugin getInstance() { return instance; }

    public KMCCore       getKmcCore()       { return kmcCore; }
    public ArenaManager  getArenaManager()  { return arenaManager; }
    public WorldCloner   getWorldCloner()   { return worldCloner; }
    public KitManager    getKitManager()    { return kitManager; }
    public GameManager   getGameManager()   { return gameManager; }
}
