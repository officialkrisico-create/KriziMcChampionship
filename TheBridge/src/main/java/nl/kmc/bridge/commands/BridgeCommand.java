package nl.kmc.bridge.commands;

import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.managers.ArenaManager;
import nl.kmc.bridge.models.BridgeTeam;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /bridge command — admin setup + game control.
 *
 * <p>Setup workflow:
 * <pre>
 *   /bridge setworld &lt;world&gt;
 *   /bridge setvoidy &lt;y&gt;
 *
 *   For EACH team (typically 2: red and blue):
 *     /bridge createteam red "Red Team" RED RED_WOOL
 *     /bridge editteam red spawn        ← stand at red team spawn
 *     /bridge editteam red goalpos1     ← inside red's goal hole
 *     /bridge editteam red goalpos2     ← opposite corner of hole
 *     /bridge commit red
 * </pre>
 */
public class BridgeCommand implements CommandExecutor, TabCompleter {

    private final TheBridgePlugin plugin;

    public BridgeCommand(TheBridgePlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("bridge.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                String error = plugin.getGameManager().startGame();
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                else sender.sendMessage(ChatColor.GREEN + "Game gestart!");
            }
            case "stop" -> {
                plugin.getGameManager().forceStop();
                sender.sendMessage(ChatColor.RED + "Game gestopt.");
            }
            case "setworld" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /bridge setworld <world>"); return true; }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' niet gevonden."); return true; }
                plugin.getArenaManager().setWorld(w);
                sender.sendMessage(ChatColor.GREEN + "World ingesteld op " + w.getName());
            }
            case "setvoidy" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /bridge setvoidy <y>"); return true; }
                try {
                    int y = Integer.parseInt(args[1]);
                    plugin.getArenaManager().setVoidYLevel(y);
                    sender.sendMessage(ChatColor.GREEN + "Void Y ingesteld op " + y);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal.");
                }
            }
            case "createteam" -> {
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /bridge createteam <id> <displayName> <ChatColor> <WoolMaterial>");
                    return true;
                }
                String id = args[1];
                String displayName = args[2];
                ChatColor cc;
                try { cc = ChatColor.valueOf(args[3].toUpperCase()); }
                catch (IllegalArgumentException e) { sender.sendMessage(ChatColor.RED + "Ongeldige ChatColor."); return true; }
                Material wool;
                try { wool = Material.valueOf(args[4].toUpperCase()); }
                catch (IllegalArgumentException e) { sender.sendMessage(ChatColor.RED + "Ongeldige Material."); return true; }
                if (!wool.name().endsWith("_WOOL")) {
                    sender.sendMessage(ChatColor.RED + "Material moet wol zijn (e.g. RED_WOOL).");
                    return true;
                }
                var partial = plugin.getArenaManager().getPartial(id);
                partial.displayName  = displayName;
                partial.chatColor    = cc;
                partial.woolMaterial = wool;
                sender.sendMessage(ChatColor.GREEN + "Team '" + id + "' aangemaakt. "
                        + "Vul nu spawn, goalpos1, goalpos2 in via /bridge editteam.");
            }
            case "editteam" -> handleEditTeam(sender, args);
            case "commit" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /bridge commit <id>"); return true; }
                String id = args[1];
                ArenaManager.PartialTeam partial = plugin.getArenaManager().getPartial(id);
                if (!partial.isComplete()) {
                    sender.sendMessage(ChatColor.RED + "Team niet compleet — mist: " + partial.missing());
                    return true;
                }
                plugin.getArenaManager().commitPartial(id);
                sender.sendMessage(ChatColor.GREEN + "Team '" + id + "' opgeslagen!");
            }
            case "deleteteam" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /bridge deleteteam <id>"); return true; }
                plugin.getArenaManager().deleteTeam(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Team verwijderd.");
            }
            case "listteams" -> {
                var teams = plugin.getArenaManager().getTeams();
                if (teams.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Geen teams."); return true; }
                sender.sendMessage(ChatColor.GOLD + "=== Teams (" + teams.size() + ") ===");
                for (BridgeTeam t : teams.values()) {
                    sender.sendMessage(t.getChatColor() + t.getId() + ChatColor.GRAY + " — "
                            + t.getDisplayName() + " &7(" + t.getWoolMaterial() + ")");
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== The Bridge Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                for (String line : plugin.getArenaManager().getReadinessReport().split("\n")) {
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

    /** /bridge editteam <id> <spawn|goalpos1|goalpos2|color|wool|name> [val] */
    private void handleEditTeam(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /bridge editteam <id> <spawn|goalpos1|goalpos2|color|wool|name> [val]");
            return;
        }
        String id = args[1];
        var partial = plugin.getArenaManager().getPartial(id);

        // Seed from existing
        BridgeTeam existing = plugin.getArenaManager().getTeam(id);
        if (existing != null) {
            if (partial.displayName == null)  partial.displayName  = existing.getDisplayName();
            if (partial.chatColor == null)    partial.chatColor    = existing.getChatColor();
            if (partial.woolMaterial == null) partial.woolMaterial = existing.getWoolMaterial();
            if (partial.spawn == null)        partial.spawn        = existing.getSpawn();
            if (partial.goalPos1 == null)     partial.goalPos1     = existing.getGoalPos1();
            if (partial.goalPos2 == null)     partial.goalPos2     = existing.getGoalPos2();
        }

        String field = args[2].toLowerCase();
        switch (field) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                partial.spawn = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "Spawn ingesteld.");
            }
            case "goalpos1" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                partial.goalPos1 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "goalpos1 ingesteld.");
            }
            case "goalpos2" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                partial.goalPos2 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "goalpos2 ingesteld.");
            }
            case "color" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef ChatColor op."); return; }
                try { partial.chatColor = ChatColor.valueOf(args[3].toUpperCase()); }
                catch (IllegalArgumentException e) { sender.sendMessage(ChatColor.RED + "Ongeldige ChatColor."); return; }
                sender.sendMessage(ChatColor.GREEN + "Color ingesteld op " + partial.chatColor + partial.chatColor.name());
            }
            case "wool" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef wool material op."); return; }
                try {
                    Material m = Material.valueOf(args[3].toUpperCase());
                    if (!m.name().endsWith("_WOOL")) { sender.sendMessage(ChatColor.RED + "Moet wol zijn."); return; }
                    partial.woolMaterial = m;
                } catch (IllegalArgumentException e) { sender.sendMessage(ChatColor.RED + "Ongeldige Material."); return; }
                sender.sendMessage(ChatColor.GREEN + "Wool ingesteld op " + partial.woolMaterial);
            }
            case "name" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef naam op."); return; }
                partial.displayName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                sender.sendMessage(ChatColor.GREEN + "Naam: " + partial.displayName);
            }
            default -> sender.sendMessage(ChatColor.RED + "Onbekend veld. Gebruik: spawn, goalpos1, goalpos2, color, wool, name");
        }

        if (partial.isComplete()) {
            sender.sendMessage(ChatColor.YELLOW + "Team compleet — typ &6/bridge commit " + id + " &eom op te slaan.");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Mist nog: " + partial.missing());
        }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== The Bridge ===");
        s.sendMessage(ChatColor.YELLOW + "/bridge start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/bridge setworld <world> | setvoidy <y>");
        s.sendMessage(ChatColor.YELLOW + "/bridge createteam <id> <displayName> <ChatColor> <Wool>");
        s.sendMessage(ChatColor.YELLOW + "/bridge editteam <id> <spawn|goalpos1|goalpos2|color|wool|name> [val]");
        s.sendMessage(ChatColor.YELLOW + "/bridge commit <id> | deleteteam <id> | listteams");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setworld", "setvoidy", "createteam", "editteam",
                    "commit", "deleteteam", "listteams", "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("editteam")
                || args[0].equalsIgnoreCase("commit") || args[0].equalsIgnoreCase("deleteteam"))) {
            return plugin.getArenaManager().getTeams().keySet().stream()
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("editteam")) {
            return List.of("spawn", "goalpos1", "goalpos2", "color", "wool", "name").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
