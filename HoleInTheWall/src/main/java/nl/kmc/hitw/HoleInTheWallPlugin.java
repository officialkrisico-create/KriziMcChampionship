package nl.kmc.hitw;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.TestArenaKit;
import nl.kmc.hitw.commands.HitwCommand;
import nl.kmc.hitw.config.HitwConfig;
import nl.kmc.hitw.listeners.HitwListener;
import nl.kmc.hitw.managers.ArenaManager;
import nl.kmc.hitw.managers.HoleInTheWallGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class HoleInTheWallPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "hole_in_the_wall";

    private HitwConfig hitwConfig;
    private ArenaManager arenaManager;
    private HoleInTheWallGameManagerV2 gameManager;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Hole in the Wall"; }
    @Override protected Material icon()        { return Material.BRICK_WALL; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Enorme muren schuiven over het platform — spring, sneak of loop door het gat."; }
    @Override protected String   objective()   { return "Overleef de muren en blijf als laatste staan."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "Punten per ronde-plaatsing — hoe langer je overleeft, hoe meer",
                "Meerdere rondes: de totaalstand bepaalt je KMC-punten",
                "Een muur duwt je van het platform — wie valt, is eruit");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        hitwConfig   = new HitwConfig();
        hitwConfig.load(getConfig());
        arenaManager = new ArenaManager(this);
        gameManager  = new HoleInTheWallGameManagerV2(this, reg, stats);
        return gameManager;
    }

    @Override
    protected List<nl.kmc.core.setup.SetupStep> extraSetupSteps(Player viewer) {
        if (arenaManager == null) return List.of();
        var am = arenaManager;
        List<nl.kmc.core.setup.SetupStep> s = new ArrayList<>();
        s.add(nl.kmc.core.setup.SetupStep.action("Platform-midden",
                am.getArena().isCenterSet() ? "✓ gezet" : "niet gezet", am.getArena().isCenterSet(),
                Material.BEACON,
                p -> { am.setCenter(p.getLocation());
                       p.sendMessage("§a[Setup] Platform-midden = het blok waar je op staat (" + p.getWorld().getName() + ")."); },
                "Klik: zet het midden van het ronde platform op het blok waar je op staat"));
        s.add(nl.kmc.core.setup.SetupStep.action("Platform-radius", am.getArena().getRadius() + " blokken", true,
                Material.COMPASS,
                p -> getKmcCore().getChatInput().await(p, "Typ de radius van het platform (6 of meer):", text -> {
                    try { am.setRadius(Integer.parseInt(text.trim())); p.sendMessage("§a[Setup] Radius = " + am.getArena().getRadius()); }
                    catch (NumberFormatException e) { p.sendMessage("§c[Setup] Dat is geen getal."); }
                }),
                "Klik: stel de radius van het platform in"));
        s.add(nl.kmc.core.setup.SetupStep.action("Spectator-spot (optioneel)", "jouw locatie", true,
                Material.SPYGLASS,
                p -> { am.setSpectator(p.getLocation()); p.sendMessage("§a[Setup] Spectator-spot gezet."); },
                "Klik: waar uitgeschakelde spelers kijken (standaard boven het midden)"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new HitwCommand(this);
        var bukkitCmd = getCommand("hitw");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new HitwListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (gameManager != null) {
            if (gameManager.isRunning()) gameManager.end();
            gameManager.getWallManager().clear();
        }
    }

    @Override
    protected void onV1GameStart(String gameId) { /* V1 path removed — V2 only */ }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** /kmctest: a radius-16 platform in the sky, centre set and built. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        Location a = TestArenaKit.anchor(origin);
        // setCenter takes the location a player STANDS at (it uses the block below), so pass y+1.
        arenaManager.setCenter(new Location(a.getWorld(), a.getBlockX(), a.getBlockY() + 1, a.getBlockZ()));
        arenaManager.setRadius(16);
        arenaManager.setSpectator(null);
        arenaManager.getArena().buildPlatform();
        return arenaManager.getArena().centerLocation();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public HitwConfig                 getHitwConfig()    { return hitwConfig; }
    public ArenaManager               getArenaManager()  { return arenaManager; }
    public HoleInTheWallGameManagerV2 getGameManagerV2() { return gameManager; }
}
