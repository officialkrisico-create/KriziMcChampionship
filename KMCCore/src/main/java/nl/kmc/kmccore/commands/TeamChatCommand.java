package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/** /tc [message] — toggle team chat or send a quick team message */
public class TeamChatCommand implements CommandExecutor {

    private final KMCCore plugin;
    public TeamChatCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use team chat."); return true; }
        if (!player.hasPermission("kmc.teamchat")) { player.sendMessage(MessageUtil.get("no-permission")); return true; }

        PlayerData pd = plugin.getPlayerDataManager().get(player.getUniqueId());
        if (pd == null || !pd.hasTeam()) { player.sendMessage(MessageUtil.get("team.no-team")); return true; }

        if (args.length == 0) {
            // Toggle team chat mode
            pd.toggleTeamChat();
            player.sendMessage(pd.isTeamChatEnabled()
                    ? MessageUtil.get("team.chat-enabled")
                    : MessageUtil.get("team.chat-disabled"));
        } else {
            // Send immediate team message without toggling
            String msg = String.join(" ", args);
            plugin.getTeamManager().sendTeamChat(player, msg);
        }
        return true;
    }
}
