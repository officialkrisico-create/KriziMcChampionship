package nl.kmc.core.listener;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.service.PlayerService;
import nl.kmc.core.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class TeamChatListener implements Listener {

    private final JavaPlugin   plugin;
    private final TeamService  teams;

    public TeamChatListener(JavaPlugin plugin, TeamService teams) {
        this.plugin = plugin;
        this.teams  = teams;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        var kmc = teams.getPlayer(p.getUniqueId());
        if (kmc.isEmpty() || !kmc.get().isTeamChatEnabled()) return;

        Optional<KMCTeam> teamOpt = teams.getTeamByPlayer(p.getUniqueId());
        if (teamOpt.isEmpty()) return;

        KMCTeam team = teamOpt.get();
        event.setCancelled(true);

        String msg = team.getColouredName() + " §7[TC] §f" + p.getName() + "§7: §f" + event.getMessage();

        plugin.getServer().getOnlinePlayers().stream()
                .filter(op -> team.hasMember(op.getUniqueId()))
                .forEach(op -> op.sendMessage(msg));

        plugin.getLogger().info("[TeamChat/" + team.getId() + "] " + p.getName() + ": " + event.getMessage());
    }
}
