package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.TabListManager;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Routes chat messages:
 * - If team chat is on → sends team-only message with [TC][TeamName] prefix
 * - Otherwise → broadcasts globally with [TeamName] PlayerName: message format
 *
 * Uses Paper's AsyncChatEvent (Adventure API) for proper Component rendering.
 */
public class ChatListener implements Listener {

    private final KMCCore plugin;

    public ChatListener(KMCCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PlayerData pd = plugin.getPlayerDataManager().get(player.getUniqueId());

        // Extract plain text from the message component
        String rawMessage = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(event.message());

        TabListManager tlm = plugin.getTabListManager();

        if (pd != null && pd.isTeamChatEnabled()) {
            // Team chat — cancel global broadcast, send to team only
            event.setCancelled(true);

            KMCTeam team = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
            if (team == null) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "Je zit in geen team.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }

            Component msg = tlm.buildTeamChatMessage(player, team, rawMessage);

            // Send to all online teammates on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (java.util.UUID memberUuid : team.getMembers()) {
                    Player member = Bukkit.getPlayer(memberUuid);
                    if (member != null) member.sendMessage(msg);
                }
                // Also echo to console
                plugin.getLogger().info("[TC][" + team.getDisplayName() + "] " + player.getName() + ": " + rawMessage);
            });

        } else {
            // Global chat — replace the default renderer with our formatted component
            event.renderer((source, sourceDisplayName, message, viewer) ->
                    tlm.buildChatMessage(player, rawMessage));
        }
    }
}
