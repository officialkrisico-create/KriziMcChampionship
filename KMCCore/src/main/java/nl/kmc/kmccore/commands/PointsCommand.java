package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/** /kmcpoints <set|add|remove> <team|player> <name> <amount> */
public class PointsCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;
    public PointsCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.points.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
        if (args.length < 4) { usage(sender); return true; }

        String action  = args[0].toLowerCase();
        String mode    = args[1].toLowerCase(); // "team" or "player"
        String name    = args[2];
        int amount;
        try { amount = Integer.parseInt(args[3]); } catch (NumberFormatException e) { sender.sendMessage(MessageUtil.get("invalid-number")); return true; }

        if (mode.equals("team")) {
            switch (action) {
                case "set"    -> { plugin.getPointsManager().setTeamPoints(name, amount); sender.sendMessage(MessageUtil.get("points.team-set").replace("{team}", name).replace("{amount}", String.valueOf(amount))); }
                case "add"    -> { plugin.getPointsManager().addTeamPoints(name, amount); sender.sendMessage(MessageUtil.get("points.team-add").replace("{team}", name).replace("{amount}", String.valueOf(amount))); }
                case "remove" -> { plugin.getPointsManager().removeTeamPoints(name, amount); sender.sendMessage(MessageUtil.get("points.team-remove").replace("{team}", name).replace("{amount}", String.valueOf(amount))); }
                default       -> usage(sender);
            }
        } else {
            Player target = Bukkit.getPlayer(name);
            if (target == null) { sender.sendMessage(MessageUtil.get("player-not-found").replace("{player}", name)); return true; }
            switch (action) {
                case "set"    -> { plugin.getPointsManager().setPlayerPoints(target.getUniqueId(), amount); sender.sendMessage(MessageUtil.get("points.player-set").replace("{player}", name).replace("{amount}", String.valueOf(amount))); }
                case "add"    -> { plugin.getPointsManager().addPlayerPoints(target.getUniqueId(), amount); sender.sendMessage(MessageUtil.get("points.player-add").replace("{player}", name).replace("{amount}", String.valueOf(amount))); }
                case "remove" -> { plugin.getPointsManager().removePlayerPoints(target.getUniqueId(), amount); sender.sendMessage(MessageUtil.get("points.player-remove").replace("{player}", name).replace("{amount}", String.valueOf(amount))); }
                default       -> usage(sender);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return List.of("set","add","remove").stream().filter(o -> o.startsWith(args[0])).collect(Collectors.toList());
        if (args.length == 2) return List.of("team","player").stream().filter(o -> o.startsWith(args[1])).collect(Collectors.toList());
        if (args.length == 3 && args[1].equalsIgnoreCase("team"))
            return plugin.getTeamManager().getAllTeams().stream().map(t -> t.getId()).filter(id -> id.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        if (args.length == 3 && args[1].equalsIgnoreCase("player"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) { s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}", "/kmcpoints <set|add|remove> <team|player> <name> <amount>")); }
}
