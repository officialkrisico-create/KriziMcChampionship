package nl.kmc.tnttag;

import nl.kmc.tnttag.commands.TagCommand;
import nl.kmc.tnttag.listeners.TagListener;
import nl.kmc.tnttag.managers.ArenaManager;
import nl.kmc.tnttag.managers.GameManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TNT Tag — pass the bomb minigame.
 *
 * <p>One or more random players are "it" with a TNT helmet + glow.
 * They run around tagging others by proximity. When the round timer
 * hits zero, all current "it" players explode + are eliminated.
 * Repeat until one survivor remains.
 */
public final class TNTTagPlugin extends JavaPlugin {

    private static TNTTagPlugin instance;

    public static final String GAME_ID = "tnt_tag";

    private KMCCore       kmcCore;
    private ArenaManager  arenaManager;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling TNT Tag.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager = new ArenaManager(this);
        gameManager  = new GameManager(this);

        var cmd = new TagCommand(this);
        var bukkitCmd = getCommand("tnttag");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new TagListener(this), this);

        // Auto-start hook
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked TNT Tag — starting countdown.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("TNT Tag auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("TNT Tag enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
    }

    public static TNTTagPlugin getInstance() { return instance; }

    public KMCCore      getKmcCore()      { return kmcCore; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public GameManager  getGameManager()  { return gameManager; }
}
