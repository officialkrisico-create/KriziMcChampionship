package nl.kmc.skywars;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.skywars.commands.SkyWarsCommand;
import nl.kmc.skywars.listeners.SkyWarsListener;
import nl.kmc.skywars.managers.ArenaManager;
import nl.kmc.skywars.managers.ChestStocker;
import nl.kmc.skywars.managers.SkyWarsGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

/**
 * Team SkyWars — PvP minigame on floating islands.
 * Registered with both V1 KMCCore (backward compat) and V2 tournament engine.
 */
public final class SkyWarsPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "team_skywars";

    private ArenaManager         arenaManager;
    private ChestStocker         chestStocker;
    private SkyWarsGameManagerV2 skyWarsGameManagerV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "Team SkyWars"; }
    @Override protected Material icon()       { return Material.ENDER_CHEST; }
    @Override protected int     minPlayers()  { return 4; }
    @Override protected String  description() { return "PvP op zwevende eilanden — laatste team dat overblijft wint."; }
    @Override protected String  objective()   { return "Elimineer alle andere teams van hun eilanden."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+50 ptn — Kill",
            "+500 ptn — 1e plaats",
            "+5 ptn — Overlevingsbonus (per vijand dood)"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager         = new ArenaManager(this);
        chestStocker         = new ChestStocker(this);
        skyWarsGameManagerV2 = new SkyWarsGameManagerV2(this, reg, stats);
        return skyWarsGameManagerV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();

        boolean worldSet = am.getWorld() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Arena wereld",
                worldSet ? "✓ " + am.getWorld().getName() : "niet ingesteld", worldSet,
                org.bukkit.Material.GRASS_BLOCK,
                p -> { am.setWorld(p.getWorld()); p.sendMessage("§a[Setup] Wereld gezet op " + p.getWorld().getName()); },
                "Klik: zet de arena-wereld op die van jou"));

        boolean midSet = am.getMiddleSpawn() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Midden",
                midSet ? "✓ ingesteld" : "niet ingesteld", midSet, org.bukkit.Material.BEACON,
                p -> { am.setMiddleSpawn(p.getLocation()); p.sendMessage("§a[Setup] Midden gezet op jouw locatie."); },
                "Klik: zet het midden van de map op jouw locatie"));

        int islands = am.getIslands().size();
        int radius = getConfig().getInt("islands.default-radius", 8);
        s.add(nl.kmc.core.setup.SetupStep.action("Eilanden",
                islands + " (min. 2)", islands >= 2, org.bukkit.Material.GRASS_BLOCK,
                p -> { am.addIsland("island_" + (am.getIslands().size() + 1), p.getLocation(), radius);
                       p.sendMessage("§a[Setup] Eiland #" + am.getIslands().size() + " toegevoegd (radius " + radius + ")."); },
                "Klik: voeg een eiland-spawn toe op jouw locatie"));

        double ringStart = getConfig().getDouble("game.deathmatch-ring-start", 40);
        s.add(nl.kmc.core.setup.SetupStep.action("Deathmatch-ring",
                "radius " + (int) ringStart, ringStart > 0, org.bukkit.Material.MAGMA_BLOCK,
                p -> {
                    var mid = am.getMiddleSpawn();
                    if (mid == null || mid.getWorld() == null) { p.sendMessage("§c[Setup] Zet eerst het midden."); return; }
                    double dx = p.getLocation().getX() - mid.getX();
                    double dz = p.getLocation().getZ() - mid.getZ();
                    double r = Math.max(8, Math.round(Math.sqrt(dx * dx + dz * dz)));
                    getConfig().set("game.deathmatch-ring-start", r);
                    saveConfig();
                    p.sendMessage("§a[Setup] Deathmatch-ring radius gezet: §e" + (int) r);
                },
                "Klik terwijl je op de gewenste RAND staat: radius = afstand tot het midden"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new SkyWarsCommand(this);
        var bukkitCmd = getCommand("skywars");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new SkyWarsListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (skyWarsGameManagerV2 != null && skyWarsGameManagerV2.isRunning()) skyWarsGameManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ArenaManager          getArenaManager()    { return arenaManager; }
    public ChestStocker          getChestStocker()    { return chestStocker; }
    public SkyWarsGameManagerV2  getSkyWarsGameManagerV2() { return skyWarsGameManagerV2; }
}
