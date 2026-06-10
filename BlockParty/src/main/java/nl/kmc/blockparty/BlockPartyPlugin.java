package nl.kmc.blockparty;

import nl.kmc.blockparty.commands.BlockPartyCommand;
import nl.kmc.blockparty.listeners.BlockPartyListener;
import nl.kmc.blockparty.managers.ArenaManager;
import nl.kmc.blockparty.managers.BlockPartyGameManagerV2;
import nl.kmc.blockparty.managers.FloorGenerator;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.setup.SetupStep;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class BlockPartyPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "block_party";

    private ArenaManager            arenaManager;
    private FloorGenerator          floorGenerator;
    private BlockPartyGameManagerV2 gameManager;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Block Party"; }
    @Override protected Material icon()        { return Material.RED_CONCRETE; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Sta op de juiste kleur voordat de vloer verdwijnt — laatste speler wint."; }
    @Override protected String   objective()   { return "Overleef elke kleur-eliminatie ronde."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+250 ptn — 1e plaats",
            "-10 ptn per plaats lager",
            "+150 ptn — laatste team bonus");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager   = new ArenaManager(this);
        floorGenerator = new FloorGenerator(arenaManager);
        gameManager    = new BlockPartyGameManagerV2(this, reg, stats);
        return gameManager;
    }

    @Override
    protected List<SetupStep> extraSetupSteps(Player viewer) {
        if (arenaManager == null) return List.of();
        var a = arenaManager;
        List<SetupStep> s = new ArrayList<>();
        s.add(SetupStep.action("Vloer-hoek 1", a.getPos1() != null ? "✓ gezet" : "niet gezet", a.getPos1() != null,
                Material.RED_CONCRETE,
                p -> { a.setCorner1(p.getLocation()); p.sendMessage("§a[Setup] Vloer-hoek 1 gezet."); },
                "Klik: zet de eerste hoek van de vloer"));
        s.add(SetupStep.action("Vloer-hoek 2", a.getPos2() != null ? "✓ gezet" : "niet gezet", a.getPos2() != null,
                Material.BLUE_CONCRETE,
                p -> { a.setCorner2(p.getLocation()); p.sendMessage("§a[Setup] Vloer-hoek 2 gezet (oppervlak: " + a.area() + ")."); },
                "Klik: zet de tweede hoek van de vloer"));
        s.add(SetupStep.action("Spectator-spawn", a.getSpectator() != null ? "✓ gezet" : "niet gezet", a.getSpectator() != null,
                Material.ENDER_EYE,
                p -> { a.setSpectator(p.getLocation()); p.sendMessage("§a[Setup] Spectator-spawn gezet."); },
                "Klik: zet waar geëlimineerde spelers toekijken"));
        s.add(SetupStep.action("Void-Y", "huidig: " + a.getVoidY(), true,
                Material.BARRIER,
                p -> { a.setVoidY(p.getLocation().getBlockY()); p.sendMessage("§a[Setup] Void-Y = " + p.getLocation().getBlockY()); },
                "Klik: zet de hoogte waaronder spelers 'gevallen' zijn"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new BlockPartyCommand(this);
        var bukkitCmd = getCommand("blockparty");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new BlockPartyListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (gameManager != null && gameManager.isRunning()) gameManager.end();
    }

    @Override
    protected void onV1GameStart(String gameId) { /* no V1 path */ }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ArenaManager            getArenaManager() { return arenaManager; }
    public FloorGenerator          floorGen()        { return floorGenerator; }
    public BlockPartyGameManagerV2 getGameManager()  { return gameManager; }
}
