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
                            if (!gameManagerV2.start())
                                getLogger().warning("[" + pluginName + "] V2 start() rejected — arena not ready.");
                        }, 20L);
                    }, 20L);
                }
            }, this);

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
}
