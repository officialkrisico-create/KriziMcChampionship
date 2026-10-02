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
            "+ptn per ronde-plaatsing (instelbaar via points.round-placement)",
            "+ptn team-bonus als je hele team een ronde uitfinisht",
            "+0 ptn — DNF (niet op tijd gefinisht)"
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
            s.add(nl.kmc.core.setup.SetupStep.action("Checkpoint toevoegen (optioneel)",
                    partial.checkpoints.size() + " stuks", true,
                    Material.LODESTONE,
                    p -> { mm.getPartial(wizardMapId).checkpoints.add(p.getLocation());
                           p.sendMessage("§a[Setup] Checkpoint #" + mm.getPartial(wizardMapId).checkpoints.size()
                                   + " toegevoegd — wie hier valt respawnt hier, niet bij start."); },
                    "Klik: voeg een checkpoint toe op jouw locatie (val je in de void, dan respawn je bij je laatste checkpoint)"));
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

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * /kmctest: map "kmctest" — a start platform, six floating stepping platforms (one with a
     * checkpoint), and a finish platform; a void floor below so falling respawns you.
     */
    @Override
    protected org.bukkit.Location buildTestArena(org.bukkit.entity.Player admin, org.bukkit.Location origin) {
        var w = origin.getWorld();
        var a = nl.kmc.game.api.TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        int x0 = cx - 30;

        nl.kmc.game.api.TestArenaKit.platform(w, x0, y, cz, 4, 4, Material.LIME_CONCRETE);
        var partial = mapManager.getPartial("kmctest");
        partial.displayName = "Test Map";
        partial.world = w;
        partial.startSpawns.clear();
        for (int i = 0; i < 8; i++)
            partial.startSpawns.add(nl.kmc.game.api.TestArenaKit.stand(w, x0 - 2 + (i / 4) * 2, y + 1, cz - 3 + (i % 4) * 2, -90));

        partial.checkpoints.clear();
        for (int i = 1; i <= 6; i++) {
            int px = x0 + 4 + i * 6;
            nl.kmc.game.api.TestArenaKit.platform(w, px, y, cz, 1, 1,
                    i == 3 ? Material.YELLOW_CONCRETE : Material.WHITE_CONCRETE);
            if (i == 3) partial.checkpoints.add(nl.kmc.game.api.TestArenaKit.stand(w, px, y + 1, cz, -90));
        }
        int fx = x0 + 4 + 7 * 6 + 3;
        nl.kmc.game.api.TestArenaKit.platform(w, fx, y, cz, 3, 3, Material.GOLD_BLOCK);
        partial.finishPos1 = new org.bukkit.Location(w, fx - 3, y + 1, cz - 3);
        partial.finishPos2 = new org.bukkit.Location(w, fx + 3, y + 3, cz + 3);
        partial.voidY = y - 12;
        mapManager.commitPartial("kmctest");
        return nl.kmc.game.api.TestArenaKit.stand(w, x0, y + 1, cz, -90);
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
