package nl.kmc.core.listener;

import nl.kmc.core.service.PlayerService;
import nl.kmc.core.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerSessionListener implements Listener {

    private final JavaPlugin     plugin;
    private final PlayerService  players;
    private final TeamService    teams;

    public PlayerSessionListener(JavaPlugin plugin, PlayerService players, TeamService teams) {
        this.plugin  = plugin;
        this.players = players;
        this.teams   = teams;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        var kmc = players.getOrCreate(p.getUniqueId(), p.getName());
        kmc.setName(p.getName()); // always update in case of rename
        kmc.setSessionJoinTime(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        players.get(p.getUniqueId()).ifPresent(kmc -> {
            kmc.accumulateSessionTime();
            players.persist(kmc);
        });
    }
}
