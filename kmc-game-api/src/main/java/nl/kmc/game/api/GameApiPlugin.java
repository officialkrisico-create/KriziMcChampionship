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
        getServer().getPluginManager().registerEvents(new CameraCommandBridge(), this);

        var testCmd = new TestArenaCommand(this);
        var bukkitCmd = getCommand("kmctest");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(testCmd); bukkitCmd.setTabCompleter(testCmd); }

        var botCmd = new BotCommand(this);
        var botBukkitCmd = getCommand("kmcbot");
        if (botBukkitCmd != null) { botBukkitCmd.setExecutor(botCmd); botBukkitCmd.setTabCompleter(botCmd); }
        getLogger().info("kmc-game-api loaded (game-end bridge active).");
    }

    @Override
    public void onDisable() {
        // Test bots have no client to disconnect them — take them down cleanly with the server.
        FakePlayerSpawner.removeAll();
    }

    /** Forwards V2 game results to the V1 automation flow. */
    /**
     * "/<game> cam" for every game command (e.g. /bp cam, /sg cam): opens that game's camera setup menu.
     * Handled here, before the game's own executor sees it, so no game needs its own camera code.
     */
    private final class CameraCommandBridge implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
            String[] parts = e.getMessage().trim().split("\s+");
            if (parts.length < 2 || !parts[1].equalsIgnoreCase("cam")) return;
            String label = parts[0].substring(1).toLowerCase();
            var cmd = Bukkit.getPluginCommand(label);
            if (cmd == null) return;
            var owner = cmd.getPlugin();
            String gameId = null, name = owner.getName();
            if (owner instanceof AbstractGamePlugin agp) {
                gameId = agp.gameId();
                name   = agp.displayName();
            } else {
                try {   // plain game plugins (Adventure Escape) expose a public static GAME_ID
                    var fld = owner.getClass().getField("GAME_ID");
                    if (fld.get(null) instanceof String s) gameId = s;
                } catch (ReflectiveOperationException ignored) { }
            }
            if (gameId == null) return;
            var p = e.getPlayer();
            if (!p.hasPermission("kmc.camera.admin") && !p.isOp()) return;
            e.setCancelled(true);
            if (Bukkit.getPluginManager().getPlugin("KMCCore") instanceof KMCCore core) core.openGameCamera(p, gameId, name);
        }
    }

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
