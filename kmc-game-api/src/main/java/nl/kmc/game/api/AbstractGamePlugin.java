package nl.kmc.game.api;

import nl.kmc.core.KMCConstants;
import nl.kmc.core.KMCCorePlugin;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.event.GameStartEvent;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.PlayerService;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Base class for all KMC game plugins.
 *
 * <p>Eliminates the 60-line V2 tournament bootstrap block that was
 * copy-pasted into every game plugin. Subclasses only declare their
 * game metadata and wire up their game-specific managers/commands/listeners
 * inside {@link #onGameEnable()} and {@link #onGameDisable()}.
 *
 * <h2>Minimal subclass</h2>
 * <pre>{@code
 * public final class SkyWarsPlugin extends AbstractGamePlugin {
 *
 *     public static final String GAME_ID = "team_skywars";
 *
 *     private ArenaManager       arenaManager;
 *     private SkyWarsGameManagerV2 gameManagerV2;
 *
 *     @Override protected String  gameId()      { return GAME_ID; }
 *     @Override protected String  displayName() { return "Team SkyWars"; }
 *     @Override protected Material icon()       { return Material.ENDER_CHEST; }
 *     @Override protected int     minPlayers()  { return 4; }
 *     @Override protected String  description() { return "PvP on floating islands."; }
 *     @Override protected String  objective()   { return "Last team standing wins."; }
 *     @Override protected List<String> scoringLines() {
 *         return List.of("+50 pts — Kill", "+500 pts — 1st Place", "+5 pts — Survival bonus");
 *     }
 *
 *     @Override
 *     protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
 *         arenaManager = new ArenaManager(this);
 *         gameManagerV2 = new SkyWarsGameManagerV2(this, reg, stats);
 *         return gameManagerV2;
 *     }
 *
 *     @Override
 *     protected void onGameEnable() {
 *         // register commands, listeners, etc.
 *     }
 *
 *     @Override
 *     protected void onGameDisable() {
 *         if (gameManagerV2 != null && gameManagerV2.isRunning()) gameManagerV2.end();
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractGamePlugin extends JavaPlugin {

    /** The V1 KMCCore plugin, always available. */
    protected KMCCore kmcCore;

    /** The V2 game manager, or {@code null} when only the V1 path is active. */
    protected BaseGameManager gameManagerV2;

    // ── Abstract metadata ─────────────────────────────────────────────────────

    /** Unique machine-readable identifier for this game (e.g. {@code "team_skywars"}). */
    protected abstract String gameId();

    /** Human-readable display name (e.g. {@code "Team SkyWars"}). */
    protected abstract String displayName();

    /** Item icon used in the voting GUI. */
    protected abstract Material icon();

    /** Minimum online players required to start. */
    protected abstract int minPlayers();

    /** One-sentence game description shown in the intro card. */
    protected abstract String description();

    /** One-sentence win objective shown in the intro card. */
    protected abstract String objective();

    /**
     * Scoring lines shown in the intro card (e.g. {@code "+50 pts — Kill"}).
     * Return an empty list to omit scoring info.
     */
    protected abstract List<String> scoringLines();

    /**
     * Factory: create the V2 game manager for this plugin.
     * Called after {@code GameRegistration} is built and registered.
     * Subclasses may also initialise supporting managers (ArenaManager etc.)
     * inside this method since it runs in {@code onEnable()} order.
     *
     * @param stats the shared statistics service for this plugin
     * @param reg   the registered {@link GameRegistration}
     * @return the concrete {@link BaseGameManager} for this game
     */
    protected abstract BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg);

    /**
     * Called after V2 bootstrap (or V1 fallback) is complete.
     * Register game-specific commands, listeners, and managers here.
     */
    protected abstract void onGameEnable();

    /**
     * Called from {@link #onDisable()}. Clean up game-specific resources.
     * The base class does NOT call {@link BaseGameManager#end()} — subclasses
     * are responsible for stopping their own managers.
     */
    protected abstract void onGameDisable();

    // ── Optional hooks ────────────────────────────────────────────────────────

    /**
     * Called when the V1 automation engine selects this game.
     * Override to start the V1 game manager. Default is a no-op.
     *
     * @param gameId the game id chosen by the V1 automation engine
     */
    protected void onV1GameStart(String gameId) {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public final void onEnable() {
        saveDefaultConfig();

        // KMCCore (V1) is required
        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found — disabling " + getDescription().getName() + ".");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        // ── V2 tournament bootstrap ──────────────────────────────────────────
        KMCCorePlugin coreV2 = (KMCCorePlugin) getServer().getPluginManager()
                .getPlugin(KMCConstants.CORE_V2_PLUGIN_NAME);

        if (coreV2 != null) {
            PlayerService       playerService = coreV2.getContainer().get(PlayerService.class);
            GameRegistryService gameRegistry  = coreV2.getContainer().get(GameRegistryService.class);
            StatisticsService   statsService  = new StatisticsService(this, playerService);

            GameRegistration reg = GameRegistration.builder(gameId(), displayName())
                    .icon(icon())
                    .minPlayers(minPlayers())
                    .description(description())
                    .objective(objective())
                    .build();

            gameRegistry.register(reg);
            gameManagerV2 = createGameManagerV2(statsService, reg);

            // Build intro card
            GameIntroCard.Builder cardBuilder = GameIntroCard.builder(gameId(), displayName())
                    .objective(objective());
            for (String line : scoringLines()) cardBuilder.addScoringLine(line);
            GameIntroCardRegistry.register(cardBuilder.build());

            // Listen for tournament engine start signal
            String id         = gameId();
            String pluginName = getDescription().getName();
            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onGameStart(GameStartEvent event) {
                    if (!id.equals(event.getGame().getId())) return;
                    getLogger().info("[" + pluginName + "] Tournament start signal — resetting arena then launching.");
                    Bukkit.getScheduler().runTaskLater(AbstractGamePlugin.this, () -> {
                        // Paste schematic to restore arena (no-op if not configured)
                        resetArena();
                        // Start game 1 second after paste so world changes settle
                        Bukkit.getScheduler().runTaskLater(AbstractGamePlugin.this, () -> {
                            if (!gameManagerV2.start()) {
                                java.util.List<String> issues = gameManagerV2.getArenaIssues();
                                getLogger().warning("[" + pluginName + "] V2 start() rejected — arena not ready:");
                                issues.forEach(i -> getLogger().warning("  - " + i));
                                // Tell online admins in chat so they don't have to read console.
                                for (org.bukkit.entity.Player op : Bukkit.getOnlinePlayers()) {
                                    if (!op.isOp() && !op.hasPermission("kmc.admin")) continue;
                                    op.sendMessage("§c[" + pluginName + "] Arena niet klaar:");
                                    if (issues.isEmpty()) op.sendMessage("§7  (geen details — check de arena setup)");
                                    issues.forEach(i -> op.sendMessage("§7  - §f" + i));
                                }
                            }
                        }, 20L);
                    }, 20L);
                }
            }, this);

            // Register this game in the unified Setup Dashboard.
            nl.kmc.core.setup.SetupService setupService =
                    coreV2.getContainer().get(nl.kmc.core.setup.SetupService.class);
            if (setupService != null) setupService.register(buildGameSetup());

            getLogger().info("[" + pluginName + "] V2 tournament integration enabled.");

        } else {
            // V1 fallback — delegate to subclass
            String id = gameId();
            kmcCore.getApi().onGameStart(gameId -> {
                if (!id.equals(gameId)) return;
                onV1GameStart(gameId);
            });
        }

        onGameEnable();
        getLogger().info(getDescription().getName() + " enabled!");
    }

    @Override
    public final void onDisable() {
        onGameDisable();
    }

    /** Returns the V1 KMCCore plugin instance. */
    public KMCCore getKmcCore() { return kmcCore; }

    /**
     * Resets the arena by pasting {@code <gameId>mapschematic.schem} from
     * {@code plugins/KMCCore/schematics/} at the origin configured with
     * {@code /kmcgame setorigin <gameId>}.
     *
     * <p>Silently skips if WorldEdit is absent, the schematic file is missing,
     * or no origin has been set. Call this before each game start so the arena
     * is always in its original state, regardless of what players did last round.
     *
     * @return {@code true} if the paste succeeded or was skipped, {@code false} on error
     */
    protected boolean resetArena() {
        var sm = kmcCore.getSchematicManager();
        if (!sm.isWorldEditAvailable()) return true;
        String schematicName = gameId() + "mapschematic.schem";
        org.bukkit.Location origin = sm.getOriginForGame(gameId());
        if (origin == null) {
            getLogger().fine("[" + gameId() + "] No arena origin set — skipping schematic reset.");
            return true;
        }
        getLogger().info("[" + gameId() + "] Resetting arena: " + schematicName);
        return sm.resetArena(schematicName, origin);
    }

    // ── Setup Dashboard integration ───────────────────────────────────────────

    /**
     * Game-specific setup steps (spawns, powerup spots, jump pads, etc.) shown in
     * the Setup Dashboard. Override to add clickable actions; the base class
     * already contributes the shared arena-origin / schematic / readiness steps.
     *
     * @param viewer the admin viewing the dashboard
     */
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        return java.util.List.of();
    }

    /** Builds the {@link nl.kmc.core.setup.GameSetup} registered for this game. */
    private nl.kmc.core.setup.GameSetup buildGameSetup() {
        final String id = gameId();
        final String name = displayName();
        final Material ic = icon();
        return new nl.kmc.core.setup.GameSetup() {
            @Override public String   gameId()      { return id; }
            @Override public String   displayName() { return name; }
            @Override public Material  icon()        { return ic; }
            @Override public boolean  isReady() {
                return gameManagerV2 != null && gameManagerV2.getArenaIssues().isEmpty();
            }
            @Override public java.util.List<String> issues() {
                return gameManagerV2 != null ? gameManagerV2.getArenaIssues() : java.util.List.of();
            }
            @Override public java.util.List<nl.kmc.core.setup.SetupStep> steps(org.bukkit.entity.Player viewer) {
                java.util.List<nl.kmc.core.setup.SetupStep> out = new java.util.ArrayList<>();

                // Shared: arena schematic origin (used to auto-reset arenas between rounds).
                var sm = kmcCore.getSchematicManager();
                boolean originSet = sm.getOriginForGame(id) != null;
                out.add(nl.kmc.core.setup.SetupStep.action(
                        "Arena origin", originSet ? "✓ ingesteld" : "niet ingesteld", originSet,
                        Material.LODESTONE,
                        p -> { sm.setOriginForGame(id, p.getLocation());
                               p.sendMessage("§a[Setup] Arena origin gezet op jouw locatie."); },
                        "Klik: zet de schematic-origin op jouw locatie"));

                String schemName = id + "mapschematic.schem";
                boolean schemPresent = new java.io.File(sm.getSchematicFolder(), schemName).exists();
                out.add(nl.kmc.core.setup.SetupStep.info(
                        "Schematic", schemPresent ? "✓ " + schemName : "ontbreekt (" + schemName + ")",
                        schemPresent, Material.STRUCTURE_BLOCK));

                // Game-specific actions provided by the subclass.
                out.addAll(extraSetupSteps(viewer));

                // Any remaining validation problems, surfaced as red display rows.
                if (gameManagerV2 != null) {
                    for (String issue : gameManagerV2.getArenaIssues()) {
                        out.add(nl.kmc.core.setup.SetupStep.info("Probleem", issue, false, Material.BARRIER));
                    }
                }
                return out;
            }
        };
    }
}
