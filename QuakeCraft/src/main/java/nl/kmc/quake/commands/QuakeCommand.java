package nl.kmc.quake.commands;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /quakecraft (alias /qc) — setup + control.
 */
public class QuakeCommand implements CommandExecutor, TabCompleter {

    private final QuakeCraftPlugin plugin;

    public QuakeCommand(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("quake.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startCountdown();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Game gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /qc setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden."); return true; }
                plugin.getArenaManager().setArenaWorld(w);
                sender.sendMessage(ChatColor.GREEN + "Arena world ingesteld op " + w.getName());
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addSpawn(p.getLocation());
                int count = plugin.getArenaManager().getSpawns().size();
                sender.sendMessage(ChatColor.GREEN + "Spawn #" + count + " toegevoegd.");
            }
            case "clearspawns" -> {
                plugin.getArenaManager().clearSpawns();
                sender.sendMessage(ChatColor.GREEN + "Alle spawns gewist.");
            }
            case "setpowerup" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /qc setpowerup <naam>"); return true; }
                plugin.getArenaManager().addPowerupLocation(args[1], p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Powerup locatie '" + args[1] + "' ingesteld.");
            }
            case "removepowerup" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /qc removepowerup <naam>"); return true; }
                plugin.getArenaManager().removePowerupLocation(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Powerup locatie '" + args[1] + "' verwijderd.");
            }
            case "clearpowerups" -> {
                plugin.getArenaManager().clearPowerupLocations();
                sender.sendMessage(ChatColor.GREEN + "Alle powerup locaties gewist.");
            }
            case "listpowerups" -> {
                var locs = plugin.getArenaManager().getPowerupLocations();
                if (locs.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Geen powerup locaties.");
                else {
                    sender.sendMessage(ChatColor.GOLD + "=== Powerup locaties (" + locs.size() + ") ===");
                    for (var e : locs.entrySet()) {
                        var loc = e.getValue();
                        sender.sendMessage(ChatColor.YELLOW + e.getKey() + ChatColor.GRAY
                                + " bij " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                    }
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== QuakeCraft Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
                }
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
        s.sendMessage(ChatColor.RED + "Gebruik: /qc <start|stop|setworld|setspawn|clearspawns"
                + "|setpowerup|removepowerup|clearpowerups|listpowerups|status|reload>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","stop","setworld","setspawn","clearspawns",
                    "setpowerup","removepowerup","clearpowerups","listpowerups",
                    "status","reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld"))
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("removepowerup"))
            return plugin.getArenaManager().getPowerupLocations().keySet().stream()
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }
}
