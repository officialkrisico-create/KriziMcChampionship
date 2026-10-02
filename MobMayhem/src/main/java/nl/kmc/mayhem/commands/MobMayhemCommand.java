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
                if (plugin.getGameManagerV2() != null) {
                    if (!plugin.getGameManagerV2().start())
                        plugin.getGameManagerV2().reportArenaIssues(sender);
                    else sender.sendMessage(ChatColor.GREEN + "Mob Mayhem wordt gestart!");
                } else {
                    sender.sendMessage(ChatColor.RED + "V2 niet beschikbaar.");
                }
            }
            case "stop" -> {
                if (plugin.getGameManagerV2() != null) plugin.getGameManagerV2().end();
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
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                var am = plugin.getArenaManager();
                String problem = am.templateWorldProblem(p);
                if (problem != null) { sender.sendMessage(ChatColor.RED + problem); return true; }
                boolean first = args[0].equalsIgnoreCase("pos1");
                if (first) am.setPos1(p.getLocation()); else am.setPos2(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Arena-hoek " + (first ? "1" : "2") + " = blok "
                        + am.describePos(first) + ChatColor.GRAY + " (het blok waar je op staat).");
                if (am.isBoxSet()) {
                    int[] s = am.getBoxSize();
                    sender.sendMessage(ChatColor.GRAY + "Box: " + s[0] + "x" + s[1] + "x" + s[2] + " blokken.");
                }
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
            case "addpowerupspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addPowerupSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Powerup-spawn #"
                        + plugin.getArenaManager().getPowerupSpawnCount() + " toegevoegd.");
            }
            case "clearpowerupspawns" -> {
                plugin.getArenaManager().clearPowerupSpawns();
                sender.sendMessage(ChatColor.GREEN + "Alle powerup-spawns gewist.");
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Mob Mayhem Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + (plugin.getGameManagerV2() != null ? plugin.getGameManagerV2().getState().toString() : "IDLE"));
                String tname = plugin.getConfig().getString("world.template-name", "mm_template");
                boolean texists = new File(Bukkit.getWorldContainer(), tname).isDirectory();
                sender.sendMessage(ChatColor.GRAY + "Template: " + tname
                        + (texists ? " &a✔" : " &c✘ (bestaat niet)"));
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
                }
                boolean voidLoaded = Bukkit.getWorld(plugin.getVoidWorldManager().getVoidWorldName()) != null;
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Void world: &f"
                        + plugin.getVoidWorldManager().getVoidWorldName()
                        + (voidLoaded ? " &a✔" : " &7(nog niet aangemaakt — wordt gemaakt bij eerste /mm start)")));
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
        s.sendMessage(ChatColor.YELLOW + "/mm pos1 | pos2 (arena-hoeken, in de template world)");
        s.sendMessage(ChatColor.YELLOW + "/mm setspawn (player)");
        s.sendMessage(ChatColor.YELLOW + "/mm addmobspawn (mob spawn point)");
        s.sendMessage(ChatColor.YELLOW + "/mm clearmobspawns");
        s.sendMessage(ChatColor.YELLOW + "/mm addpowerupspawn (optioneel)");
        s.sendMessage(ChatColor.YELLOW + "/mm clearpowerupspawns");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "settemplate", "pos1", "pos2", "setspawn",
                    "addmobspawn", "clearmobspawns", "addpowerupspawn", "clearpowerupspawns",
                    "status", "reload").stream()
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
