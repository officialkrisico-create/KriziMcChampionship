package nl.kmc.elytra.commands;

import nl.kmc.elytra.ElytraEndriumPlugin;
import nl.kmc.elytra.managers.CourseManager;
import nl.kmc.elytra.models.BoostHoop;
import nl.kmc.elytra.models.Checkpoint;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /elytraendrium (or /ee) — admin setup + start/stop.
 *
 * <p>Setup workflow:
 * <pre>
 *   /ee setworld &lt;world&gt;
 *   /ee setlaunch                            ← stand at elevated launch tower
 *   /ee cp 1 name "First Tower"
 *   /ee cp 1 pos1                            ← inside the trigger ring
 *   /ee cp 1 pos2                            ← opposite corner
 *   /ee cp 1 respawn                         ← where to TP on crash
 *   /ee cp 1 points 10
 *   (repeat for each checkpoint, last is the FINISH)
 *
 *   /ee boost b1 pos1
 *   /ee boost b1 pos2
 *   /ee boost b1 strength 1.8
 * </pre>
 */
public class ElytraCommand implements CommandExecutor, TabCompleter {

    private final ElytraEndriumPlugin plugin;

    public ElytraCommand(ElytraEndriumPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("elytra.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

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
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ee setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World niet gevonden."); return true; }
                plugin.getCourseManager().setCourseWorld(w);
                sender.sendMessage(ChatColor.GREEN + "Course world ingesteld op " + w.getName());
            }
            case "setlaunch" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getCourseManager().setLaunchSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Launch spawn ingesteld op huidige plek.");
                sender.sendMessage(ChatColor.GRAY + "Tip: zorg dat dit hoog genoeg is om gelijk te kunnen glijden.");
            }
            case "cp", "checkpoint" -> handleCheckpointCommand(sender, args);
            case "removecp" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ee removecp <n>"); return true; }
                try {
                    int n = Integer.parseInt(args[1]);
                    plugin.getCourseManager().removeCheckpoint(n);
                    sender.sendMessage(ChatColor.GREEN + "Checkpoint " + n + " verwijderd.");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
                }
            }
            case "clearcp" -> {
                plugin.getCourseManager().clearCheckpoints();
                sender.sendMessage(ChatColor.GREEN + "Alle checkpoints gewist.");
            }
            case "listcp" -> {
                var cps = plugin.getCourseManager().getCheckpoints();
                if (cps.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Geen checkpoints."); return true; }
                sender.sendMessage(ChatColor.GOLD + "=== Checkpoints (" + cps.size() + ") ===");
                for (Checkpoint cp : cps) {
                    var p1 = cp.getPos1();
                    sender.sendMessage(ChatColor.YELLOW + "#" + cp.getIndex() + " " + cp.getDisplayName()
                            + ChatColor.GRAY + " — " + cp.getPoints() + " pts @ "
                            + p1.getBlockX() + "," + p1.getBlockY() + "," + p1.getBlockZ());
                }
            }
            case "boost" -> handleBoostCommand(sender, args);
            case "removeboost" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /ee removeboost <id>"); return true; }
                plugin.getCourseManager().removeBoost(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Boost hoop '" + args[1] + "' verwijderd.");
            }
            case "listboosts" -> {
                var bs = plugin.getCourseManager().getBoostHoops();
                if (bs.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Geen boost hoops."); return true; }
                sender.sendMessage(ChatColor.GOLD + "=== Boost Hoops (" + bs.size() + ") ===");
                for (BoostHoop b : bs.values()) {
                    sender.sendMessage(ChatColor.YELLOW + b.getId() + ChatColor.GRAY + " — "
                            + "strength " + b.getStrength());
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Elytra Endrium Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                for (String line : plugin.getCourseManager().getReadinessReport().split("\n")) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7" + line));
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getCourseManager().load();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }
            default -> usage(sender);
        }
        return true;
    }

    /** /ee cp <n> <name|pos1|pos2|respawn|points> [val] */
    private void handleCheckpointCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /ee cp <n> <name|pos1|pos2|respawn|points> [value]");
            return;
        }
        int index;
        try { index = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldig nummer."); return; }
        if (index < 1) { sender.sendMessage(ChatColor.RED + "Index moet 1 of hoger."); return; }

        var partial = plugin.getCourseManager().getPartial(index);
        Checkpoint existing = plugin.getCourseManager().getCheckpoint(index);
        if (existing != null) {
            if (partial.name == null)    partial.name    = existing.getDisplayName();
            if (partial.pos1 == null)    partial.pos1    = existing.getPos1();
            if (partial.pos2 == null)    partial.pos2    = existing.getPos2();
            if (partial.respawn == null) partial.respawn = existing.getRespawn();
            if (partial.points == null)  partial.points  = existing.getPoints();
        }

        String key = args[2].toLowerCase();
        switch (key) {
            case "name" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef een naam."); return; }
                partial.name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                sender.sendMessage(ChatColor.GREEN + "Naam: " + partial.name);
            }
            case "pos1" -> { partial.pos1 = p.getLocation(); sender.sendMessage(ChatColor.GREEN + "pos1 ingesteld."); }
            case "pos2" -> { partial.pos2 = p.getLocation(); sender.sendMessage(ChatColor.GREEN + "pos2 ingesteld."); }
            case "respawn" -> { partial.respawn = p.getLocation(); sender.sendMessage(ChatColor.GREEN + "Respawn ingesteld."); }
            case "points" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef een getal."); return; }
                try { partial.points = Integer.parseInt(args[3]); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldig getal."); return; }
                sender.sendMessage(ChatColor.GREEN + "Points: " + partial.points);
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Onbekend veld. Gebruik: name, pos1, pos2, respawn, points");
                return;
            }
        }

        if (partial.isComplete()) {
            plugin.getCourseManager().addOrUpdateCheckpoint(index, partial.name,
                    partial.pos1, partial.pos2, partial.respawn, partial.points);
            plugin.getCourseManager().clearPartial(index);
            sender.sendMessage(ChatColor.GOLD + "✔ Checkpoint #" + index + " (" + partial.name + ") opgeslagen!");
        } else if (partial.points == null && partial.name != null && partial.pos1 != null
                && partial.pos2 != null && partial.respawn != null) {
            partial.points = plugin.getConfig().getInt("points.default-checkpoint-points", 10);
            plugin.getCourseManager().addOrUpdateCheckpoint(index, partial.name,
                    partial.pos1, partial.pos2, partial.respawn, partial.points);
            plugin.getCourseManager().clearPartial(index);
            sender.sendMessage(ChatColor.GOLD + "✔ Checkpoint #" + index + " opgeslagen "
                    + "(default " + partial.points + " punten).");
        }
    }

    /** /ee boost &lt;id&gt; &lt;pos1|pos2|strength&gt; [value] */
    private void handleBoostCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /ee boost <id> <pos1|pos2|strength> [value]");
            return;
        }
        String id = args[1];

        var partial = plugin.getCourseManager().getPartialBoost(id);

        BoostHoop existing = plugin.getCourseManager().getBoostHoops().get(id);
        if (existing != null) {
            if (partial.pos1 == null)     partial.pos1     = existing.getPos1();
            if (partial.pos2 == null)     partial.pos2     = existing.getPos2();
            if (partial.strength == null) partial.strength = existing.getStrength();
        }

        String field = args[2].toLowerCase();
        switch (field) {
            case "pos1" -> { partial.pos1 = p.getLocation(); sender.sendMessage(ChatColor.GREEN + "pos1 ingesteld."); }
            case "pos2" -> { partial.pos2 = p.getLocation(); sender.sendMessage(ChatColor.GREEN + "pos2 ingesteld."); }
            case "strength" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef strength op (bv 1.5)."); return; }
                try { partial.strength = Double.parseDouble(args[3]); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldig getal."); return; }
                sender.sendMessage(ChatColor.GREEN + "Strength: " + partial.strength);
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Onbekend veld. Gebruik: pos1, pos2, strength");
                return;
            }
        }

        if (partial.strength == null) partial.strength = 1.5;

        if (partial.isComplete()) {
            plugin.getCourseManager().addOrUpdateBoost(new BoostHoop(
                    id, partial.pos1, partial.pos2, partial.strength));
            plugin.getCourseManager().clearPartialBoost(id);
            sender.sendMessage(ChatColor.GOLD + "✔ Boost hoop '" + id + "' opgeslagen!");
        }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Elytra Endrium ===");
        s.sendMessage(ChatColor.YELLOW + "/ee start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/ee setworld <world> | setlaunch");
        s.sendMessage(ChatColor.YELLOW + "/ee cp <n> <name|pos1|pos2|respawn|points> [val]");
        s.sendMessage(ChatColor.YELLOW + "/ee removecp <n> | clearcp | listcp");
        s.sendMessage(ChatColor.YELLOW + "/ee boost <id> <pos1|pos2|strength> [val]");
        s.sendMessage(ChatColor.YELLOW + "/ee removeboost <id> | listboosts");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setworld", "setlaunch",
                    "cp", "removecp", "clearcp", "listcp",
                    "boost", "removeboost", "listboosts",
                    "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld")) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cp")) {
            return List.of("name", "pos1", "pos2", "respawn", "points").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("boost")) {
            return List.of("pos1", "pos2", "strength").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
