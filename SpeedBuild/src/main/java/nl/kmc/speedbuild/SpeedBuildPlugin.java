package nl.kmc.speedbuild;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.setup.SetupStep;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.speedbuild.game.SpeedBuildManager;
import nl.kmc.speedbuild.listener.BlockBreakListener;
import nl.kmc.speedbuild.listener.BlockPlaceListener;
import nl.kmc.speedbuild.listener.PlayerInteractListener;
import nl.kmc.speedbuild.schematic.SchematicLoader;
import nl.kmc.speedbuild.setup.ArenaConfig;
import nl.kmc.speedbuild.setup.SpeedBuildSetupCommand;
import nl.kmc.speedbuild.ui.InventoryButtons;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class SpeedBuildPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "speed_build";

    private SchematicLoader    loader;
    private ArenaConfig        arena;
    private InventoryButtons   buttons;
    private SpeedBuildManager  gameManager;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Speed Build"; }
    @Override protected Material icon()        { return Material.BRICKS; }
    @Override protected int      minPlayers()  { return 1; }
    @Override protected String   description() { return "Kopieer 10 schematics zo accuraat en snel mogelijk — objectief gescoord."; }
    @Override protected String   objective()   { return "Bouw alle 10 builds met maximale nauwkeurigheid."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+100 ptn — perfecte build (100% nauwkeurig)",
            "+tijdbonus — sneller dan par",
            "-2 ptn — per fout/missend blok");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        loader      = new SchematicLoader(kmcCore);
        arena       = new ArenaConfig(this, loader);
        buttons     = new InventoryButtons(this);
        gameManager = new SpeedBuildManager(this, reg, stats);
        return gameManager;
    }

    @Override
    protected List<SetupStep> extraSetupSteps(Player viewer) {
        if (arena == null) return List.of();
        var a = arena;
        List<SetupStep> s = new ArrayList<>();
        s.add(SetupStep.action("Build-anker", a.getAnchor() != null ? "✓ gezet" : "niet gezet", a.getAnchor() != null,
                Material.LODESTONE,
                p -> { a.setAnchor(p.getLocation()); p.sendMessage("§a[Setup] Build-anker gezet."); },
                "Klik: zet de min-hoek van het eerste bouwvak"));
        s.add(SetupStep.action("Spawn", a.getSpawn() != null ? "✓ gezet" : "niet gezet", a.getSpawn() != null,
                Material.RED_BED,
                p -> { a.setSpawn(p.getLocation()); p.sendMessage("§a[Setup] Spawn gezet."); },
                "Klik: zet waar spelers starten/terugkeren"));
        s.add(SetupStep.info("Builds", a.getBuilds().size() + "/10 ingesteld", a.getBuilds().size() == 10,
                Material.BRICKS));
        s.add(SetupStep.info("WorldEdit", loader.isWorldEditAvailable() ? "✓ beschikbaar" : "ontbreekt",
                loader.isWorldEditAvailable(), Material.WOODEN_AXE));
        s.add(SetupStep.info("Schematics-map", kmcCore.getSchematicManager().getSchematicFolder().getName(),
                true, Material.STRUCTURE_BLOCK));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new SpeedBuildSetupCommand(this);
        var bukkitCmd = getCommand("speedbuild");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerInteractListener(this), this);
        pm.registerEvents(new BlockPlaceListener(this), this);
        pm.registerEvents(new BlockBreakListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (gameManager != null && gameManager.isRunning()) gameManager.end();
    }

    @Override
    protected void onV1GameStart(String gameId) { /* no V1 path */ }

    // ── Getters ───────────────────────────────────────────────────────────────

    public SchematicLoader   getLoader()      { return loader; }
    public ArenaConfig       getArena()       { return arena; }
    public InventoryButtons  getButtons()     { return buttons; }
    public SpeedBuildManager getGameManager() { return gameManager; }
}
