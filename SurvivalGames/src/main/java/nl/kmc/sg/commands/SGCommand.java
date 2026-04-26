package nl.kmc.sg.commands;

import nl.kmc.sg.SurvivalGamesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /sg command — admin setup + control.
 */
public class SGCommand implements CommandExecutor, TabCompleter {

    private final SurvivalGamesPlugin plugin;

    public SGCommand(SurvivalGamesPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("sg.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Survival Games gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /sg setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World niet gevonden."); return true; }
                plugin.getArenaManager().setWorld(w);
                sender.sendMessage(ChatColor.GREEN + "World ingesteld op " + w.getName());
            }
            case "setcornucopia" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().setCornucopia(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Cornucopia ingesteld op huidige plek.");
            }
            case "addpedestal" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addPedestal(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Pedestal #"
                        + plugin.getArenaManager().getArena().getSpawnPedestals().size()
                        + " toegevoegd.");
            }
            case "clearpedestals" -> {
                plugin.getArenaManager().clearPedestals();
                sender.sendMessage(ChatColor.GREEN + "Pedestals gewist.");
            }
            case "setborder" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /sg setborder <radius> [minRadius]"); return true; }
                try {
                    double r = Double.parseDouble(args[1]);
                    double minR = args.length >= 3 ? Double.parseDouble(args[2]) : r * 0.2;
                    plugin.getArenaManager().setBorder(r, minR);
                    sender.sendMessage(ChatColor.GREEN + "Border ingesteld: " + r + " → " + minR);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "setvoidy" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /sg setvoidy <y>"); return true; }
                try {
                    plugin.getArenaManager().setVoidY(Integer.parseInt(args[1]));
                    sender.sendMessage(ChatColor.GREEN + "Void Y ingesteld.");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "stockchests" -> {
                sender.sendMessage(ChatColor.YELLOW + "Stocking chests async — dit kan even duren.");
                plugin.getChestStocker().stockAllAsync(() -> 
                    sender.sendMessage(ChatColor.GREEN + "Stocking voltooid: "
                            + plugin.getChestStocker().getStockedCount() + " chests gevuld.")
                );
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Survival Games Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                for (String line : plugin.getArenaManager().getArena().getReadinessReport().split("\n")) {
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

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Survival Games ===");
        s.sendMessage(ChatColor.YELLOW + "/sg start | stop | status | reload | stockchests");
        s.sendMessage(ChatColor.YELLOW + "/sg setworld <world>");
        s.sendMessage(ChatColor.YELLOW + "/sg setcornucopia | addpedestal | clearpedestals");
        s.sendMessage(ChatColor.YELLOW + "/sg setborder <radius> [minRadius]");
        s.sendMessage(ChatColor.YELLOW + "/sg setvoidy <y>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setworld", "setcornucopia", "addpedestal",
                    "clearpedestals", "setborder", "setvoidy", "stockchests",
                    "status", "reload").stream()
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
