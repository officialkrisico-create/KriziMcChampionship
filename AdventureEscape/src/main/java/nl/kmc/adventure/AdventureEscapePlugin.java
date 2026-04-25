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
 * Adventure Escape — Ace Race style minigame plugin.
 *
 * <p>Depends on KMCCore for team/points integration.
 *
 * <p><b>Auto-start integration:</b> registers an onGameStart hook with
 * KMCCore. When the automation/voting picks "adventure_escape" as the
 * next game, KMCCore fires the hook and we run startCountdown()
 * automatically — no admin needed.
 */
public final class AdventureEscapePlugin extends JavaPlugin {

    private static AdventureEscapePlugin instance;

    /** Game id that KMCCore uses to identify this minigame. */
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

        // Init managers
        arenaManager       = new ArenaManager(this);
        effectBlockManager = new EffectBlockManager(this);
        raceManager        = new RaceManager(this);
        raceScoreboard     = new RaceScoreboard(this);

        // Commands
        var cmd = new AdventureCommand(this);
        getCommand("adventure").setExecutor(cmd);
        getCommand("adventure").setTabCompleter(cmd);
        getCommand("ae").setExecutor(cmd);
        getCommand("ae").setTabCompleter(cmd);

        // Listeners
        getServer().getPluginManager().registerEvents(new BlockStepListener(this),    this);
        getServer().getPluginManager().registerEvents(new LineCrossListener(this),    this);
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);

        // -----------------------------------------------------------
        // Auto-start hook — RESTORED (was missing in this build)
        // -----------------------------------------------------------
        // KMCCore fires fireGameStart(gameId) when GameManager.startGame
        // is called. We register a listener that catches our id and runs
        // the race countdown after a brief delay (gives KMCCore time to
        // finish announcements and teleport players to the right world).
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Adventure Escape — launching countdown.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = raceManager.startCountdown();
                if (error != null) {
                    getLogger().warning("Adventure Escape auto-start failed: " + error);
                    // Notify automation so it doesn't get stuck on a failed game
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);  // 2-second delay — same pattern as QuakeCraft / Bingo / Parkour
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
