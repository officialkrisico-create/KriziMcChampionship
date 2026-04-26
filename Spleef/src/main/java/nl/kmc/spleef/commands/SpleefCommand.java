package nl.kmc.spleef.commands;

import nl.kmc.spleef.SpleefPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /spleef (or /sp) — admin setup + control.
 *
 * <p>Setup workflow:
 * <pre>
 *   1. /spleef setworld &lt;worldname&gt;
 *   2. Stand at one corner of the floor area, /spleef setlayer pos1
 *   3. Stand at the OPPOSITE corner of the floor area, /spleef setlayer pos2
 *      (use the same Y level — that becomes the floor level)
 *   4. Stand at each desired player spawn (above the floor), /spleef addspawn
 *   5. /spleef setvoidy &lt;y&gt; — Y level below which players fall to elimination
 *      (default: floor Y - 10)
 *   6. /spleef status to verify
 * </pre>
 */
public class SpleefCommand implements CommandExecutor, TabCompleter {

    private final SpleefPlugin plugin;

    public SpleefCommand(SpleefPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("spleef.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Spleef wordt gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /spleef setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden."); return true; }
                plugin.getArenaManager().setWorld(w);
                sender.sendMessage(ChatColor.GREEN + "World ingesteld op " + w.getName());
            }
            case "setlayer" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /spleef setlayer <pos1|pos2>"); return true; }
                var loc = p.getLocation();
                if (args[1].equalsIgnoreCase("pos1")) {
                    plugin.getArenaManager().setLayerPos1(
                            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    sender.sendMessage(ChatColor.GREEN + "pos1 ingesteld op ("
                            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")");
                    sender.sendMessage(ChatColor.GRAY + "Stand nu op de overkant en doe /spleef setlayer pos2");
                } else if (args[1].equalsIgnoreCase("pos2")) {
                    if (!plugin.getArenaManager().hasPos1()) {
                        sender.sendMessage(ChatColor.RED + "Doe eerst /spleef setlayer pos1");
                        return true;
                    }
                    plugin.getArenaManager().setLayerPos2(
                            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    sender.sendMessage(ChatColor.GREEN + "pos2 ingesteld — vloer is opgeslagen!");
                } else {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /spleef setlayer <pos1|pos2>");
                }
            }
            case "addspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addPlayerSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Player spawn #"
                        + plugin.getArenaManager().getArena().getPlayerSpawns().size()
                        + " toegevoegd.");
            }
            case "clearspawns" -> {
                plugin.getArenaManager().clearPlayerSpawns();
                sender.sendMessage(ChatColor.GREEN + "Alle player spawns gewist.");
            }
            case "setvoidy" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /spleef setvoidy <y>"); return true; }
                try {
                    int y = Integer.parseInt(args[1]);
                    plugin.getArenaManager().setVoidY(y);
                    sender.sendMessage(ChatColor.GREEN + "Void Y ingesteld op " + y);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Spleef Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                for (String line : plugin.getArenaManager().getArena().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
                }
                sender.sendMessage(ChatColor.GRAY + "Floor blocks placed: "
                        + plugin.getFloorManager().getRemainingBlockCount());
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getArenaManager().load();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Spleef ===");
        s.sendMessage(ChatColor.YELLOW + "/spleef start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/spleef setworld <world>");
        s.sendMessage(ChatColor.YELLOW + "/spleef setlayer <pos1|pos2>");
        s.sendMessage(ChatColor.YELLOW + "/spleef addspawn | clearspawns");
        s.sendMessage(ChatColor.YELLOW + "/spleef setvoidy <y>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setworld", "setlayer", "addspawn",
                    "clearspawns", "setvoidy", "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld")) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setlayer")) {
            return List.of("pos1", "pos2").stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
