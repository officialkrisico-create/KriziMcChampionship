package nl.kmc.bingo;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.bingo.commands.BingoCommand;
import nl.kmc.bingo.listeners.InventoryListener;
import nl.kmc.bingo.managers.BingoGameManagerV2;
import nl.kmc.bingo.managers.CardGenerator;
import nl.kmc.bingo.managers.WorldManager;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class BingoPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "bingo_teams";

    private CardGenerator      cardGenerator;
    private WorldManager       worldManager;
    private BingoGameManagerV2 bingoManagerV2;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String      gameId()      { return GAME_ID; }
    @Override protected String      displayName() { return "Bingo"; }
    @Override protected Material    icon()        { return Material.PAPER; }
    @Override protected int         minPlayers()  { return 4; }
    @Override protected String      description() { return "Verzamel items om je bingokaart te voltooien vóór de andere teams!"; }
    @Override protected String      objective()   { return "Voltooi als eerste een volledige lijn (rij, kolom of diagonaal)."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+25 ptn — Vakje voltooid",
            "+100 ptn — Lijn voltooid",
            "+500 ptn — 1e plaats"
        );
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        cardGenerator = new CardGenerator(this);
        worldManager  = new WorldManager(this);
        bingoManagerV2 = new BingoGameManagerV2(this, reg, stats);
        return bingoManagerV2;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (worldManager == null) return java.util.List.of();
        var wm = worldManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();

        boolean tmpl = wm.templateExists();
        s.add(nl.kmc.core.setup.SetupStep.action("Template wereld",
                tmpl ? "✓ " + wm.getTemplateWorldName() : "ontbreekt", tmpl,
                org.bukkit.Material.GRASS_BLOCK,
                p -> { wm.setTemplateWorld(p.getWorld().getName());
                       p.sendMessage("§a[Setup] Template-wereld gezet op " + p.getWorld().getName()); },
                "Klik: gebruik je huidige wereld als bingo-template"));

        // Don't force-load the world here — a spawn is available whenever the
        // template exists (its spawn is the default) or a custom spawn is set.
        boolean spawnSet = getConfig().isConfigurationSection("world.default-spawn") || wm.templateExists();
        s.add(nl.kmc.core.setup.SetupStep.action("Spawn",
                spawnSet ? "✓ ingesteld" : "niet ingesteld", spawnSet, org.bukkit.Material.RED_BED,
                p -> { var loc = p.getLocation();
                       getConfig().set("world.default-spawn.x", loc.getX());
                       getConfig().set("world.default-spawn.y", loc.getY());
                       getConfig().set("world.default-spawn.z", loc.getZ());
                       getConfig().set("world.default-spawn.yaw", loc.getYaw());
                       getConfig().set("world.default-spawn.pitch", loc.getPitch());
                       saveConfig();
                       p.sendMessage("§a[Setup] Spawn opgeslagen op jouw locatie (in de template-wereld)."); },
                "Klik: zet de spawn op jouw locatie (sta in de template-wereld)"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        if (cardGenerator == null) {
            // V1-only path: initialise supporting managers here
            cardGenerator = new CardGenerator(this);
            worldManager  = new WorldManager(this);
        }

        BingoCommand cmd = new BingoCommand(this);
        var bukkitCmd = getCommand("bingo");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (bingoManagerV2 != null && bingoManagerV2.isRunning()) bingoManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // V1 path: no dedicated V1 GameManager anymore — log a warning
        getLogger().warning("[Bingo] V1 game-start signal received but V1 GameManager has been removed. Use V2.");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public CardGenerator      getCardGenerator()  { return cardGenerator; }
    public WorldManager       getWorldManager()   { return worldManager; }
    /** Returns the V2 manager cast to BingoGameManagerV2, or null. */
    public BingoGameManagerV2 getBingoManagerV2() { return bingoManagerV2; }
}
