package nl.kmc.mayhem;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.mayhem.commands.MobMayhemCommand;
import nl.kmc.mayhem.listeners.MobListener;
import nl.kmc.mayhem.managers.ArenaManager;
import nl.kmc.mayhem.managers.KitManager;
import nl.kmc.mayhem.managers.MobMayhemGameManagerV2;
import nl.kmc.mayhem.managers.VoidWorldManager;
import nl.kmc.mayhem.managers.WorldCloner;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;

public final class MobMayhemPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "mob_mayhem";

    private ArenaManager           arenaManager;
    private WorldCloner            worldCloner;
    private VoidWorldManager       voidWorldManager;
    private KitManager             kitManager;
    private MobMayhemGameManagerV2 mobMayhemV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "Mob Mayhem"; }
    @Override protected Material icon()       { return Material.ZOMBIE_HEAD; }
    @Override protected int     minPlayers()  { return 4; }
    @Override protected String  description() { return "Overleef steeds zwaardere golven mobs — elk team in eigen arena. Let op powerups!"; }
    @Override protected String  objective()   { return "Overleef meer golven dan de andere teams."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+ptn — Per mob-kill (schaalt per golf, bosses extra)",
                "+100 ptn — Golf verslagen",
                "+500 ptn — 1e plaats (meeste golven)",
                "Powerups — snelheid, kracht, genezing en meer, verspreid door de arena"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager     = new ArenaManager(this);
        worldCloner      = new WorldCloner(this);
        voidWorldManager = new VoidWorldManager(this);
        kitManager       = new KitManager(this);
        mobMayhemV2  = new MobMayhemGameManagerV2(this, reg, stats);
        return mobMayhemV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (arenaManager == null) return java.util.List.of();
        var am = arenaManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();
        s.add(nl.kmc.core.setup.SetupStep.action("Arena-hoek 1", am.isBoxSet() ? "✓ gezet" : "niet gezet", am.isBoxSet(),
                org.bukkit.Material.RED_CONCRETE,
                p -> { String problem = am.templateWorldProblem(p);
                       if (problem != null) { p.sendMessage("§c[Setup] " + problem); return; }
                       am.setPos1(p.getLocation());
                       p.sendMessage("§a[Setup] Arena-hoek 1 = blok " + am.describePos(true) + " (het blok waar je op staat)."); },
                "Klik: zet de eerste hoek van de arena-box"));
        s.add(nl.kmc.core.setup.SetupStep.action("Arena-hoek 2", am.isBoxSet() ? "✓ gezet" : "niet gezet", am.isBoxSet(),
                org.bukkit.Material.BLUE_CONCRETE,
                p -> { String problem = am.templateWorldProblem(p);
                       if (problem != null) { p.sendMessage("§c[Setup] " + problem); return; }
                       am.setPos2(p.getLocation());
                       p.sendMessage("§a[Setup] Arena-hoek 2 = blok " + am.describePos(false) + " (het blok waar je op staat)."); },
                "Klik: zet de tegenoverliggende hoek van de arena-box"));
        s.add(nl.kmc.core.setup.SetupStep.action("Speler-spawn", "klik op je locatie", false,
                org.bukkit.Material.COMPASS,
                p -> { am.setPlayerSpawn(p.getLocation()); p.sendMessage("§a[Setup] Speler-spawn gezet."); },
                "Klik: zet de speler-spawn op jouw locatie"));
        int mobs = am.getMobSpawnCount();
        s.add(nl.kmc.core.setup.SetupStep.action("Mob spawns", mobs + " stuks", mobs > 0,
                org.bukkit.Material.ZOMBIE_HEAD,
                p -> { am.addMobSpawn(p.getLocation());
                       p.sendMessage("§a[Setup] Mob-spawn #" + am.getMobSpawnCount() + " toegevoegd."); },
                "Klik: voeg een mob-spawn toe op jouw locatie"));
        int powerups = am.getPowerupSpawnCount();
        s.add(nl.kmc.core.setup.SetupStep.action("Powerup spawns (optioneel)", powerups + " stuks", true,
                org.bukkit.Material.SUGAR,
                p -> { am.addPowerupSpawn(p.getLocation());
                       p.sendMessage("§a[Setup] Powerup-spawn #" + am.getPowerupSpawnCount() + " toegevoegd."); },
                "Klik: voeg een powerup-spawn toe op jouw locatie"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new MobMayhemCommand(this);
        var bukkitCmd = getCommand("mobmayhem");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new MobListener(this), this);
    }

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * /kmctest: a walled 41x41 arena in the CURRENT world, which becomes the template world —
     * box, player spawn, 8 mob spawns and 2 powerup spots. Needs WorldEdit (as the game itself does).
     */
    @Override
    protected org.bukkit.Location buildTestArena(org.bukkit.entity.Player admin, org.bukkit.Location origin) {
        var w = origin.getWorld();
        var a = nl.kmc.game.api.TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();

        nl.kmc.game.api.TestArenaKit.platform(w, cx, y, cz, 20, 20, Material.DEEPSLATE_TILES);
        nl.kmc.game.api.TestArenaKit.walls(w, cx, y + 1, cz, 20, 20, 8, Material.POLISHED_DEEPSLATE);

        getConfig().set("world.template-name", w.getName());
        saveConfig();

        // pos1/pos2 are "the block you stand on" — pass a location one above the intended block.
        arenaManager.setPos1(new org.bukkit.Location(w, cx - 20, y + 1, cz - 20));
        arenaManager.setPos2(new org.bukkit.Location(w, cx + 20, y + 11, cz + 20));
        arenaManager.setPlayerSpawn(nl.kmc.game.api.TestArenaKit.stand(w, cx, y + 1, cz, 0));
        arenaManager.clearMobSpawns();
        for (var spawn : nl.kmc.game.api.TestArenaKit.ring(w, cx, y + 1, cz, 15, 8)) arenaManager.addMobSpawn(spawn);
        arenaManager.clearPowerupSpawns();
        arenaManager.addPowerupSpawn(nl.kmc.game.api.TestArenaKit.stand(w, cx + 6, y + 1, cz, 0));
        arenaManager.addPowerupSpawn(nl.kmc.game.api.TestArenaKit.stand(w, cx - 6, y + 1, cz, 0));
        return nl.kmc.game.api.TestArenaKit.stand(w, cx, y + 1, cz, 0);
    }

    @Override
    protected void onGameDisable() {
        if (mobMayhemV2 != null && mobMayhemV2.isRunning()) mobMayhemV2.end();
        if (worldCloner != null) worldCloner.disposeAll();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().warning("[MobMayhem] V1 auto-start fired but V1 GameManager has been removed.");
            if (kmcCore.getAutomationManager().isRunning())
                kmcCore.getAutomationManager().onGameEnd(null);
        }, 40L);
    }

    // ── Getters used by commands / listeners ──────────────────────────────────

    public KMCCore                getKmcCore()       { return kmcCore; }
    public ArenaManager           getArenaManager()  { return arenaManager; }
    public WorldCloner            getWorldCloner()   { return worldCloner; }
    public VoidWorldManager       getVoidWorldManager() { return voidWorldManager; }
    public KitManager             getKitManager()    { return kitManager; }
    public MobMayhemGameManagerV2 getGameManagerV2() { return mobMayhemV2; }
}
