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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gives every player who ends up in spectator mode during a game the spectator kit (teleport compass,
 * follow mode, leader tracker) — whichever game it is, without every game having to call the manager.
 * The kit is removed again when the game ends or the player leaves spectator mode.
 */
public final class SpectatorAutoHook implements Listener {

    private final KMCCore plugin;
    /** Players a game put in spectator mode (they stay spectators through the respawn that follows a death). */
    private final Set<UUID> spectators = new HashSet<>();
    private final Map<UUID, org.bukkit.Location> lastDeath = new HashMap<>();
    /** Spectators in the middle of a respawn: the game-mode reset that comes with it must not forget them. */
    private final Set<UUID> respawning = new HashSet<>();

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
            if (gameOn()) spectators.add(p.getUniqueId());
            // Let the game finish its own teleports/cleanup first.
            Bukkit.getScheduler().runTaskLater(plugin, () -> enable(p), 10L);
        } else {
            if (!respawning.contains(p.getUniqueId())) {
                spectators.remove(p.getUniqueId());
                if (mgr().isSpectating(p)) mgr().disableSpectatorMode(p);
            }
        }
    }

    /** Where a spectator died, so the respawn that follows does not throw him back to the lobby. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        lastDeath.put(e.getEntity().getUniqueId(), e.getEntity().getLocation());
    }

    /**
     * A game that puts a dead player in spectator mode does it while he is on the death screen; the respawn
     * that follows can reset the game mode, which left "spectators" that cannot fly and that can hit mobs.
     * They are put back in spectator mode (where they died) as soon as they respawn.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!spectators.contains(p.getUniqueId()) || !gameOn()) return;
        org.bukkit.Location where = lastDeath.get(p.getUniqueId());
        if (where != null && where.getWorld() != null) e.setRespawnLocation(where);
        respawning.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            respawning.remove(p.getUniqueId());
            if (p.isOnline() && gameOn()) {
                spectators.add(p.getUniqueId());
                p.setGameMode(GameMode.SPECTATOR);
                p.setAllowFlight(true);
                p.setFlying(true);
            }
        }, 2L);
    }

    /** A spectator never damages anything (a safety net for a spectator who is not in real spectator mode). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && spectators.contains(p.getUniqueId())) e.setCancelled(true);
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
            spectators.clear();
            lastDeath.clear();
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        spectators.remove(e.getPlayer().getUniqueId());
        lastDeath.remove(e.getPlayer().getUniqueId());
        if (mgr() != null && mgr().isSpectating(e.getPlayer())) mgr().disableSpectatorMode(e.getPlayer());
    }
}
