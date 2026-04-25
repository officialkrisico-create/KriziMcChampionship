package nl.kmc.adventure.commands;

import nl.kmc.adventure.AdventureEscapePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /adventure or /ae command. Setup + control + checkpoint editing.
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>/ae setworld &lt;name&gt;</li>
 *   <li>/ae setspawn (call once per slot)</li>
 *   <li>/ae setstartline pos1 / pos2</li>
 *   <li>/ae setfinishline pos1 / pos2</li>
 *   <li>/ae setcheckpoint &lt;n&gt; pos1 / pos2  ← new, repeat for each cp</li>
 *   <li>/ae setlaps &lt;n&gt;</li>
 *   <li>/ae status to verify</li>
 * </ol>
 */
public class AdventureCommand implements CommandExecutor, TabCompleter {

    private final AdventureEscapePlugin plugin;

    /** Temporary first-corner storage for /ae setcheckpoint pos1. Key = player uuid + cp number. */
    private final Map<String, org.bukkit.Location> pendingCpCorners = new HashMap<>();

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
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setworld <worldName>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden."); return true; }
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
            case "setstartline" -> setLine(sender, args, true);
            case "setfinishline" -> setLine(sender, args, false);
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

            // ---- CHECKPOINTS (new) ----
            case "setcheckpoint" -> handleSetCheckpoint(sender, args);
            case "removecheckpoint" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae removecheckpoint <n>"); return true; }
                try {
                    int n = Integer.parseInt(args[1]);
                    plugin.getArenaManager().removeCheckpoint(n);
                    sender.sendMessage(ChatColor.GREEN + "Checkpoint " + n + " verwijderd.");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
                }
            }
            case "clearcheckpoints" -> {
                plugin.getArenaManager().clearCheckpoints();
                sender.sendMessage(ChatColor.GREEN + "Alle checkpoints gewist.");
            }
            case "listcheckpoints" -> {
                var cps = plugin.getArenaManager().getCheckpoints();
                if (cps.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Geen checkpoints ingesteld.");
                } else {
                    sender.sendMessage(ChatColor.GOLD + "=== Checkpoints (" + cps.size() + ") ===");
                    for (var cp : cps) {
                        var p1 = cp.getPos1();
                        sender.sendMessage(ChatColor.YELLOW + "#" + cp.getIndex() + ChatColor.GRAY
                                + " bij " + p1.getBlockX() + "," + p1.getBlockY() + "," + p1.getBlockZ());
                    }
                }
            }

            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Adventure Escape Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getRaceManager().getState());
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
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

    private void setLine(CommandSender sender, String[] args, boolean start) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
        String name = start ? "Start" : "Finish";
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae set" + name.toLowerCase() + "line pos1|pos2"); return; }
        if (args[1].equalsIgnoreCase("pos1")) {
            if (start) plugin.getArenaManager().setStartlinePos1(p.getLocation());
            else       plugin.getArenaManager().setFinishlinePos1(p.getLocation());
            sender.sendMessage(ChatColor.GREEN + name + " pos1 ingesteld.");
        } else if (args[1].equalsIgnoreCase("pos2")) {
            if (start) plugin.getArenaManager().setStartlinePos2(p.getLocation());
            else       plugin.getArenaManager().setFinishlinePos2(p.getLocation());
            sender.sendMessage(ChatColor.GREEN + name + " pos2 ingesteld.");
        } else {
            sender.sendMessage(ChatColor.RED + "Gebruik pos1 of pos2");
        }
    }

    /**
     * /ae setcheckpoint &lt;n&gt; pos1
     * /ae setcheckpoint &lt;n&gt; pos2
     *
     * Stores pos1 in memory; when pos2 is set, finalises the checkpoint.
     */
    private void handleSetCheckpoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /ae setcheckpoint <nummer> <pos1|pos2>");
            return;
        }

        int n;
        try { n = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
            return;
        }
        if (n < 1) {
            sender.sendMessage(ChatColor.RED + "Checkpoint nummer moet 1 of hoger zijn.");
            return;
        }

        String corner = args[2].toLowerCase();
        String key = p.getUniqueId() + ":" + n;

        if (corner.equals("pos1")) {
            pendingCpCorners.put(key, p.getLocation());
            sender.sendMessage(ChatColor.GREEN + "Checkpoint #" + n
                    + " pos1 opgeslagen. Loop naar de andere hoek en doe /ae setcheckpoint " + n + " pos2");
        } else if (corner.equals("pos2")) {
            org.bukkit.Location pos1 = pendingCpCorners.remove(key);
            if (pos1 == null) {
                sender.sendMessage(ChatColor.RED + "Je moet eerst pos1 zetten met /ae setcheckpoint " + n + " pos1");
                return;
            }
            plugin.getArenaManager().addOrUpdateCheckpoint(n, pos1, p.getLocation());
            sender.sendMessage(ChatColor.GREEN + "✔ Checkpoint #" + n + " ingesteld!");
        } else {
            sender.sendMessage(ChatColor.RED + "Gebruik pos1 of pos2");
        }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.RED + "Gebruik: /ae <start|stop|setworld|setspawn|clearspawns"
                + "|setstartline|setfinishline|setcheckpoint|removecheckpoint|clearcheckpoints"
                + "|listcheckpoints|setlaps|status|reload>");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","stop","setworld","setspawn","clearspawns",
                    "setstartline","setfinishline",
                    "setcheckpoint","removecheckpoint","clearcheckpoints","listcheckpoints",
                    "setlaps","status","reload")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("setcheckpoint"))
            return List.of("pos1","pos2").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
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
}
