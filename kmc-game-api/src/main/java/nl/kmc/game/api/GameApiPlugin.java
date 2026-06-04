package nl.kmc.game.api;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loader plugin for kmc-game-api.
 *
 * <p>Besides making the API classes available to game plugins, this plugin
 * hosts the <b>game-end bridge</b>: it listens for {@link GameResultEvent}
 * (fired by every V2 game manager) and forwards it to KMCCore's V1
 * {@code AutomationManager} via {@code GameManager.stopGame(...)}.
 *
 * <p>This lets {@code /kmcauto} drive the V2 games end-to-end: it launches
 * them by firing {@code GameStartEvent} (see {@code GameManager.startGame}),
 * and is notified of completion through this bridge.
 *
 * <p>The bridge only acts when the V1 AutomationManager is running, so it does
 * not interfere with the V2 tournament engine ({@code /kmctournament}).
 */
public final class GameApiPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new ResultBridge(), this);
        getLogger().info("kmc-game-api loaded (game-end bridge active).");
    }

    /** Forwards V2 game results to the V1 automation flow. */
    private final class ResultBridge implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onGameResult(GameResultEvent event) {
            if (!(Bukkit.getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) return;

            // Only drive V1 automation when it is the active engine.
            if (core.getAutomationManager() == null || !core.getAutomationManager().isRunning()) return;

            // stopGame() clears the active game, runs end announcements/cleanup,
            // and calls AutomationManager.onGameEnd() — which handles ceremonies,
            // repetitions, leaderboard chain and the next-game transition.
            if (core.getGameManager().getActiveGame() != null) {
                core.getGameManager().stopGame(event.getWinnerDescription());
            }
        }
    }
}
