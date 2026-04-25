package nl.kmc.bingo.commands;

import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.util.CardGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /bingo — admin + player commands.
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>Build a fresh template world (e.g. /mv create bingo_template normal)</li>
 *   <li>/bingo settemplate bingo_template</li>
 *   <li>/bingo setspawn — while standing where players should spawn IN that template world</li>
 *   <li>/bingo start — manual test, OR /kmcauto start</li>
 * </ol>
 */
public class BingoCommand implements CommandExecutor, TabCompleter {

    private final BingoPlugin plugin;

    public BingoCommand(BingoPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            // Default: open card if player is in a game
            if (sender instanceof Player p && plugin.getGameManager().isActive()) {
                CardGUI.open(plugin, p);
                return true;
            }
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "card", "view" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (!plugin.getGameManager().isActive()) {
                    sender.sendMessage(ChatColor.RED + "Geen Bingo game actief.");
                    return true;
                }
                CardGUI.open(plugin, p);
            }

            case "start" -> {
                if (!sender.hasPermission("bingo.admin")) {
                    sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true;
                }
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Bingo wordt gestart!");
            }

            case "stop" -> {
                if (!sender.hasPermission("bingo.admin")) {
                    sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true;
                }
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Bingo gestopt.");
            }

            case "settemplate" -> {
                if (!sender.hasPermission("bingo.admin")) {
                    sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /bingo settemplate <worldName>");
                    return true;
                }
                if (Bukkit.getWorld(args[1]) == null
                        && !new java.io.File(Bukkit.getWorldContainer(), args[1]).isDirectory()) {
                    sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden.");
                    return true;
                }
                plugin.getWorldManager().setTemplateWorld(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Template world ingesteld op " + args[1]);
            }

            case "setspawn" -> {
                if (!sender.hasPermission("bingo.admin")) {
                    sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true;
                }
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                var loc = p.getLocation();
                plugin.getConfig().set("world.default-spawn.x", loc.getX());
                plugin.getConfig().set("world.default-spawn.y", loc.getY());
                plugin.getConfig().set("world.default-spawn.z", loc.getZ());
                plugin.getConfig().set("world.default-spawn.yaw", loc.getYaw());
                plugin.getConfig().set("world.default-spawn.pitch", loc.getPitch());
                plugin.saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Spawn locatie opgeslagen ("
                        + (int)loc.getX() + ", " + (int)loc.getY() + ", " + (int)loc.getZ() + ")");
                sender.sendMessage(ChatColor.GRAY + "Tip: zorg dat je in de template world stond.");
            }

            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Bingo Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                sender.sendMessage(ChatColor.GRAY + "Template world: "
                        + plugin.getWorldManager().getTemplateWorldName()
                        + (plugin.getWorldManager().templateExists() ? " &a✔" : " &c✘ (bestaat niet)"));
                if (plugin.getWorldManager().hasGameWorld()) {
                    sender.sendMessage(ChatColor.GRAY + "Game world: "
                            + plugin.getWorldManager().getGameWorld().getName());
                }
                sender.sendMessage(ChatColor.GRAY + "Pool size: "
                        + plugin.getConfig().getStringList("card.collect-pool").size() + " items");
            }

            case "reload" -> {
                if (!sender.hasPermission("bingo.admin")) {
                    sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true;
                }
                plugin.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }

            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Bingo ===");
        s.sendMessage(ChatColor.YELLOW + "/bingo card " + ChatColor.GRAY + "— bekijk je team's bingo card");
        if (s.hasPermission("bingo.admin")) {
            s.sendMessage(ChatColor.YELLOW + "/bingo start " + ChatColor.GRAY + "— start een game");
            s.sendMessage(ChatColor.YELLOW + "/bingo stop " + ChatColor.GRAY + "— stop de game");
            s.sendMessage(ChatColor.YELLOW + "/bingo settemplate <world> " + ChatColor.GRAY + "— stel template world in");
            s.sendMessage(ChatColor.YELLOW + "/bingo setspawn " + ChatColor.GRAY + "— stel spawn in (op huidige plek)");
            s.sendMessage(ChatColor.YELLOW + "/bingo status " + ChatColor.GRAY + "— verifieer setup");
            s.sendMessage(ChatColor.YELLOW + "/bingo reload " + ChatColor.GRAY + "— config herladen");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("card"));
            if (s.hasPermission("bingo.admin"))
                opts.addAll(List.of("start", "stop", "settemplate", "setspawn", "status", "reload"));
            return opts.stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("settemplate")) {
            return Bukkit.getWorlds().stream().map(org.bukkit.World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
