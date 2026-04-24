package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.TeamManager;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /kmcteam <add|remove|list|info|chat> [player] [team]
 */
public class TeamCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public TeamCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.team")) {
            sender.sendMessage(MessageUtil.get("no-permission")); return true;
        }
        if (args.length == 0) {
            sender.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}", "/kmcteam <add|remove|list|info|chat> [player] [team]"));
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "add" -> {
                // /kmcteam add <player> <team>
                if (args.length < 3) { usage(sender, "/kmcteam add <player> <team>"); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(MessageUtil.get("player-not-found").replace("{player}", args[1])); return true; }

                // Ensure PlayerData exists
                plugin.getPlayerDataManager().getOrCreate(target.getUniqueId(), target.getName());

                TeamManager.AddResult result = plugin.getTeamManager().addPlayerToTeam(target.getUniqueId(), args[2].toLowerCase());
                switch (result) {
                    case OK -> {
                        sender.sendMessage(MessageUtil.get("team.added").replace("{player}", target.getName()).replace("{team}", args[2]));
                        target.sendMessage(MessageUtil.get("team.join-message").replace("{team}", args[2]));
                    }
                    case ALREADY_IN_TEAM -> sender.sendMessage(MessageUtil.get("team.already-in-team").replace("{player}", target.getName()));
                    case TEAM_FULL       -> sender.sendMessage(MessageUtil.get("team.full").replace("{team}", args[2]).replace("{max}", String.valueOf(plugin.getTeamManager().getMaxPlayersPerTeam())));
                    case TEAM_NOT_FOUND  -> sender.sendMessage(MessageUtil.get("team.not-found").replace("{team}", args[2]));
                }
            }

            case "remove" -> {
                // /kmcteam remove <player>
                if (args.length < 2) { usage(sender, "/kmcteam remove <player>"); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(MessageUtil.get("player-not-found").replace("{player}", args[1])); return true; }

                boolean removed = plugin.getTeamManager().removePlayerFromTeam(target.getUniqueId());
                if (removed) {
                    sender.sendMessage(MessageUtil.get("team.removed").replace("{player}", target.getName()).replace("{team}", "hun team"));
                    target.sendMessage(MessageUtil.get("team.leave-message").replace("{team}", "jouw team"));
                } else {
                    sender.sendMessage(MessageUtil.get("team.not-in-team").replace("{player}", target.getName()));
                }
            }

            case "info" -> {
                // /kmcteam info <team>
                if (args.length < 2) { usage(sender, "/kmcteam info <team>"); return true; }
                KMCTeam team = plugin.getTeamManager().getTeam(args[1].toLowerCase());
                if (team == null) { sender.sendMessage(MessageUtil.get("team.not-found").replace("{team}", args[1])); return true; }

                List<String> memberNames = team.getMembers().stream()
                        .map(uuid -> {
                            Player p = Bukkit.getPlayer(uuid);
                            if (p != null) return p.getName();
                            PlayerData pd = plugin.getPlayerDataManager().get(uuid);
                            return pd != null ? pd.getName() : uuid.toString().substring(0, 8);
                        }).collect(Collectors.toList());

                sender.sendMessage(MessageUtil.get("team.info-header").replace("{team_color}", team.getColor().toString()).replace("{team_name}", team.getDisplayName()));
                sender.sendMessage(MessageUtil.get("team.info-members").replace("{members}", memberNames.isEmpty() ? "Geen" : String.join(", ", memberNames)));
                sender.sendMessage(MessageUtil.get("team.info-points").replace("{points}", String.valueOf(team.getPoints())));
                sender.sendMessage(MessageUtil.get("team.info-wins").replace("{wins}", String.valueOf(team.getWins())));
            }

            case "list" -> {
                sender.sendMessage(MessageUtil.get("team.list-header"));
                for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
                    sender.sendMessage(MessageUtil.get("team.list-entry")
                            .replace("{team_color}", t.getColor().toString())
                            .replace("{team_name}", t.getDisplayName())
                            .replace("{count}", String.valueOf(t.getMemberCount()))
                            .replace("{max}", String.valueOf(plugin.getTeamManager().getMaxPlayersPerTeam())));
                }
            }

            case "chat" -> {
                // /kmcteam chat — toggle team chat for sender
                if (!(sender instanceof Player player)) { sender.sendMessage("Only players can toggle team chat."); return true; }
                PlayerData pd = plugin.getPlayerDataManager().get(player.getUniqueId());
                if (pd == null) { player.sendMessage(MessageUtil.get("team.no-team")); return true; }
                pd.toggleTeamChat();
                player.sendMessage(pd.isTeamChatEnabled() ? MessageUtil.get("team.chat-enabled") : MessageUtil.get("team.chat-disabled"));
            }

            default -> usage(sender, "/kmcteam <add|remove|list|info|chat> [player] [team]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return filter(List.of("add","remove","list","info","chat"), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("add"))
            return plugin.getTeamManager().getAllTeams().stream().map(t -> t.getId()).filter(id -> id.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s, String u) { s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}", u)); }
    private List<String> filter(List<String> opts, String prefix) { return opts.stream().filter(o -> o.startsWith(prefix.toLowerCase())).collect(Collectors.toList()); }
}
