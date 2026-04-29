package nl.kmc.kmccore.lobby;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.TeamArmor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

/**
 * Maintains team-colored leather boots on every player while they are
 * "in the lobby" — defined as anywhere outside an active minigame arena.
 *
 * <p>Triggers application on:
 * <ul>
 *   <li>Player join</li>
 *   <li>Player respawn</li>
 *   <li>Player change world (when arriving at lobby world)</li>
 *   <li>Player team assignment changes (re-coloring)</li>
 * </ul>
 *
 * <p>Boots are not auto-replenished mid-game — that's intentional. Once
 * a minigame teleports the player into its arena, the game's start
 * routine is responsible for either keeping the boots, replacing them
 * with combat armor, or calling {@link TeamArmor#removeTeamArmor(Player)}
 * if armor isn't desired in that game at all.
 *
 * <p>The lobby world is read from KMCCore config:
 * {@code lobby.world} (defaults to "world" if unset).
 */
public class LobbyArmorListener implements Listener {

    private final KMCCore plugin;

    public LobbyArmorListener(KMCCore plugin) {
        this.plugin = plugin;
    }

    /** Called by KMCCore on enable to give boots to anyone already online. */
    public void applyToAllOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isInLobby(p)) {
                TeamArmor.applyBoots(p);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Brief delay so spawn point is settled
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isInLobby(p)) {
                TeamArmor.applyBoots(p);
            }
        }, 5L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isInLobby(p)) {
                TeamArmor.applyBoots(p);
            }
        }, 2L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (isInLobby(p)) {
            TeamArmor.applyBoots(p);
        }
    }

    /**
     * True if the player is currently in the lobby world (not in an
     * active arena world).
     */
    private boolean isInLobby(Player p) {
        Location loc = p.getLocation();
        if (loc == null || loc.getWorld() == null) return false;

        String lobbyWorld = plugin.getConfig().getString("lobby.world", "world");
        return loc.getWorld().getName().equalsIgnoreCase(lobbyWorld);
    }
}
