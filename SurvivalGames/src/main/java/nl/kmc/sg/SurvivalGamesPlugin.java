package nl.kmc.sg;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.sg.commands.SGCommand;
import nl.kmc.sg.listeners.SGListener;
import nl.kmc.sg.managers.ArenaManager;
import nl.kmc.sg.managers.ChestStocker;
import nl.kmc.sg.managers.SurvivalGamesManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;

public final class SurvivalGamesPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "survival_games";

    private ArenaManager           arenaManager;
    private ChestStocker           chestStocker;
    private SurvivalGamesManagerV2 sgV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "Survival Games"; }
    @Override protected Material icon()       { return Material.BOW; }
    @Override protected int     minPlayers()  { return 4; }
    @Override protected String  description() { return "Hunger Games — loot, fight, and be the last one standing."; }
    @Override protected String  objective()   { return "Eliminate all other players to win."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+75 pts — Kill",
                "+5 pts — Survival bonus (per death)",
                "+500 pts — 1st Place"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager = new ArenaManager(this);
        chestStocker = new ChestStocker(this);
        sgV2         = new SurvivalGamesManagerV2(this, reg, stats);
        return sgV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> steps = new java.util.ArrayList<>();

        boolean worldSet = am.getWorld() != null;
        steps.add(nl.kmc.core.setup.SetupStep.action("Arena wereld",
                worldSet ? "✓ " + am.getWorld().getName() : "niet ingesteld", worldSet,
                org.bukkit.Material.GRASS_BLOCK,
                p -> { am.setWorld(p.getWorld());
                       p.sendMessage("§a[Setup] Arena-wereld gezet op " + p.getWorld().getName()); },
                "Klik: zet de arena-wereld op die van jou"));

        steps.add(nl.kmc.core.setup.SetupStep.action("Cornucopia",
                "midden van de map", true, org.bukkit.Material.CHEST,
                p -> { am.setCornucopia(p.getLocation());
                       p.sendMessage("§a[Setup] Cornucopia gezet op jouw locatie."); },
                "Klik: zet het cornucopia-midden op jouw locatie"));

        int pedestals = am.getSpawnLocations().size();
        steps.add(nl.kmc.core.setup.SetupStep.action("Pedestals (spawns)",
                pedestals + " (min. 2)", pedestals >= 2, org.bukkit.Material.QUARTZ_PILLAR,
                p -> { am.addPedestal(p.getLocation());
                       p.sendMessage("§a[Setup] Pedestal #" + am.getSpawnLocations().size() + " toegevoegd."); },
                "Klik: voeg een start-pedestal toe op jouw locatie"));

        return steps;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new SGCommand(this);
        var bukkitCmd = getCommand("survivalgames");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new SGListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (sgV2 != null && sgV2.isRunning()) sgV2.end();
        if (chestStocker != null) chestStocker.cancelTasks();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().warning("[SG] V1 auto-start fired but V1 GameManager has been removed.");
            if (kmcCore.getAutomationManager().isRunning())
                kmcCore.getAutomationManager().onGameEnd(null);
        }, 40L);
    }

    // ── Getters used by commands / listeners ──────────────────────────────────

    public KMCCore                 getKmcCore()       { return kmcCore; }
    public ArenaManager            getArenaManager()  { return arenaManager; }
    public ChestStocker            getChestStocker()  { return chestStocker; }
    public SurvivalGamesManagerV2  getGameManagerV2() { return sgV2; }
}
