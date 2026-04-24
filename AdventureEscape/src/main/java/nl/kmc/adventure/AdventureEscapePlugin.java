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
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adventure Escape plugin entry point.
 *
 * <p>FIX: Commands were being registered twice (once as /adventure,
 * once as /ae). Now /ae is just an alias defined in plugin.yml.
 */
public final class AdventureEscapePlugin extends JavaPlugin {

    private static AdventureEscapePlugin instance;

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

        // Single command registration — /ae is defined as an alias in plugin.yml
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
