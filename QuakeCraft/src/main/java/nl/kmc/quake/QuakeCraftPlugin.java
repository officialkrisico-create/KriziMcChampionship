package nl.kmc.quake;

import nl.kmc.quake.commands.QuakeCommand;
import nl.kmc.quake.managers.ArenaManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * QuakeCraft — fast-paced railgun deathmatch for KMC tournaments.
 *
 * <p><b>Status:</b> patch 9 — foundation. Models, config, arena setup
 * in place. Game loop and weapon mechanics arrive in patch 10 once
 * design questions are answered (railgun mechanic, FFA vs teams,
 * win condition).
 */
public final class QuakeCraftPlugin extends JavaPlugin {

    private static QuakeCraftPlugin instance;

    public static final String GAME_ID = "quake_craft";

    private KMCCore       kmcCore;
    private ArenaManager  arenaManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling QuakeCraft.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager = new ArenaManager(this);

        QuakeCommand cmd = new QuakeCommand(this);
        var bukkitCmd = getCommand("quakecraft");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        // Auto-start hook (same pattern as Adventure Escape)
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked QuakeCraft — countdown not yet implemented.");
            // TODO patch 10: gameManager.startCountdown();
        });

        getLogger().info("QuakeCraft (foundation) enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("QuakeCraft disabled.");
    }

    public static QuakeCraftPlugin getInstance() { return instance; }

    public KMCCore       getKmcCore()      { return kmcCore; }
    public ArenaManager  getArenaManager() { return arenaManager; }
}
