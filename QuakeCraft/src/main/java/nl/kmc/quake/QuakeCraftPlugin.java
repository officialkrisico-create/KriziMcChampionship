package nl.kmc.quake;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.quake.commands.QuakeCommand;
import nl.kmc.quake.listeners.WeaponListener;
import nl.kmc.quake.managers.ArenaManager;
import nl.kmc.quake.managers.PowerupSpawner;
import nl.kmc.quake.managers.QuakeCraftGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;

public final class QuakeCraftPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "quake_craft";

    private ArenaManager            arenaManager;
    private PowerupSpawner          powerupSpawner;
    private nl.kmc.quake.managers.MineManager  mineManager;
    private nl.kmc.quake.managers.DecoyManager decoyManager;
    private QuakeCraftGameManagerV2 quakeV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "QuakeCraft"; }
    @Override protected Material icon()       { return Material.WOODEN_HOE; }
    @Override protected int     minPlayers()  { return 4; }
    @Override protected String  description() { return "Fast-paced railgun FPS — first to the kill target wins."; }
    @Override protected String  objective()   { return "Reach " + getConfig().getInt("game.kill-target", 25) + " kills first."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+10 pts — Kill",
                "+25 pts — Revenge kill",
                "+50 pts — Win game"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager   = new ArenaManager(this);
        powerupSpawner = new PowerupSpawner(this);
        mineManager    = new nl.kmc.quake.managers.MineManager(this);
        decoyManager   = new nl.kmc.quake.managers.DecoyManager(this);
        quakeV2        = new QuakeCraftGameManagerV2(this, reg, stats);
        return quakeV2;
    }

    @Override
    protected void onGameEnable() {
        QuakeCommand cmd = new QuakeCommand(this);
        var bukkitCmd = getCommand("quakecraft");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new WeaponListener(this), this);
        getServer().getPluginManager().registerEvents(
                new nl.kmc.quake.listeners.JumpPadListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (quakeV2 != null && quakeV2.isRunning()) quakeV2.end();
        if (powerupSpawner != null) powerupSpawner.stop();
        if (mineManager != null) mineManager.stop();
        if (decoyManager != null) decoyManager.stop();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().warning("[QuakeCraft] V1 auto-start fired but V1 GameManager has been removed.");
            if (kmcCore.getAutomationManager().isRunning())
                kmcCore.getAutomationManager().onGameEnd(null);
        }, 40L);
    }

    // ── Getters used by commands / listeners ──────────────────────────────────

    public KMCCore                 getKmcCore()        { return kmcCore; }
    public ArenaManager            getArenaManager()   { return arenaManager; }
    public PowerupSpawner          getPowerupSpawner() { return powerupSpawner; }
    public nl.kmc.quake.managers.MineManager  getMineManager()  { return mineManager; }
    public nl.kmc.quake.managers.DecoyManager getDecoyManager() { return decoyManager; }
    public QuakeCraftGameManagerV2 getGameManagerV2()  { return quakeV2; }
}
