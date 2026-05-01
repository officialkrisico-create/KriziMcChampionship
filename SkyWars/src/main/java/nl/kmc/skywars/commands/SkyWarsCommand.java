package nl.kmc.skywars.commands;

import nl.kmc.skywars.SkyWarsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /skywars command — admin setup + game control.
 */
public class SkyWarsCommand implements CommandExecutor, TabCompleter {

    private final SkyWarsPlugin plugin;

    public SkyWarsCommand(SkyWarsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("skywars.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "SkyWars gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World niet gevonden."); return true; }
                plugin.getArenaManager().setWorld(w);
                sender.sendMessage(ChatColor.GREEN + "World ingesteld op " + w.getName());
            }
            case "setvoidy" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars setvoidy <y>"); return true; }
                try {
                    plugin.getArenaManager().setVoidYLevel(Integer.parseInt(args[1]));
                    sender.sendMessage(ChatColor.GREEN + "Void Y ingesteld.");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "addisland" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars addisland <id> [radius]"); return true; }
                int radius = args.length >= 3 ? parseInt(args[2], 8) : 8;
                plugin.getArenaManager().addIsland(args[1], p.getLocation(), radius);
                sender.sendMessage(ChatColor.GREEN + "Island '" + args[1] + "' toegevoegd op huidige plek "
                        + "(zoekradius: " + radius + ").");
            }
            case "removeisland" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars removeisland <id>"); return true; }
                plugin.getArenaManager().removeIsland(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Island verwijderd.");
            }
            case "bindislandteam" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /skywars bindislandteam <island> [kmcTeamId]"); return true;
                }
                String kmcTeamId = args.length >= 3 ? args[2] : null;
                if (plugin.getArenaManager().setIslandTeam(args[1], kmcTeamId)) {
                    if (kmcTeamId == null) {
                        sender.sendMessage(ChatColor.GREEN + "Island '" + args[1] + "' team-binding verwijderd.");
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Island '" + args[1]
                                + "' nu gebonden aan team '" + kmcTeamId + "'.");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Island '" + args[1] + "' bestaat niet.");
                }
            }
            case "addplayerspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars addplayerspawn <island>"); return true; }
                if (plugin.getArenaManager().addPlayerSpawn(args[1], p.getLocation())) {
                    var island = plugin.getArenaManager().getIsland(args[1]);
                    sender.sendMessage(ChatColor.GREEN + "Player-spawn toegevoegd op island '"
                            + args[1] + "' (totaal: " + island.playerSpawnCount() + ").");
                } else {
                    sender.sendMessage(ChatColor.RED + "Island '" + args[1] + "' bestaat niet.");
                }
            }
            case "clearplayerspawns" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars clearplayerspawns <island>"); return true; }
                if (plugin.getArenaManager().clearPlayerSpawns(args[1])) {
                    sender.sendMessage(ChatColor.GREEN + "Player-spawns gewist op island '" + args[1] + "'.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Island '" + args[1] + "' bestaat niet.");
                }
            }
            case "listislands" -> {
                var islands = plugin.getArenaManager().getIslands();
                if (islands.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Geen islands."); return true; }
                sender.sendMessage(ChatColor.GOLD + "=== Islands (" + islands.size() + ") ===");
                for (var i : islands.values()) {
                    var s = i.getSpawn();
                    String team = i.getTeamId() != null ? " &btm:" + i.getTeamId() : "";
                    String spawns = i.playerSpawnCount() > 0
                            ? " &7(" + i.playerSpawnCount() + " player-spawns)"
                            : "";
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&e" + i.getId() + " &7— "
                            + s.getBlockX() + "," + s.getBlockY() + "," + s.getBlockZ()
                            + " (radius " + i.getChestSearchRadius() + ")"
                            + team + spawns));
                }
            }
            case "addmidring" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars addmidring <id> [radius]"); return true; }
                int radius = args.length >= 3 ? parseInt(args[2], 6) : 6;
                plugin.getArenaManager().addMidRingIsland(args[1], p.getLocation(), radius);
                sender.sendMessage(ChatColor.GREEN + "Mid-ring island '" + args[1]
                        + "' toegevoegd op huidige plek (zoekradius: " + radius + ").");
            }
            case "removemidring" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars removemidring <id>"); return true; }
                plugin.getArenaManager().removeMidRingIsland(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Mid-ring island verwijderd.");
            }
            case "listmidring" -> {
                var mr = plugin.getArenaManager().getMidRingIslands();
                if (mr.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Geen mid-ring islands."); return true; }
                sender.sendMessage(ChatColor.GOLD + "=== Mid-Ring Islands (" + mr.size() + ") ===");
                for (var i : mr.values()) {
                    var s = i.getSpawn();
                    sender.sendMessage(ChatColor.YELLOW + i.getId() + ChatColor.GRAY + " — "
                            + s.getBlockX() + "," + s.getBlockY() + "," + s.getBlockZ()
                            + " (radius " + i.getChestSearchRadius() + ")");
                }
            }
            case "setmiddle" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().setMiddleSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Middle ingesteld op huidige plek.");
            }
            case "setmidradius" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /skywars setmidradius <r>"); return true; }
                plugin.getArenaManager().setMiddleRadius(parseInt(args[1], 6));
                sender.sendMessage(ChatColor.GREEN + "Middle search radius: " + args[1]);
            }
            case "stockchests" -> {
                int n = plugin.getChestStocker().stockAll();
                sender.sendMessage(ChatColor.GREEN + "" + n + " chests gevuld.");
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== SkyWars Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getArenaManager().load();
                plugin.getChestStocker().loadLootTables();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== SkyWars ===");
        s.sendMessage(ChatColor.YELLOW + "/skywars start | stop | status | reload | stockchests");
        s.sendMessage(ChatColor.YELLOW + "/skywars setworld <world> | setvoidy <y>");
        s.sendMessage(ChatColor.YELLOW + "/skywars addisland <id> [radius] | removeisland <id> | listislands");
        s.sendMessage(ChatColor.YELLOW + "/skywars setmiddle | setmidradius <r>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setworld", "setvoidy",
                    "addisland", "removeisland", "listislands",
                    "setmiddle", "setmidradius", "stockchests",
                    "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld")) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("removeisland")) {
            return plugin.getArenaManager().getIslands().keySet().stream()
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
