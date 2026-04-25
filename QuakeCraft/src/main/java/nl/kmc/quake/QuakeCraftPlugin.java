package nl.kmc.quake;

import nl.kmc.quake.commands.QuakeCommand;
import nl.kmc.quake.listeners.WeaponListener;
import nl.kmc.quake.managers.ArenaManager;
import nl.kmc.quake.managers.GameManager;
import nl.kmc.quake.managers.PowerupSpawner;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * QuakeCraft — fast-paced railgun deathmatch for KMC tournaments.
 *
 * <p>Hoe-based weapons. Right-click to fire. Teams play together,
 * first team to 25 collective kills wins, or whoever has the most
 * kills when the 10-minute timer runs out.
 *
 * <p>Weapon mapping:
 * <ul>
 *   <li>Wooden hoe = Railgun (∞ ammo, base weapon)</li>
 *   <li>Iron hoe = Shotgun (5 uses, 5-pellet spread)</li>
 *   <li>Netherite hoe = Sniper (3 uses, long range, tracer)</li>
 *   <li>Gold hoe = Machine gun (25 uses, fast cooldown)</li>
 *   <li>Bone = Grenade (1 use, throwable, AOE)</li>
 *   <li>Sugar = Speed II buff (15 sec, consumed on pickup)</li>
 * </ul>
 */
public final class QuakeCraftPlugin extends JavaPlugin {

    private static QuakeCraftPlugin instance;

    public static final String GAME_ID = "quake_craft";

    private KMCCore        kmcCore;
    private ArenaManager   arenaManager;
    private GameManager    gameManager;
    private PowerupSpawner powerupSpawner;

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

        arenaManager   = new ArenaManager(this);
        gameManager    = new GameManager(this);
        powerupSpawner = new PowerupSpawner(this);

        QuakeCommand cmd = new QuakeCommand(this);
        var bukkitCmd = getCommand("quakecraft");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new WeaponListener(this), this);

        // Auto-start hook: KMCCore picks QuakeCraft → start countdown
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked QuakeCraft — launching countdown.");
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

        getLogger().info("QuakeCraft enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        if (powerupSpawner != null) powerupSpawner.stop();
        getLogger().info("QuakeCraft disabled.");
    }

    public static QuakeCraftPlugin getInstance() { return instance; }

    public KMCCore        getKmcCore()        { return kmcCore; }
    public ArenaManager   getArenaManager()   { return arenaManager; }
    public GameManager    getGameManager()    { return gameManager; }
    public PowerupSpawner getPowerupSpawner() { return powerupSpawner; }
}
