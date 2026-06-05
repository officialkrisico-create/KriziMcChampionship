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
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> steps = new java.util.ArrayList<>();

        boolean worldSet = am.getArenaWorld() != null;
        steps.add(nl.kmc.core.setup.SetupStep.action("Arena wereld",
                worldSet ? "✓ " + am.getArenaWorld().getName() : "niet ingesteld", worldSet,
                Material.GRASS_BLOCK,
                p -> { am.setArenaWorld(p.getWorld());
                       p.sendMessage("§a[Setup] Arena-wereld gezet op " + p.getWorld().getName()); },
                "Klik: zet de arena-wereld op die van jou"));

        int spawns = am.getSpawns().size();
        steps.add(nl.kmc.core.setup.SetupStep.action("Spawns", spawns + " (min. 2)", spawns >= 2,
                Material.RED_BED,
                p -> { am.addSpawn(p.getLocation());
                       p.sendMessage("§a[Setup] Spawn #" + am.getSpawns().size() + " toegevoegd."); },
                "Klik: voeg een spawn toe op jouw locatie"));

        int powerups = am.getPowerupLocations().size();
        steps.add(nl.kmc.core.setup.SetupStep.action("Powerup-locaties", String.valueOf(powerups), powerups > 0,
                Material.ENDER_CHEST,
                p -> { am.addPowerupLocation("spot_" + (am.getPowerupLocations().size() + 1), p.getLocation());
                       p.sendMessage("§a[Setup] Powerup-locatie toegevoegd."); },
                "Klik: voeg een powerup-spawn toe"));

        int pads = am.getJumpPads().size();
        steps.add(nl.kmc.core.setup.SetupStep.action("Jump pads", String.valueOf(pads), true,
                Material.SLIME_BLOCK,
                p -> { am.addJumpPad(p.getLocation(),
                            getConfig().getDouble("jump-pad.default-height", 4.0),
                            getConfig().getDouble("jump-pad.forward", 0.4));
                       p.sendMessage("§a[Setup] Jump pad toegevoegd."); },
                "Klik: voeg een jump pad toe op jouw locatie"));

        return steps;
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
