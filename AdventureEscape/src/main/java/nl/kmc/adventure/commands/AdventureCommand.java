package nl.kmc.adventure.commands;

import nl.kmc.adventure.AdventureEscapePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /adventure or /ae — all setup and control commands in one.
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>/ae setworld &lt;worldName&gt; — set the race world</li>
 *   <li>Go TO that world, then run the rest:</li>
 *   <li>/ae setspawn — add spawn point (call for each racer slot)</li>
 *   <li>/ae setstartline pos1 — first corner of start box</li>
 *   <li>/ae setstartline pos2 — second corner</li>
 *   <li>/ae setfinishline pos1 — first corner of finish box</li>
 *   <li>/ae setfinishline pos2 — second corner</li>
 *   <li>/ae setlaps &lt;n&gt; — number of laps</li>
 *   <li>/ae status — verify setup is complete</li>
 *   <li>/ae start — launch the race</li>
 * </ol>
 */
public class AdventureCommand implements CommandExecutor, TabCompleter {

    private final AdventureEscapePlugin plugin;

    public AdventureCommand(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("adventure.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "start" -> {
                String error = plugin.getRaceManager().startCountdown();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Race gestart!");
            }
            case "stop" -> {
                plugin.getRaceManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Race gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /ae setworld <worldName>");
                    return true;
                }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) {
                    sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden.");
                    return true;
                }
                plugin.getArenaManager().setRaceWorld(w);
                sender.sendMessage(ChatColor.GREEN + "Race world ingesteld op " + w.getName());
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
            case "setstartline" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setstartline pos1|pos2"); return true; }
                if (args[1].equalsIgnoreCase("pos1")) {
                    plugin.getArenaManager().setStartlinePos1(p.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Start pos1 ingesteld.");
                } else if (args[1].equalsIgnoreCase("pos2")) {
                    plugin.getArenaManager().setStartlinePos2(p.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Start pos2 ingesteld.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Gebruik pos1 of pos2");
                }
            }
            case "setfinishline" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setfinishline pos1|pos2"); return true; }
                if (args[1].equalsIgnoreCase("pos1")) {
                    plugin.getArenaManager().setFinishlinePos1(p.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Finish pos1 ingesteld.");
                } else if (args[1].equalsIgnoreCase("pos2")) {
                    plugin.getArenaManager().setFinishlinePos2(p.getLocation());
                    sender.sendMessage(ChatColor.GREEN + "Finish pos2 ingesteld.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Gebruik pos1 of pos2");
                }
            }
            case "setlaps" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setlaps <n>"); return true; }
                try {
                    int n = Integer.parseInt(args[1]);
                    plugin.getArenaManager().setLaps(n);
                    sender.sendMessage(ChatColor.GREEN + "Laps ingesteld op " + n);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Adventure Escape Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getRaceManager().getState());
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.GRAY + line);
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getArenaManager().load();
                plugin.getEffectBlockManager().load();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }
            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","stop","setworld","setspawn","clearspawns",
                    "setstartline","setfinishline","setlaps","status","reload")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 &&
                (args[0].equalsIgnoreCase("setstartline") || args[0].equalsIgnoreCase("setfinishline")))
            return List.of("pos1","pos2").stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld"))
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.RED + "Gebruik: /ae <start|stop|setworld|setspawn|setstartline|setfinishline|setlaps|status|reload>");
    }
}
