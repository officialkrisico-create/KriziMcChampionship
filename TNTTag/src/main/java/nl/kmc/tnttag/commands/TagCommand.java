package nl.kmc.tnttag.commands;

import nl.kmc.tnttag.TNTTagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /tnttag command — admin setup + game control.
 *
 * <p>Setup workflow:
 * <pre>
 *   /tnttag setworld &lt;world&gt;
 *   /tnttag addspawn               (× number of player slots)
 *   /tnttag setvoidy &lt;y&gt;
 *   /tnttag status                 (verify)
 *   /tnttag start
 * </pre>
 */
public class TagCommand implements CommandExecutor, TabCompleter {

    private final TNTTagPlugin plugin;

    public TagCommand(TNTTagPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("tnttag.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "TNT Tag gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /tnttag setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden."); return true; }
                plugin.getArenaManager().setWorld(w);
                sender.sendMessage(ChatColor.GREEN + "World ingesteld op " + w.getName());
            }
            case "addspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Spawn #"
                        + plugin.getArenaManager().getArena().getSpawns().size()
                        + " toegevoegd.");
            }
            case "clearspawns" -> {
                plugin.getArenaManager().clearSpawns();
                sender.sendMessage(ChatColor.GREEN + "Spawns gewist.");
            }
            case "setvoidy" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /tnttag setvoidy <y>"); return true; }
                try {
                    int y = Integer.parseInt(args[1]);
                    plugin.getArenaManager().setVoidY(y);
                    sender.sendMessage(ChatColor.GREEN + "Void Y ingesteld op " + y);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== TNT Tag Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                sender.sendMessage(ChatColor.YELLOW + "Round: " + plugin.getGameManager().getCurrentRound());
                for (String line : plugin.getArenaManager().getArena().getReadinessReport().split("\n")) {
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
        s.sendMessage(ChatColor.GOLD + "=== TNT Tag ===");
        s.sendMessage(ChatColor.YELLOW + "/tnttag start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/tnttag setworld <world>");
        s.sendMessage(ChatColor.YELLOW + "/tnttag addspawn | clearspawns");
        s.sendMessage(ChatColor.YELLOW + "/tnttag setvoidy <y>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setworld", "addspawn", "clearspawns",
                    "setvoidy", "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld")) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
