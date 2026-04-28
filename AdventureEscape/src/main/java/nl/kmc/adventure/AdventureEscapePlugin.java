package nl.kmc.adventure;

import nl.kmc.adventure.commands.AdventureCommand;
import nl.kmc.adventure.listeners.BlockStepListener;
import nl.kmc.adventure.listeners.LineCrossListener;
import nl.kmc.adventure.listeners.OutOfBoundsListener;
import nl.kmc.adventure.listeners.PlayerJoinQuitListener;
import nl.kmc.adventure.managers.ArenaManager;
import nl.kmc.adventure.managers.CheckpointManager;
import nl.kmc.adventure.managers.EffectBlockManager;
import nl.kmc.adventure.managers.RaceManager;
import nl.kmc.adventure.managers.RaceScoreboard;
import nl.kmc.adventure.managers.TrialKeyManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adventure Escape — Ace Race style minigame plugin.
 *
 * <p>Depends on KMCCore for team/points integration.
 */
public final class AdventureEscapePlugin extends JavaPlugin {

    private static AdventureEscapePlugin instance;

    private KMCCore             kmcCore;
    private ArenaManager        arenaManager;
    private EffectBlockManager  effectBlockManager;
    private CheckpointManager   checkpointManager;
    private RaceManager         raceManager;
    private RaceScoreboard      raceScoreboard;
    private TrialKeyManager     trialKeyManager;
    private OutOfBoundsListener oobListener;

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

        // Init managers (ArenaManager first — others depend on it)
        arenaManager        = new ArenaManager(this);
        effectBlockManager  = new EffectBlockManager(this);
        checkpointManager   = new CheckpointManager(this);
        raceManager         = new RaceManager(this);
        raceScoreboard      = new RaceScoreboard(this);
        trialKeyManager     = new TrialKeyManager(this);
        oobListener         = new OutOfBoundsListener(this);

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
        getServer().getPluginManager().registerEvents(trialKeyManager,                this);

        // Start OOB tick
        oobListener.start();

        getLogger().info("Adventure Escape enabled!");
    }

    @Override
    public void onDisable() {
        if (oobListener != null)    oobListener.stop();
        if (raceManager != null)    raceManager.forceStop();
        if (raceScoreboard != null) raceScoreboard.cleanup();
    }

    public static AdventureEscapePlugin getInstance() { return instance; }

    public KMCCore             getKmcCore()            { return kmcCore; }
    public ArenaManager        getArenaManager()       { return arenaManager; }
    public EffectBlockManager  getEffectBlockManager() { return effectBlockManager; }
    public CheckpointManager   getCheckpointManager()  { return checkpointManager; }
    public RaceManager         getRaceManager()        { return raceManager; }
    public RaceScoreboard      getRaceScoreboard()     { return raceScoreboard; }
    public TrialKeyManager     getTrialKeyManager()    { return trialKeyManager; }
}
