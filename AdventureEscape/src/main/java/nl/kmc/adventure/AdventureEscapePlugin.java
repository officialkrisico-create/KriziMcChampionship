package nl.kmc.adventure;

import nl.kmc.adventure.commands.AdventureCommand;
import nl.kmc.adventure.listeners.BlockStepListener;
import nl.kmc.adventure.listeners.LineCrossListener;
import nl.kmc.adventure.listeners.PlayerJoinQuitListener;
import nl.kmc.adventure.managers.ArenaManager;
import nl.kmc.adventure.managers.EffectBlockManager;
import nl.kmc.adventure.managers.RaceManager;
import nl.kmc.adventure.managers.RaceScoreboard;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adventure Escape plugin entry point.
 *
 * <p>NO PVP LISTENER — players can NOT damage each other during a race.
 *
 * <p>Auto-start hook: when KMCCore picks Adventure Escape as the next
 * game (via vote or random), this plugin's onGameStart hook fires and
 * runs startCountdown() automatically.
 */
public final class AdventureEscapePlugin extends JavaPlugin {

    private static AdventureEscapePlugin instance;

    public static final String GAME_ID = "adventure_escape";

    private KMCCore            kmcCore;
    private ArenaManager       arenaManager;
    private EffectBlockManager effectBlockManager;
    private RaceManager        raceManager;
    private RaceScoreboard     raceScoreboard;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling Adventure Escape.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager       = new ArenaManager(this);
        effectBlockManager = new EffectBlockManager(this);
        raceManager        = new RaceManager(this);
        raceScoreboard     = new RaceScoreboard(this);

        AdventureCommand cmd = new AdventureCommand(this);
        var bukkitCmd = getCommand("adventure");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        } else {
            getLogger().severe("Command 'adventure' not found in plugin.yml!");
        }

        getServer().getPluginManager().registerEvents(new BlockStepListener(this),    this);
        getServer().getPluginManager().registerEvents(new LineCrossListener(this),    this);
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);

        // Auto-start hook: KMCCore tells us when our game is picked
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore started '" + gameId + "' — launching race countdown.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = raceManager.startCountdown();
                if (error != null) {
                    getLogger().warning("Auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("Adventure Escape enabled!");
    }

    @Override
    public void onDisable() {
        if (raceManager != null) raceManager.forceStop();
        if (raceScoreboard != null) raceScoreboard.cleanup();
    }

    public static AdventureEscapePlugin getInstance() { return instance; }

    public KMCCore            getKmcCore()            { return kmcCore; }
    public ArenaManager       getArenaManager()       { return arenaManager; }
    public EffectBlockManager getEffectBlockManager() { return effectBlockManager; }
    public RaceManager        getRaceManager()        { return raceManager; }
    public RaceScoreboard     getRaceScoreboard()     { return raceScoreboard; }
}
