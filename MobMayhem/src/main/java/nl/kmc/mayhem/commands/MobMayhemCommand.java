package nl.kmc.mayhem.commands;

import nl.kmc.mayhem.MobMayhemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * /mobmayhem (or /mm) — admin setup + control.
 *
 * <p>Setup workflow:
 * <pre>
 *   1. Build a fresh template world manually:
 *        /mv create mm_template normal
 *      OR a flat creative world. Build your arena there.
 *
 *   2. Tell the plugin which world is the template:
 *        /mm settemplate mm_template
 *
 *   3. Build the arena IN that world:
 *      - Set player spawn:    stand at desired spawn → /mm setspawn
 *      - Add mob spawns:      stand at each location → /mm addmobspawn
 *                             (need at least 4)
 *
 *   4. Verify:    /mm status
 *
 *   5. Test:      /mm start (manual) or /kmcauto start
 * </pre>
 */
public class MobMayhemCommand implements CommandExecutor, TabCompleter {

    private final MobMayhemPlugin plugin;

    public MobMayhemCommand(MobMayhemPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }

        if (!sender.hasPermission("mayhem.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Mob Mayhem wordt gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "settemplate" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /mm settemplate <worldName>");
                    return true;
                }
                if (Bukkit.getWorld(args[1]) == null
                        && !new File(Bukkit.getWorldContainer(), args[1]).isDirectory()) {
                    sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden.");
                    return true;
                }
                plugin.getConfig().set("world.template-name", args[1]);
                plugin.saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Template world ingesteld op " + args[1]);
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().setPlayerSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Player spawn ingesteld op je huidige locatie.");
                sender.sendMessage(ChatColor.GRAY + "Tip: zorg dat je in de template world stond.");
            }
            case "addmobspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addMobSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Mob spawn #"
                        + plugin.getArenaManager().getMobSpawnCount() + " toegevoegd.");
            }
            case "clearmobspawns" -> {
                plugin.getArenaManager().clearMobSpawns();
                sender.sendMessage(ChatColor.GREEN + "Alle mob spawns gewist.");
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Mob Mayhem Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                String tname = plugin.getConfig().getString("world.template-name", "mm_template");
                boolean texists = new File(Bukkit.getWorldContainer(), tname).isDirectory();
                sender.sendMessage(ChatColor.GRAY + "Template: " + tname
                        + (texists ? " &a✔" : " &c✘ (bestaat niet)"));
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
                }
                sender.sendMessage(ChatColor.GRAY + "Active clones: "
                        + plugin.getWorldCloner().getActiveClones().size());
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
        s.sendMessage(ChatColor.GOLD + "=== Mob Mayhem ===");
        s.sendMessage(ChatColor.YELLOW + "/mm start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/mm settemplate <world>");
        s.sendMessage(ChatColor.YELLOW + "/mm setspawn (player)");
        s.sendMessage(ChatColor.YELLOW + "/mm addmobspawn (mob spawn point)");
        s.sendMessage(ChatColor.YELLOW + "/mm clearmobspawns");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "settemplate", "setspawn",
                    "addmobspawn", "clearmobspawns", "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
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
