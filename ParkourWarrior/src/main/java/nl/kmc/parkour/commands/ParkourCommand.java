package nl.kmc.parkour.commands;

import nl.kmc.parkour.ParkourWarriorPlugin;
import nl.kmc.parkour.managers.CourseManager;
import nl.kmc.parkour.models.Checkpoint;
import nl.kmc.parkour.models.Powerup;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /parkourwarrior or /pkw — admin setup + control + skip command.
 *
 * <p>Setup workflow:
 * <pre>
 *   /pkw setworld &lt;world&gt;
 *   /pkw setstart                        ← stand at start spawn
 *   /pkw cp 1 name "Easy jumps"
 *   /pkw cp 1 pos1                       ← stand at first corner
 *   /pkw cp 1 pos2                       ← stand at second corner
 *   /pkw cp 1 respawn                    ← stand where they should TP
 *   /pkw cp 1 points 10
 *   (repeat for each checkpoint, last is finish)
 *
 *   /pkw powerup boost1 speed pos1
 *   /pkw powerup boost1 speed pos2
 *   /pkw powerup boost1 speed duration 5
 *   /pkw powerup boost1 speed amplifier 2
 * </pre>
 */
public class ParkourCommand implements CommandExecutor, TabCompleter {

    private final ParkourWarriorPlugin plugin;

    public ParkourCommand(ParkourWarriorPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }

        // Player commands available to all
        if (args[0].equalsIgnoreCase("skip")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
            String error = plugin.getGameManager().trySkip(p);
            if (error != null) p.sendMessage(ChatColor.RED + error);
            return true;
        }

        // Admin commands
        if (!sender.hasPermission("parkour.admin")) {
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
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /pkw setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden."); return true; }
                plugin.getCourseManager().setCourseWorld(w);
                sender.sendMessage(ChatColor.GREEN + "Course world ingesteld op " + w.getName());
            }
            case "setstart" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getCourseManager().setStartSpawn(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Start spawn ingesteld.");
            }
            case "cp", "checkpoint" -> handleCheckpointCommand(sender, args);
            case "removecp", "removecheckpoint" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /pkw removecp <n>"); return true; }
                try {
                    int n = Integer.parseInt(args[1]);
                    plugin.getCourseManager().removeCheckpoint(n);
                    sender.sendMessage(ChatColor.GREEN + "Checkpoint " + n + " verwijderd.");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
                }
            }
            case "clearcp", "clearcheckpoints" -> {
                plugin.getCourseManager().clearCheckpoints();
                sender.sendMessage(ChatColor.GREEN + "Alle checkpoints gewist.");
            }
            case "listcp", "listcheckpoints" -> {
                var cps = plugin.getCourseManager().getCheckpoints();
                if (cps.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Geen checkpoints.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Checkpoints (" + cps.size()
                        + " across " + plugin.getCourseManager().getStageCount() + " stages) ===");
                int currentStage = -1;
                for (Checkpoint cp : cps) {
                    if (cp.getStage() != currentStage) {
                        currentStage = cp.getStage();
                        sender.sendMessage(ChatColor.GOLD + "─ Stage " + currentStage + " ─");
                    }
                    var p1 = cp.getPos1();
                    String diffTag = cp.getDifficulty() != nl.kmc.parkour.models.Difficulty.MAIN
                            ? " [" + cp.getDifficulty().formatted() + ChatColor.GRAY + "]"
                            : "";
                    sender.sendMessage(ChatColor.YELLOW + "  #" + cp.getIndex() + " " + cp.getDisplayName()
                            + diffTag + ChatColor.GRAY + " — " + cp.getPoints() + " pts"
                            + (cp.getDifficulty() != nl.kmc.parkour.models.Difficulty.MAIN
                               ? " (×" + cp.getDifficulty().getMultiplier() + " = "
                                 + cp.getAwardedPoints() + ")" : "")
                            + " @ " + p1.getBlockX() + "," + p1.getBlockY() + "," + p1.getBlockZ());
                }
            }
            case "powerup", "pu" -> handlePowerupCommand(sender, args);
            case "removepowerup", "removepu" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /pkw removepowerup <id>"); return true; }
                plugin.getCourseManager().removePowerup(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Powerup '" + args[1] + "' verwijderd.");
            }
            case "listpowerups", "listpu" -> {
                var pus = plugin.getCourseManager().getPowerups();
                if (pus.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Geen powerups.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Powerups (" + pus.size() + ") ===");
                for (Powerup pu : pus.values()) {
                    sender.sendMessage(ChatColor.YELLOW + pu.getId() + ChatColor.GRAY + " — "
                            + pu.getType() + " level " + (pu.getAmplifier() + 1)
                            + " for " + pu.getDurationSeconds() + "s");
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Parkour Warrior Status ===");
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

    /**
     * /pkw cp &lt;n&gt; &lt;name|pos1|pos2|respawn|points&gt; [value...]
     *
     * Builds checkpoint state in stages — each piece is set independently.
     * When all 5 pieces are set, the checkpoint is committed automatically.
     */
    private void handleCheckpointCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /pkw cp <n> <name|pos1|pos2|respawn|points> [value]");
            return;
        }

        int index;
        try { index = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
            return;
        }
        if (index < 1) { sender.sendMessage(ChatColor.RED + "Index moet 1 of hoger."); return; }

        // Seed partial from existing checkpoint if it exists
        CourseManager.PartialCheckpoint partial = plugin.getCourseManager().getPartial(index);
        Checkpoint existing = plugin.getCourseManager().getCheckpoint(index);
        if (existing != null) {
            if (partial.name == null)       partial.name       = existing.getDisplayName();
            if (partial.pos1 == null)       partial.pos1       = existing.getPos1();
            if (partial.pos2 == null)       partial.pos2       = existing.getPos2();
            if (partial.respawn == null)    partial.respawn    = existing.getRespawn();
            if (partial.points == null)     partial.points     = existing.getPoints();
            if (partial.stage == null)      partial.stage      = existing.getStage();
            if (partial.difficulty == null) partial.difficulty = existing.getDifficulty();
        }

        String key = args[2].toLowerCase();
        switch (key) {
            case "name" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Gebruik: /pkw cp " + index + " name <text>"); return; }
                partial.name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                sender.sendMessage(ChatColor.GREEN + "Naam: " + partial.name);
            }
            case "pos1" -> {
                partial.pos1 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "pos1 ingesteld.");
            }
            case "pos2" -> {
                partial.pos2 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "pos2 ingesteld.");
            }
            case "respawn" -> {
                partial.respawn = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "Respawn ingesteld.");
            }
            case "points" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Gebruik: /pkw cp " + index + " points <n>"); return; }
                try {
                    partial.points = Integer.parseInt(args[3]);
                    sender.sendMessage(ChatColor.GREEN + "Points: " + partial.points);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
                    return;
                }
            }
            case "stage" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Gebruik: /pkw cp " + index + " stage <n>"); return; }
                try {
                    partial.stage = Integer.parseInt(args[3]);
                    sender.sendMessage(ChatColor.GREEN + "Stage: " + partial.stage);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig nummer.");
                    return;
                }
            }
            case "difficulty", "diff" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /pkw cp " + index + " difficulty <main|easy|medium|hard>");
                    return;
                }
                nl.kmc.parkour.models.Difficulty d = nl.kmc.parkour.models.Difficulty.parse(args[3]);
                partial.difficulty = d;
                sender.sendMessage(ChatColor.GREEN + "Difficulty: " + d.formatted()
                        + ChatColor.GRAY + " (×" + d.getMultiplier() + " punten)");
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Onbekend veld. Gebruik: name, pos1, pos2, respawn, points, stage, difficulty");
                return;
            }
        }

        if (partial.isComplete()) {
            int stage = partial.stage != null ? partial.stage : index;
            nl.kmc.parkour.models.Difficulty diff = partial.difficulty != null
                    ? partial.difficulty : nl.kmc.parkour.models.Difficulty.MAIN;
            plugin.getCourseManager().addOrUpdateCheckpoint(index, stage, diff,
                    partial.name, partial.pos1, partial.pos2, partial.respawn, partial.points);
            plugin.getCourseManager().clearPartial(index);
            sender.sendMessage(ChatColor.GOLD + "✔ Checkpoint #" + index
                    + " (" + partial.name + ") opgeslagen — stage " + stage
                    + " " + diff.formatted() + ChatColor.GOLD + "!");
        } else {
            // Default points if missing
            if (partial.points == null && partial.name != null && partial.pos1 != null
                    && partial.pos2 != null && partial.respawn != null) {
                partial.points = plugin.getConfig().getInt("points.default-checkpoint-points", 10);
                int stage = partial.stage != null ? partial.stage : index;
                nl.kmc.parkour.models.Difficulty diff = partial.difficulty != null
                        ? partial.difficulty : nl.kmc.parkour.models.Difficulty.MAIN;
                plugin.getCourseManager().addOrUpdateCheckpoint(index, stage, diff,
                        partial.name, partial.pos1, partial.pos2, partial.respawn, partial.points);
                plugin.getCourseManager().clearPartial(index);
                sender.sendMessage(ChatColor.GOLD + "✔ Checkpoint #" + index + " opgeslagen "
                        + "(default " + partial.points + " punten, stage " + stage + ", "
                        + diff.formatted() + ChatColor.GOLD + ").");
            }
        }
    }

    /**
     * /pkw powerup &lt;id&gt; &lt;speed|jump&gt; &lt;pos1|pos2|duration|amplifier&gt; [value]
     */
    private void handlePowerupCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /pkw powerup <id> <speed|jump> <pos1|pos2|duration|amplifier> [value]");
            return;
        }

        String id = args[1];
        Powerup.Type type;
        try { type = Powerup.Type.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Type moet 'speed' of 'jump' zijn.");
            return;
        }

        var partial = plugin.getCourseManager().getPartialPowerup(id);
        partial.type = type;

        // Seed from existing if present
        Powerup existing = plugin.getCourseManager().getPowerups().get(id);
        if (existing != null) {
            if (partial.pos1 == null)      partial.pos1      = existing.getPos1();
            if (partial.pos2 == null)      partial.pos2      = existing.getPos2();
            if (partial.duration == null)  partial.duration  = existing.getDurationSeconds();
            if (partial.amplifier == null) partial.amplifier = existing.getAmplifier();
        }

        String field = args[3].toLowerCase();
        switch (field) {
            case "pos1" -> {
                partial.pos1 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "pos1 ingesteld.");
            }
            case "pos2" -> {
                partial.pos2 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "pos2 ingesteld.");
            }
            case "duration" -> {
                if (args.length < 5) { sender.sendMessage(ChatColor.RED + "Geef seconden op."); return; }
                try { partial.duration = Integer.parseInt(args[4]); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldig getal."); return; }
                sender.sendMessage(ChatColor.GREEN + "Duration: " + partial.duration + "s");
            }
            case "amplifier" -> {
                if (args.length < 5) { sender.sendMessage(ChatColor.RED + "Geef level op (0=I, 1=II, 2=III)."); return; }
                try { partial.amplifier = Integer.parseInt(args[4]); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldig getal."); return; }
                sender.sendMessage(ChatColor.GREEN + "Amplifier: " + partial.amplifier);
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Onbekend veld. Gebruik: pos1, pos2, duration, amplifier");
                return;
            }
        }

        // Default duration/amplifier if not set
        if (partial.duration == null)  partial.duration = 5;
        if (partial.amplifier == null) partial.amplifier = 1;

        if (partial.isComplete()) {
            plugin.getCourseManager().addOrUpdatePowerup(new Powerup(
                    id, partial.type, partial.pos1, partial.pos2,
                    partial.duration, partial.amplifier));
            plugin.getCourseManager().clearPartialPowerup(id);
            sender.sendMessage(ChatColor.GOLD + "✔ Powerup '" + id + "' opgeslagen!");
        }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Parkour Warrior ===");
        s.sendMessage(ChatColor.YELLOW + "/pkw skip " + ChatColor.GRAY + "— skip huidige checkpoint (na 3 fails)");
        if (s.hasPermission("parkour.admin")) {
            s.sendMessage(ChatColor.YELLOW + "/pkw start | stop | status | reload");
            s.sendMessage(ChatColor.YELLOW + "/pkw setworld <world> | setstart");
            s.sendMessage(ChatColor.YELLOW + "/pkw cp <n> <name|pos1|pos2|respawn|points> [val]");
            s.sendMessage(ChatColor.YELLOW + "/pkw removecp <n> | clearcp | listcp");
            s.sendMessage(ChatColor.YELLOW + "/pkw powerup <id> <speed|jump> <pos1|pos2|duration|amplifier> [val]");
            s.sendMessage(ChatColor.YELLOW + "/pkw removepowerup <id> | listpowerups");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("skip"));
            if (s.hasPermission("parkour.admin"))
                opts.addAll(List.of("start", "stop", "setworld", "setstart",
                        "cp", "removecp", "clearcp", "listcp",
                        "powerup", "removepowerup", "listpowerups",
                        "status", "reload"));
            return opts.stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setworld")) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cp")) {
            return List.of("name", "pos1", "pos2", "respawn", "points", "stage", "difficulty").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("cp")
                && (args[2].equalsIgnoreCase("difficulty") || args[2].equalsIgnoreCase("diff"))) {
            return List.of("main", "easy", "medium", "hard").stream()
                    .filter(o -> o.startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("powerup")) {
            return List.of("speed", "jump").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("powerup")) {
            return List.of("pos1", "pos2", "duration", "amplifier").stream()
                    .filter(o -> o.startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
