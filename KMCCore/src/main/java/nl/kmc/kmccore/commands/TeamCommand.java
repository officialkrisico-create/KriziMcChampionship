package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.TeamManager;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /kmcteam &lt;add|remove|list|info|chat|create|delete&gt; [args...]
 *
 * <p>Subcommands:
 * <ul>
 *   <li>add &lt;player&gt; &lt;team&gt;   — assign player to team</li>
 *   <li>remove &lt;player&gt;           — remove player from their team</li>
 *   <li>list                       — list all teams + members</li>
 *   <li>info &lt;team&gt;               — detailed team info</li>
 *   <li>chat                       — toggle team chat for yourself</li>
 *   <li><b>create &lt;id&gt; &lt;color&gt; &lt;displayName...&gt;</b> — NEW</li>
 *   <li><b>delete &lt;id&gt;</b>             — NEW</li>
 * </ul>
 */
public class TeamCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;
    public TeamCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "add" -> {
                if (!sender.hasPermission("kmc.team.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
                if (args.length < 3) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcteam add <speler> <team>")); return true; }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
                    sender.sendMessage(MessageUtil.get("player-not-found").replace("{player}", args[1]));
                    return true;
                }

                // Ensure PlayerData exists (needed for team tracking)
                String name = target.getName() != null ? target.getName() : args[1];
                plugin.getPlayerDataManager().getOrCreate(target.getUniqueId(), name);

                TeamManager.AddResult result = plugin.getTeamManager()
                        .addPlayerToTeam(target.getUniqueId(), args[2].toLowerCase());

                switch (result) {
                    case OK -> {
                        sender.sendMessage(MessageUtil.get("team.added")
                                .replace("{player}", name)
                                .replace("{team}", args[2]));
                        // Lobby TP happens inside addPlayerToTeam for online players
                    }
                    case ALREADY_IN_TEAM -> sender.sendMessage(MessageUtil.get("team.already-in-team").replace("{player}", name));
                    case TEAM_FULL       -> sender.sendMessage(MessageUtil.get("team.full")
                            .replace("{team}", args[2])
                            .replace("{max}", String.valueOf(plugin.getTeamManager().getMaxPlayersPerTeam())));
                    case TEAM_NOT_FOUND  -> sender.sendMessage(MessageUtil.get("team.not-found").replace("{team}", args[2]));
                }
            }

            case "remove" -> {
                if (!sender.hasPermission("kmc.team.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcteam remove <speler>")); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (target == null) { sender.sendMessage(MessageUtil.get("player-not-found").replace("{player}", args[1])); return true; }
                if (plugin.getTeamManager().removePlayerFromTeam(target.getUniqueId())) {
                    sender.sendMessage(MessageUtil.get("team.removed").replace("{player}", args[1]));
                } else {
                    sender.sendMessage(MessageUtil.get("team.not-in-team").replace("{player}", args[1]));
                }
            }

            case "info" -> {
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcteam info <team>")); return true; }
                KMCTeam team = plugin.getTeamManager().getTeam(args[1]);
                if (team == null) { sender.sendMessage(MessageUtil.get("team.not-found").replace("{team}", args[1])); return true; }
                sender.sendMessage(MessageUtil.color("&6═══ " + team.getColor() + team.getDisplayName() + " &6═══"));
                sender.sendMessage(MessageUtil.color("&7Punten: &e" + team.getPoints()));
                sender.sendMessage(MessageUtil.color("&7Wins: &a" + team.getWins()));
                sender.sendMessage(MessageUtil.color("&7Leden (" + team.getMemberCount() + "):"));
                for (UUID uuid : team.getMembers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    String nm = p != null ? p.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                    sender.sendMessage(MessageUtil.color("  &7- &f" + (nm != null ? nm : uuid.toString())));
                }
            }

            case "list" -> {
                sender.sendMessage(MessageUtil.color("&6═══ Teams ═══"));
                for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
                    sender.sendMessage(MessageUtil.color("&7- " + t.getColor() + t.getDisplayName()
                            + " &7(" + t.getMemberCount() + "/" + plugin.getTeamManager().getMaxPlayersPerTeam()
                            + ") - &e" + t.getPoints() + " pt"));
                }
            }

            case "chat" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                var pd = plugin.getPlayerDataManager().getOrCreate(p.getUniqueId(), p.getName());
                pd.toggleTeamChat();
                plugin.getDatabaseManager().savePlayer(pd);
                sender.sendMessage(MessageUtil.color("&7Team chat is nu &e"
                        + (pd.isTeamChatEnabled() ? "AAN" : "UIT")));
            }

            // ---------------- NEW: create team ----------------
            case "create" -> {
                if (!sender.hasPermission("kmc.team.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
                if (args.length < 4) {
                    sender.sendMessage(MessageUtil.color(
                            "&cGebruik: /kmcteam create <id> <color> <displayName>"));
                    sender.sendMessage(MessageUtil.color(
                            "&7Voorbeeld: /kmcteam create groene_geiten GREEN Groene Geiten"));
                    sender.sendMessage(MessageUtil.color(
                            "&7Beschikbare kleuren: RED, GOLD, YELLOW, GREEN, BLUE, AQUA, LIGHT_PURPLE, WHITE, DARK_PURPLE, DARK_GREEN, DARK_AQUA, DARK_BLUE, DARK_RED, GRAY, DARK_GRAY"));
                    return true;
                }

                String id = args[1].toLowerCase();
                ChatColor color;
                try { color = ChatColor.valueOf(args[2].toUpperCase()); }
                catch (IllegalArgumentException e) {
                    sender.sendMessage(MessageUtil.color("&cOngeldige kleur: " + args[2]));
                    return true;
                }

                if (!color.isColor()) {
                    sender.sendMessage(MessageUtil.color("&c" + args[2] + " is geen kleur (bold/italic/etc zijn geen kleuren)."));
                    return true;
                }

                // Join remaining args as display name
                StringBuilder displayName = new StringBuilder();
                for (int i = 3; i < args.length; i++) {
                    if (displayName.length() > 0) displayName.append(" ");
                    displayName.append(args[i]);
                }

                KMCTeam created = plugin.getTeamManager().createTeam(
                        id, displayName.toString(), color, color.toString());
                if (created == null) {
                    sender.sendMessage(MessageUtil.color("&cEen team met id '" + id + "' bestaat al."));
                } else {
                    sender.sendMessage(MessageUtil.color("&a✔ Team aangemaakt: "
                            + color + displayName + "&a (id: " + id + ")"));
                }
            }

            // ---------------- NEW: delete team ----------------
            case "delete" -> {
                if (!sender.hasPermission("kmc.team.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcteam delete <id>")); return true; }

                String id = args[1].toLowerCase();
                KMCTeam t = plugin.getTeamManager().getTeam(id);
                if (t == null) {
                    sender.sendMessage(MessageUtil.color("&cTeam '" + id + "' niet gevonden."));
                    return true;
                }

                // Confirm if team has members
                if (t.getMemberCount() > 0
                        && (args.length < 3 || !args[2].equalsIgnoreCase("confirm"))) {
                    sender.sendMessage(MessageUtil.color(
                            "&c⚠ Team heeft " + t.getMemberCount()
                            + " leden. Gebruik &e/kmcteam delete " + id + " confirm &com door te gaan."));
                    return true;
                }

                String display = t.getDisplayName();
                boolean ok = plugin.getTeamManager().deleteTeam(id);
                if (ok) sender.sendMessage(MessageUtil.color("&a✔ Team '" + display + "' verwijderd."));
                else    sender.sendMessage(MessageUtil.color("&cVerwijderen mislukt."));
            }

            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.color("&cGebruik: /kmcteam <add|remove|list|info|chat|create|delete>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return List.of("add","remove","list","info","chat","create","delete").stream()
                .filter(o -> o.startsWith(args[0].toLowerCase())).collect(Collectors.toList());

        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());

        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("delete")))
            return plugin.getTeamManager().getAllTeams().stream()
                    .map(t -> t.getId()).collect(Collectors.toList());

        if (args.length == 3 && args[0].equalsIgnoreCase("add"))
            return plugin.getTeamManager().getAllTeams().stream()
                    .map(t -> t.getId()).collect(Collectors.toList());

        if (args.length == 3 && args[0].equalsIgnoreCase("create"))
            return Arrays.stream(ChatColor.values()).filter(ChatColor::isColor)
                    .map(ChatColor::name).collect(Collectors.toList());

        return List.of();
    }
}
