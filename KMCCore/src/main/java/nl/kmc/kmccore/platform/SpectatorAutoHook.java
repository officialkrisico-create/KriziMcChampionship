package nl.kmc.kmccore.platform;

import nl.kmc.core.event.GameEndEvent;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.spectator.SpectatorManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gives every player who ends up in spectator mode during a game the spectator kit (teleport compass,
 * follow mode, leader tracker) — whichever game it is, without every game having to call the manager.
 * The kit is removed again when the game ends or the player leaves spectator mode.
 */
public final class SpectatorAutoHook implements Listener {

    private final KMCCore plugin;

    public SpectatorAutoHook(KMCCore plugin) { this.plugin = plugin; }

    private SpectatorManager mgr() { return plugin.getSpectatorManager(); }

    private boolean gameOn() {
        return nl.kmc.core.RunningGames.anyRunning() || plugin.getGameManager().isGameActive();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMode(PlayerGameModeChangeEvent e) {
        if (!plugin.getConfig().getBoolean("spectator-kit.enabled", true) || mgr() == null) return;
        Player p = e.getPlayer();
        if (e.getNewGameMode() == GameMode.SPECTATOR) {
            // Let the game finish its own teleports/cleanup first.
            Bukkit.getScheduler().runTaskLater(plugin, () -> enable(p), 10L);
        } else if (mgr().isSpectating(p)) {
            mgr().disableSpectatorMode(p);
        }
    }

    private void enable(Player p) {
        if (!p.isOnline() || p.getGameMode() != GameMode.SPECTATOR || !gameOn() || mgr().isSpectating(p)) return;
        var cm = plugin.getCinematicManager();
        if (cm != null && cm.isInCinematic(p.getUniqueId())) return;
        mgr().enableSpectatorMode(p, new SpectatorManager.SpectatorContext() {
            @Override public List<Player> getAlivePlayers() {
                List<Player> out = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o.equals(p) || o.getGameMode() == GameMode.SPECTATOR || o.getWorld() != p.getWorld()) continue;
                    out.add(o);
                }
                return out;
            }
            @Override public Player getLeader() {
                return getAlivePlayers().stream().max(Comparator.comparingInt(o -> {
                    PlayerData pd = plugin.getPlayerDataManager().get(o.getUniqueId());
                    return pd != null ? pd.getPoints() : 0;
                })).orElse(null);
            }
            @Override public String getGameName() {
                var g = plugin.getGameManager().getActiveGame();
                return g != null ? g.getDisplayName() : "Game";
            }
        });
    }

    @EventHandler
    public void onGameEnd(GameEndEvent e) {
        if (mgr() == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (mgr().isSpectating(p)) mgr().disableSpectatorMode(p);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (mgr() != null && mgr().isSpectating(e.getPlayer())) mgr().disableSpectatorMode(e.getPlayer());
    }
}
