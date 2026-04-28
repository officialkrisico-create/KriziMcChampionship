package nl.kmc.adventure.commands;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.Checkpoint;
import nl.kmc.adventure.models.OutOfBoundsBox;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
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
 *
 * <p>Checkpoint setup:
 * <ol>
 *   <li>/ae setcheckpoint &lt;name&gt; — sets respawn at your location</li>
 *   <li>(optional) /ae setcheckpointtrigger &lt;name&gt; pos1|pos2 — region that auto-marks CP</li>
 *   <li>/ae setoutofbounds &lt;cpName&gt; &lt;boxName&gt; pos1|pos2 — define an OOB box</li>
 *   <li>/ae listcheckpoints — show all configured CPs and OOB boxes</li>
 * </ol>
 */
public class AdventureCommand implements CommandExecutor, TabCompleter {

    private final AdventureEscapePlugin plugin;

    /** Per-player staged positions for multi-step pos1/pos2 commands. */
    private final Map<UUID, Location> pendingOobPos1 = new HashMap<>();
    private final Map<UUID, String>   pendingOobKey  = new HashMap<>();
    private final Map<UUID, Location> pendingTriggerPos1 = new HashMap<>();
    private final Map<UUID, String>   pendingTriggerKey  = new HashMap<>();

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
                sender.sendMessage(ChatColor.YELLOW + "Race gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setworld <worldName>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "Wereld niet gevonden."); return true; }
                plugin.getArenaManager().setRaceWorld(w);
                sender.sendMessage(ChatColor.GREEN + "Race wereld ingesteld op " + w.getName());
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().addSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Spawn punt toegevoegd ("
                        + plugin.getArenaManager().getSpawns().size() + " totaal).");
            }
            case "clearspawns" -> {
                plugin.getArenaManager().clearSpawns();
                sender.sendMessage(ChatColor.YELLOW + "Alle spawns gewist.");
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

            // ----------------------------------------------------------------
            // Checkpoints + OOB
            // ----------------------------------------------------------------

            case "setcheckpoint" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setcheckpoint <name>"); return true; }
                Checkpoint cp = plugin.getCheckpointManager().setOrCreate(args[1], p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Checkpoint '" + cp.getName()
                        + "' ingesteld op je huidige locatie.");
            }
            case "setcheckpointtrigger" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae setcheckpointtrigger <cpName> <pos1|pos2>"); return true; }
                String cpName = args[1];
                String which = args[2].toLowerCase();
                Checkpoint cp = plugin.getCheckpointManager().get(cpName);
                if (cp == null) { sender.sendMessage(ChatColor.RED + "Checkpoint '" + cpName + "' bestaat niet."); return true; }

                if (which.equals("pos1")) {
                    pendingTriggerPos1.put(p.getUniqueId(), p.getLocation());
                    pendingTriggerKey.put(p.getUniqueId(), cpName.toLowerCase());
                    sender.sendMessage(ChatColor.YELLOW + "Trigger pos1 voor '" + cpName
                            + "' tijdelijk opgeslagen. Stel nu pos2 in.");
                } else if (which.equals("pos2")) {
                    Location p1 = pendingTriggerPos1.get(p.getUniqueId());
                    String key = pendingTriggerKey.get(p.getUniqueId());
                    if (p1 == null || !cpName.equalsIgnoreCase(key)) {
                        sender.sendMessage(ChatColor.RED + "Stel eerst pos1 in voor checkpoint '" + cpName + "'.");
                        return true;
                    }
                    cp.setTrigger(p1, p.getLocation());
                    plugin.getCheckpointManager().save();
                    pendingTriggerPos1.remove(p.getUniqueId());
                    pendingTriggerKey.remove(p.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Trigger regio voor '" + cpName + "' ingesteld.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Gebruik pos1 of pos2");
                }
            }
            case "setoutofbounds", "setoob" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /ae setoutofbounds <cpName> <boxName> <pos1|pos2>");
                    return true;
                }
                String cpName  = args[1];
                String boxName = args[2];
                String which   = args[3].toLowerCase();

                Checkpoint cp = plugin.getCheckpointManager().get(cpName);
                if (cp == null) { sender.sendMessage(ChatColor.RED + "Checkpoint '" + cpName + "' bestaat niet."); return true; }

                String stagingKey = cpName.toLowerCase() + ":" + boxName.toLowerCase();
                if (which.equals("pos1")) {
                    pendingOobPos1.put(p.getUniqueId(), p.getLocation());
                    pendingOobKey.put(p.getUniqueId(), stagingKey);
                    sender.sendMessage(ChatColor.YELLOW + "OOB pos1 tijdelijk opgeslagen voor '"
                            + cpName + "/" + boxName + "'. Stel nu pos2 in.");
                } else if (which.equals("pos2")) {
                    Location p1 = pendingOobPos1.get(p.getUniqueId());
                    String key = pendingOobKey.get(p.getUniqueId());
                    if (p1 == null || !stagingKey.equals(key)) {
                        sender.sendMessage(ChatColor.RED + "Stel eerst pos1 in voor '"
                                + cpName + "/" + boxName + "'.");
                        return true;
                    }
                    plugin.getCheckpointManager().addOob(cpName, boxName, p1, p.getLocation());
                    pendingOobPos1.remove(p.getUniqueId());
                    pendingOobKey.remove(p.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Out-of-bounds box '" + boxName
                            + "' toegevoegd aan checkpoint '" + cpName + "'.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Gebruik pos1 of pos2");
                }
            }
            case "listcheckpoints", "listcp" -> {
                Collection<Checkpoint> all = plugin.getCheckpointManager().getAll();
                if (all.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Geen checkpoints geconfigureerd.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Checkpoints (" + all.size() + ") ===");
                for (Checkpoint cp : all) {
                    Location r = cp.getRespawn();
                    sender.sendMessage(ChatColor.YELLOW + "• " + cp.getName()
                            + ChatColor.GRAY + " — respawn: ["
                            + (int) r.getX() + "," + (int) r.getY() + "," + (int) r.getZ() + "]"
                            + (cp.hasTrigger() ? ChatColor.GREEN + " ✔trigger" : ChatColor.DARK_GRAY + " (geen trigger)"));
                    for (OutOfBoundsBox box : cp.getOobBoxes()) {
                        sender.sendMessage(ChatColor.GRAY + "    └ OOB " + box.getName()
                                + " " + box.describe());
                    }
                }
            }
            case "removecheckpoint", "removecp" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae removecheckpoint <name>"); return true; }
                if (plugin.getCheckpointManager().remove(args[1])) {
                    sender.sendMessage(ChatColor.GREEN + "Checkpoint '" + args[1] + "' verwijderd.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Checkpoint niet gevonden.");
                }
            }
            case "removeoob" -> {
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Gebruik: /ae removeoob <cpName> <boxName>"); return true; }
                if (plugin.getCheckpointManager().removeOob(args[1], args[2])) {
                    sender.sendMessage(ChatColor.GREEN + "OOB box '" + args[2]
                            + "' van checkpoint '" + args[1] + "' verwijderd.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Niet gevonden.");
                }
            }

            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Adventure Escape Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getRaceManager().getState());
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.GRAY + line);
                }
                sender.sendMessage(ChatColor.GRAY + "Checkpoints: "
                        + plugin.getCheckpointManager().getAll().size());
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getArenaManager().load();
                plugin.getEffectBlockManager().load();
                plugin.getCheckpointManager().load();
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
                    "setstartline","setfinishline","setlaps","status","reload",
                    "setcheckpoint","setcheckpointtrigger","setoutofbounds","setoob",
                    "listcheckpoints","listcp","removecheckpoint","removecp","removeoob")
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

        // Suggest CP names for commands taking <cpName>
        if (args.length == 2 &&
                (args[0].equalsIgnoreCase("setcheckpointtrigger")
                 || args[0].equalsIgnoreCase("setoutofbounds")
                 || args[0].equalsIgnoreCase("setoob")
                 || args[0].equalsIgnoreCase("removecheckpoint")
                 || args[0].equalsIgnoreCase("removecp")
                 || args[0].equalsIgnoreCase("removeoob"))) {
            return plugin.getCheckpointManager().getAll().stream()
                    .map(Checkpoint::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        // pos1/pos2 hint
        if (args.length == 3 && args[0].equalsIgnoreCase("setcheckpointtrigger"))
            return List.of("pos1","pos2").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 4 && (args[0].equalsIgnoreCase("setoutofbounds") || args[0].equalsIgnoreCase("setoob")))
            return List.of("pos1","pos2").stream()
                    .filter(o -> o.startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());

        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== /ae commando's ===");
        s.sendMessage(ChatColor.YELLOW + "Race: " + ChatColor.GRAY + "start, stop, status, reload");
        s.sendMessage(ChatColor.YELLOW + "Arena: " + ChatColor.GRAY + "setworld, setspawn, clearspawns, setstartline, setfinishline, setlaps");
        s.sendMessage(ChatColor.YELLOW + "Checkpoints: " + ChatColor.GRAY + "setcheckpoint, setcheckpointtrigger, listcheckpoints, removecheckpoint");
        s.sendMessage(ChatColor.YELLOW + "Out-of-bounds: " + ChatColor.GRAY + "setoutofbounds <cpName> <boxName> <pos1|pos2>, removeoob");
    }
}
