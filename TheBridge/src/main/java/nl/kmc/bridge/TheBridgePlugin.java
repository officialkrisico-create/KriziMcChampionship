package nl.kmc.bridge;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.bridge.commands.BridgeCommand;
import nl.kmc.bridge.listeners.BridgeListener;
import nl.kmc.bridge.managers.ArenaManager;
import nl.kmc.bridge.managers.AssistManager;
import nl.kmc.bridge.managers.BlockTracker;
import nl.kmc.bridge.managers.BridgeGameManagerV2;
import nl.kmc.bridge.managers.KitManager;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class TheBridgePlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "the_bridge";

    private ArenaManager        arenaManager;
    private KitManager          kitManager;
    private BlockTracker        blockTracker;
    private AssistManager       assistManager;
    private BridgeGameManagerV2 bridgeGameManagerV2;

    /** The team currently being built in the Setup Dashboard wizard. */
    private String wizardTeamId;

    private static final org.bukkit.ChatColor[] WIZ_COLORS = {
            org.bukkit.ChatColor.RED, org.bukkit.ChatColor.BLUE, org.bukkit.ChatColor.GREEN,
            org.bukkit.ChatColor.YELLOW, org.bukkit.ChatColor.AQUA, org.bukkit.ChatColor.LIGHT_PURPLE,
            org.bukkit.ChatColor.GOLD, org.bukkit.ChatColor.WHITE };
    private static final Material[] WIZ_WOOLS = {
            Material.RED_WOOL, Material.BLUE_WOOL, Material.LIME_WOOL, Material.YELLOW_WOOL,
            Material.CYAN_WOOL, Material.MAGENTA_WOOL, Material.ORANGE_WOOL, Material.WHITE_WOOL };

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "The Bridge"; }
    @Override protected Material icon()        { return Material.BLUE_WOOL; }
    @Override protected int      minPlayers()  { return 4; }
    @Override protected String   description() { return "Bridge to the enemy goal and score! First team to 5 goals wins."; }
    @Override protected String   objective()   { return "Score goals by entering the opponent's goal hole."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+150 pts — Goal scored",
            "+50 pts — Kill",
            "+500 pts — 1st Place"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager        = new ArenaManager(this);
        kitManager          = new KitManager(this);
        blockTracker        = new BlockTracker(this);
        assistManager       = new AssistManager();
        bridgeGameManagerV2 = new BridgeGameManagerV2(this, reg, stats);
        return bridgeGameManagerV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();

        boolean worldSet = am.getWorld() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Arena wereld",
                worldSet ? "✓ " + am.getWorld().getName() : "niet ingesteld", worldSet,
                Material.GRASS_BLOCK,
                p -> { am.setWorld(p.getWorld()); p.sendMessage("§a[Setup] Wereld gezet op " + p.getWorld().getName()); },
                "Klik: zet de arena-wereld op die van jou"));

        s.add(nl.kmc.core.setup.SetupStep.action("Void Y-level", "jouw hoogte", false,
                Material.LIGHT_GRAY_STAINED_GLASS,
                p -> { am.setVoidYLevel(p.getLocation().getBlockY());
                       p.sendMessage("§a[Setup] Void-Y gezet op " + p.getLocation().getBlockY()); },
                "Klik: zet void-Y op jouw huidige hoogte"));

        int teams = am.getTeams().size();
        s.add(nl.kmc.core.setup.SetupStep.action("Teams (" + teams + ", min. 2)",
                "nieuw team starten", teams >= 2, Material.WHITE_BANNER,
                p -> getKmcCore().getChatInput().await(p, "Typ de naam van het nieuwe team:", name -> {
                    String id = name.toLowerCase().replace(' ', '_');
                    int idx = am.getTeams().size() % WIZ_COLORS.length;
                    var partial = am.getPartial(id);
                    partial.displayName  = name;
                    partial.chatColor    = WIZ_COLORS[idx];
                    partial.woolMaterial = WIZ_WOOLS[idx];
                    partial.spawn        = p.getLocation();
                    wizardTeamId = id;
                    p.sendMessage("§a[Setup] Team §e" + name + "§a gestart (kleur + spawn gezet).");
                    p.sendMessage("§7Open §e/kmcsetup → The Bridge §7en zet de 2 goal-hoeken, dan opslaan.");
                }),
                "Klik: start een nieuw team (typ de naam)"));

        // Goal-corner + commit steps for the team currently being built.
        if (wizardTeamId != null) {
            var partial = am.getPartial(wizardTeamId);
            s.add(nl.kmc.core.setup.SetupStep.action("Goal hoek 1 (" + wizardTeamId + ")",
                    partial.goalPos1 != null ? "✓ gezet" : "nog niet", partial.goalPos1 != null,
                    Material.TARGET,
                    p -> { am.getPartial(wizardTeamId).goalPos1 = p.getLocation();
                           p.sendMessage("§a[Setup] Goal-hoek 1 gezet."); },
                    "Klik: zet de eerste goal-hoek op jouw locatie"));
            s.add(nl.kmc.core.setup.SetupStep.action("Goal hoek 2 (" + wizardTeamId + ")",
                    partial.goalPos2 != null ? "✓ gezet" : "nog niet", partial.goalPos2 != null,
                    Material.TARGET,
                    p -> { am.getPartial(wizardTeamId).goalPos2 = p.getLocation();
                           p.sendMessage("§a[Setup] Goal-hoek 2 gezet."); },
                    "Klik: zet de tweede goal-hoek op jouw locatie"));
            s.add(nl.kmc.core.setup.SetupStep.action("Team opslaan",
                    partial.isComplete() ? "klaar om op te slaan" : "mist: " + partial.missing(), partial.isComplete(),
                    Material.LIME_DYE,
                    p -> { var pt = am.getPartial(wizardTeamId);
                           if (pt.isComplete()) { am.commitPartial(wizardTeamId);
                               p.sendMessage("§a[Setup] Team §e" + wizardTeamId + "§a opgeslagen!"); wizardTeamId = null; }
                           else p.sendMessage("§c[Setup] Team nog niet compleet — mist: " + pt.missing()); },
                    "Klik: sla het team op"));
        }
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new BridgeCommand(this);
        var bukkitCmd = getCommand("bridge");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new BridgeListener(this), this);
        getServer().getPluginManager().registerEvents(assistManager, this);
    }

    @Override
    protected void onGameDisable() {
        if (bridgeGameManagerV2 != null && bridgeGameManagerV2.isRunning()) bridgeGameManagerV2.end();
        if (blockTracker        != null) blockTracker.cancelTasks();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ArenaManager        getArenaManager()       { return arenaManager; }
    public KitManager          getKitManager()         { return kitManager; }
    public BlockTracker        getBlockTracker()       { return blockTracker; }
    public AssistManager       getAssistManager()      { return assistManager; }
    public BridgeGameManagerV2 getBridgeGameManagerV2(){ return bridgeGameManagerV2; }
}
