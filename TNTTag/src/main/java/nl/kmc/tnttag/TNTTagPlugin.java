package nl.kmc.tnttag;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.tnttag.commands.TagCommand;
import nl.kmc.tnttag.listeners.TagListener;
import nl.kmc.tnttag.managers.ArenaManager;
import nl.kmc.tnttag.managers.PowerupManager;
import nl.kmc.tnttag.managers.TNTTagGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class TNTTagPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "tnt_tag";

    private ArenaManager        arenaManager;
    private PowerupManager      powerupManager;
    private TNTTagGameManagerV2 tntManagerV2;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String      gameId()      { return GAME_ID; }
    @Override protected String      displayName() { return "TNT Tag"; }
    @Override protected Material    icon()        { return Material.TNT; }
    @Override protected int         minPlayers()  { return 4; }
    @Override protected String      description() { return "Zorg dat jij de bom niet vasthoudt als de tijd om is!"; }
    @Override protected String      objective()   { return "Overleef alle rondes zonder geëlimineerd te worden."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+20 ptn — Ronde overleven",
            "+500 ptn — 1e plaats"
        );
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager   = new ArenaManager(this);
        powerupManager = new PowerupManager(this);
        tntManagerV2   = new TNTTagGameManagerV2(this, reg, stats);
        return tntManagerV2;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();
        s.add(nl.kmc.core.setup.SetupStep.action("Arena wereld", "klik om te zetten", false,
                org.bukkit.Material.GRASS_BLOCK,
                p -> { am.setWorld(p.getWorld()); p.sendMessage("§a[Setup] Wereld gezet op " + p.getWorld().getName()); },
                "Klik: zet de arena-wereld op die van jou"));
        s.add(nl.kmc.core.setup.SetupStep.action("Voeg spawn toe", "klik op je locatie", false,
                org.bukkit.Material.RED_BED,
                p -> { am.addSpawn(p.getLocation()); p.sendMessage("§a[Setup] Spawn toegevoegd."); },
                "Klik: voeg een spawn toe op jouw locatie"));
        int spawnCount = am.getArena().getSpawns().size();
        s.set(1, nl.kmc.core.setup.SetupStep.action("Voeg spawn toe",
                spawnCount + " (min. 2)", spawnCount >= 2, org.bukkit.Material.RED_BED,
                p -> { am.addSpawn(p.getLocation()); p.sendMessage("§a[Setup] Spawn #" + am.getArena().getSpawns().size() + " toegevoegd."); },
                "Klik: voeg een spawn toe op jouw locatie"));

        s.add(nl.kmc.core.setup.SetupStep.action("Void Y-level", "jouw hoogte", false,
                org.bukkit.Material.LIGHT_GRAY_STAINED_GLASS,
                p -> { am.setVoidY(p.getLocation().getBlockY());
                       p.sendMessage("§a[Setup] Void-Y gezet op " + p.getLocation().getBlockY()); },
                "Klik: zet void-Y op jouw huidige hoogte"));

        boolean centerSet = am.getArena().getCenter() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Midden", centerSet ? "✓ ingesteld" : "niet ingesteld",
                centerSet, org.bukkit.Material.BEACON,
                p -> { am.setCenter(p.getLocation()); p.sendMessage("§a[Setup] Midden gezet (voor border & chaos events)."); },
                "Klik: zet het arena-midden op jouw locatie"));

        double br = am.getArena().getBorderRadius();
        s.add(nl.kmc.core.setup.SetupStep.action("Border", br > 0 ? "radius " + (int) br : "niet ingesteld",
                br > 0, org.bukkit.Material.MAGMA_BLOCK,
                p -> {
                    var c = am.getArena().getCenter();
                    if (c == null || c.getWorld() == null) { p.sendMessage("§c[Setup] Zet eerst het midden."); return; }
                    double dx = p.getLocation().getX() - c.getX(), dz = p.getLocation().getZ() - c.getZ();
                    double r = Math.max(10, Math.round(Math.sqrt(dx * dx + dz * dz)));
                    am.setBorderRadius(r);
                    p.sendMessage("§a[Setup] Border-radius gezet op §e" + (int) r);
                },
                "Klik op de RAND: radius = afstand tot het midden"));

        boolean specSet = am.getArena().getSpectatorSpawn() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Spectator-spawn", specSet ? "✓ ingesteld" : "niet ingesteld",
                specSet, org.bukkit.Material.ENDER_EYE,
                p -> { am.setSpectatorSpawn(p.getLocation()); p.sendMessage("§a[Setup] Spectator-spawn gezet."); },
                "Klik: zet de spectator-spawn op jouw locatie"));

        int pu = am.getArena().getPowerupSpawns().size();
        s.add(nl.kmc.core.setup.SetupStep.action("Powerup-spot", pu + " spots", pu > 0, org.bukkit.Material.CHEST,
                p -> { am.addPowerupSpawn(p.getLocation()); p.sendMessage("§a[Setup] Powerup-spot #" + am.getArena().getPowerupSpawns().size() + " toegevoegd."); },
                "Klik: voeg een powerup-spawnlocatie toe"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        if (arenaManager == null) arenaManager = new ArenaManager(this);

        var cmd = new TagCommand(this);
        var bukkitCmd = getCommand("tnttag");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new TagListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (powerupManager != null) powerupManager.stop();
        if (tntManagerV2 != null && tntManagerV2.isRunning()) tntManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        getLogger().warning("[TNTTag] V1 game-start signal received but V1 GameManager has been removed. Use V2.");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ArenaManager        getArenaManager()   { return arenaManager; }
    public PowerupManager      getPowerupManager() { return powerupManager; }
    /** Returns the V2 manager cast to TNTTagGameManagerV2, or null. */
    public TNTTagGameManagerV2 getTntManagerV2()   { return tntManagerV2; }
}
