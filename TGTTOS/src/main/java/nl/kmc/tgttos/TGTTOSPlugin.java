package nl.kmc.tgttos;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.tgttos.commands.TGTTOSCommand;
import nl.kmc.tgttos.listeners.MovementListener;
import nl.kmc.tgttos.managers.MapManager;
import nl.kmc.tgttos.managers.TGTTOSGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class TGTTOSPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "tgttos";

    private MapManager          mapManager;
    private TGTTOSGameManagerV2 tgttosGameManagerV2;

    /** The map currently being built in the Setup Dashboard wizard. */
    private String wizardMapId;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "TGTTOS"; }
    @Override protected Material icon()        { return Material.DIRT_PATH; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Race van de ene kant van de map naar de andere op wisselende maps."; }
    @Override protected String   objective()   { return "Finish elke ronde zo snel mogelijk."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+100 ptn — 1e finish in ronde",
            "+75 ptn — 2e finish",
            "+5 ptn — DNF-troostprijs"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        mapManager          = new MapManager(this);
        tgttosGameManagerV2 = new TGTTOSGameManagerV2(this, reg, stats);
        return tgttosGameManagerV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (mapManager == null) return java.util.List.of();
        var mm = mapManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();

        int maps = mm.getMaps().size();
        s.add(nl.kmc.core.setup.SetupStep.action("Maps (" + maps + ", min. 1)",
                "nieuwe map starten", maps >= 1, Material.FILLED_MAP,
                p -> getKmcCore().getChatInput().await(p, "Typ de naam van de nieuwe map:", name -> {
                    String id = name.toLowerCase().replace(' ', '_');
                    var partial = mm.getPartial(id);
                    partial.displayName = name;
                    partial.world       = p.getWorld();
                    wizardMapId = id;
                    p.sendMessage("§a[Setup] Map §e" + name + "§a gestart (wereld gezet).");
                    p.sendMessage("§7Open §e/kmcsetup → TGTTOS §7en voeg spawns + finish-hoeken toe.");
                }),
                "Klik: start een nieuwe map (typ de naam)"));

        if (wizardMapId != null) {
            var partial = mm.getPartial(wizardMapId);
            s.add(nl.kmc.core.setup.SetupStep.action("Start-spawn (" + wizardMapId + ")",
                    partial.startSpawns.size() + " spawns", !partial.startSpawns.isEmpty(),
                    Material.RED_BED,
                    p -> { mm.getPartial(wizardMapId).startSpawns.add(p.getLocation());
                           p.sendMessage("§a[Setup] Start-spawn toegevoegd (" + mm.getPartial(wizardMapId).startSpawns.size() + ")."); },
                    "Klik: voeg een start-spawn toe op jouw locatie"));
            s.add(nl.kmc.core.setup.SetupStep.action("Finish hoek 1",
                    partial.finishPos1 != null ? "✓ gezet" : "nog niet", partial.finishPos1 != null,
                    Material.TARGET,
                    p -> { mm.getPartial(wizardMapId).finishPos1 = p.getLocation();
                           p.sendMessage("§a[Setup] Finish-hoek 1 gezet."); },
                    "Klik: zet de eerste finish-hoek"));
            s.add(nl.kmc.core.setup.SetupStep.action("Finish hoek 2",
                    partial.finishPos2 != null ? "✓ gezet" : "nog niet", partial.finishPos2 != null,
                    Material.TARGET,
                    p -> { mm.getPartial(wizardMapId).finishPos2 = p.getLocation();
                           p.sendMessage("§a[Setup] Finish-hoek 2 gezet."); },
                    "Klik: zet de tweede finish-hoek"));
            s.add(nl.kmc.core.setup.SetupStep.action("Map opslaan",
                    partial.isComplete() ? "klaar om op te slaan" : "mist: " + partial.missing(), partial.isComplete(),
                    Material.LIME_DYE,
                    p -> { var pm = mm.getPartial(wizardMapId);
                           if (pm.isComplete()) { mm.commitPartial(wizardMapId);
                               p.sendMessage("§a[Setup] Map §e" + wizardMapId + "§a opgeslagen!"); wizardMapId = null; }
                           else p.sendMessage("§c[Setup] Map nog niet compleet — mist: " + pm.missing()); },
                    "Klik: sla de map op"));
        }
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new TGTTOSCommand(this);
        var bukkitCmd = getCommand("tgttos");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (tgttosGameManagerV2 != null && tgttosGameManagerV2.isRunning()) tgttosGameManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public MapManager          getMapManager()         { return mapManager; }
    public TGTTOSGameManagerV2 getTGTTOSGameManagerV2(){ return tgttosGameManagerV2; }
}
