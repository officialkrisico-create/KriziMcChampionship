package nl.kmc.mayhem;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.mayhem.commands.MobMayhemCommand;
import nl.kmc.mayhem.listeners.MobListener;
import nl.kmc.mayhem.managers.ArenaManager;
import nl.kmc.mayhem.managers.KitManager;
import nl.kmc.mayhem.managers.MobMayhemGameManagerV2;
import nl.kmc.mayhem.managers.WorldCloner;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;

public final class MobMayhemPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "mob_mayhem";

    private ArenaManager           arenaManager;
    private WorldCloner            worldCloner;
    private KitManager             kitManager;
    private MobMayhemGameManagerV2 mobMayhemV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "Mob Mayhem"; }
    @Override protected Material icon()       { return Material.ZOMBIE_HEAD; }
    @Override protected int     minPlayers()  { return 4; }
    @Override protected String  description() { return "Survive 10 escalating waves of mobs — each team in their own arena."; }
    @Override protected String  objective()   { return "Survive more waves than the other teams."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+pts — Per mob kill (scales by wave)",
                "+100 pts — Wave cleared",
                "+500 pts — 1st Place (most waves)"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager = new ArenaManager(this);
        worldCloner  = new WorldCloner(this);
        kitManager   = new KitManager(this);
        mobMayhemV2  = new MobMayhemGameManagerV2(this, reg, stats);
        return mobMayhemV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();
        s.add(nl.kmc.core.setup.SetupStep.action("Speler-spawn", "klik op je locatie", false,
                org.bukkit.Material.COMPASS,
                p -> { am.setPlayerSpawn(p.getLocation()); p.sendMessage("§a[Setup] Speler-spawn gezet."); },
                "Klik: zet de speler-spawn op jouw locatie"));
        int mobs = am.getMobSpawnCount();
        s.add(nl.kmc.core.setup.SetupStep.action("Mob spawns", mobs + " stuks", mobs > 0,
                org.bukkit.Material.ZOMBIE_HEAD,
                p -> { am.addMobSpawn(p.getLocation());
                       p.sendMessage("§a[Setup] Mob-spawn #" + am.getMobSpawnCount() + " toegevoegd."); },
                "Klik: voeg een mob-spawn toe op jouw locatie"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new MobMayhemCommand(this);
        var bukkitCmd = getCommand("mobmayhem");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new MobListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (mobMayhemV2 != null && mobMayhemV2.isRunning()) mobMayhemV2.end();
        if (worldCloner != null) worldCloner.disposeAll();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().warning("[MobMayhem] V1 auto-start fired but V1 GameManager has been removed.");
            if (kmcCore.getAutomationManager().isRunning())
                kmcCore.getAutomationManager().onGameEnd(null);
        }, 40L);
    }

    // ── Getters used by commands / listeners ──────────────────────────────────

    public KMCCore                getKmcCore()       { return kmcCore; }
    public ArenaManager           getArenaManager()  { return arenaManager; }
    public WorldCloner            getWorldCloner()   { return worldCloner; }
    public KitManager             getKitManager()    { return kitManager; }
    public MobMayhemGameManagerV2 getGameManagerV2() { return mobMayhemV2; }
}
