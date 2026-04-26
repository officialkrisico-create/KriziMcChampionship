package nl.kmc.tgttos.commands;

import nl.kmc.tgttos.TGTTOSPlugin;
import nl.kmc.tgttos.managers.MapManager;
import nl.kmc.tgttos.models.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /tgttos command — admin setup and game control.
 *
 * <p>Setup workflow for ONE map:
 * <pre>
 *   /tgttos createmap glass "Glass Bridge"
 *   /tgttos editmap glass world &lt;world&gt;
 *   /tgttos editmap glass addspawn         (× number of player slots)
 *   /tgttos editmap glass finishpos1
 *   /tgttos editmap glass finishpos2
 *   /tgttos editmap glass voidy 50
 *   /tgttos commit glass
 * </pre>
 *
 * <p>Repeat for each map you want in the rotation pool. The plugin
 * picks 3 random maps per game.
 */
public class TGTTOSCommand implements CommandExecutor, TabCompleter {

    private final TGTTOSPlugin plugin;

    public TGTTOSCommand(TGTTOSPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        if (!sender.hasPermission("tgttos.admin")) {
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
            case "createmap" -> {
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Gebruik: /tgttos createmap <id> <display name>"); return true; }
                String id = args[1];
                String displayName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                var partial = plugin.getMapManager().getPartial(id);
                partial.displayName = displayName;
                sender.sendMessage(ChatColor.GREEN + "Map '" + id + "' aangemaakt. "
                        + "Vul nu world, spawns, finish, voidy in via /tgttos editmap.");
            }
            case "editmap" -> handleEditMap(sender, args);
            case "commit" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /tgttos commit <id>"); return true; }
                String id = args[1];
                MapManager.PartialMap partial = plugin.getMapManager().getPartial(id);
                if (!partial.isComplete()) {
                    sender.sendMessage(ChatColor.RED + "Map niet compleet — mist: " + partial.missing());
                    return true;
                }
                plugin.getMapManager().commitPartial(id);
                sender.sendMessage(ChatColor.GREEN + "Map '" + id + "' opgeslagen!");
            }
            case "deletemap" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /tgttos deletemap <id>"); return true; }
                plugin.getMapManager().deleteMap(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Map '" + args[1] + "' verwijderd.");
            }
            case "listmaps" -> {
                var maps = plugin.getMapManager().getMaps();
                if (maps.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Geen maps."); return true; }
                sender.sendMessage(ChatColor.GOLD + "=== Maps (" + maps.size() + ") ===");
                for (Map m : maps.values()) {
                    sender.sendMessage(ChatColor.YELLOW + m.getId() + ChatColor.GRAY + " — "
                            + m.getDisplayName() + " &7@ " + m.getWorld().getName()
                            + " (" + m.getStartSpawns().size() + " spawns)");
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== TGTTOS Status ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + plugin.getGameManager().getState());
                sender.sendMessage(ChatColor.YELLOW + "Round: " + (plugin.getGameManager().getCurrentMap() != null
                        ? plugin.getGameManager().getCurrentMap().getDisplayName() : "—"));
                sender.sendMessage(ChatColor.GRAY + plugin.getMapManager().getReadinessReport().replace("&c", ChatColor.RED.toString()));
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getMapManager().load();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }
            default -> usage(sender);
        }
        return true;
    }

    /** /tgttos editmap <id> <world|addspawn|clearspawns|finishpos1|finishpos2|voidy|name> [val] */
    private void handleEditMap(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Gebruik: /tgttos editmap <id> <world|addspawn|clearspawns|finishpos1|finishpos2|voidy|name> [val]");
            return;
        }
        String id = args[1];
        var partial = plugin.getMapManager().getPartial(id);

        // Seed from existing map if it already exists
        Map existing = plugin.getMapManager().getMap(id);
        if (existing != null) {
            if (partial.displayName == null) partial.displayName = existing.getDisplayName();
            if (partial.world == null)       partial.world       = existing.getWorld();
            if (partial.startSpawns.isEmpty()) partial.startSpawns.addAll(existing.getStartSpawns());
            if (partial.finishPos1 == null)  partial.finishPos1  = existing.getFinishPos1();
            if (partial.finishPos2 == null)  partial.finishPos2  = existing.getFinishPos2();
            if (partial.voidY == null)       partial.voidY       = existing.getVoidYLevel();
        }

        String field = args[2].toLowerCase();
        switch (field) {
            case "world" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Gebruik: /tgttos editmap " + id + " world <name>"); return; }
                World w = Bukkit.getWorld(args[3]);
                if (w == null) { sender.sendMessage(ChatColor.RED + "World '" + args[3] + "' niet gevonden."); return; }
                partial.world = w;
                sender.sendMessage(ChatColor.GREEN + "World ingesteld op " + w.getName());
            }
            case "addspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                partial.startSpawns.add(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Start spawn #" + partial.startSpawns.size() + " toegevoegd.");
            }
            case "clearspawns" -> {
                partial.startSpawns.clear();
                sender.sendMessage(ChatColor.GREEN + "Start spawns gewist.");
            }
            case "finishpos1" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                partial.finishPos1 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "finishpos1 ingesteld.");
            }
            case "finishpos2" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                partial.finishPos2 = p.getLocation();
                sender.sendMessage(ChatColor.GREEN + "finishpos2 ingesteld.");
            }
            case "voidy" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef Y-niveau op."); return; }
                try { partial.voidY = Integer.parseInt(args[3]); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldig getal."); return; }
                sender.sendMessage(ChatColor.GREEN + "Void Y ingesteld op " + partial.voidY);
            }
            case "name" -> {
                if (args.length < 4) { sender.sendMessage(ChatColor.RED + "Geef naam op."); return; }
                partial.displayName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                sender.sendMessage(ChatColor.GREEN + "Naam: " + partial.displayName);
            }
            default -> sender.sendMessage(ChatColor.RED + "Onbekend veld. Gebruik: world, addspawn, clearspawns, finishpos1, finishpos2, voidy, name");
        }

        if (partial.isComplete()) {
            sender.sendMessage(ChatColor.YELLOW + "Map compleet — typ &6/tgttos commit " + id + " &eom op te slaan.");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Mist nog: " + partial.missing());
        }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== TGTTOS ===");
        s.sendMessage(ChatColor.YELLOW + "/tgttos start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/tgttos createmap <id> <display name>");
        s.sendMessage(ChatColor.YELLOW + "/tgttos editmap <id> <world|addspawn|clearspawns|finishpos1|finishpos2|voidy|name> [val]");
        s.sendMessage(ChatColor.YELLOW + "/tgttos commit <id> | deletemap <id> | listmaps");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "createmap", "editmap", "commit",
                    "deletemap", "listmaps", "status", "reload").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("editmap")
                || args[0].equalsIgnoreCase("commit") || args[0].equalsIgnoreCase("deletemap"))) {
            return plugin.getMapManager().getMaps().keySet().stream()
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("editmap")) {
            return List.of("world", "addspawn", "clearspawns", "finishpos1", "finishpos2", "voidy", "name").stream()
                    .filter(o -> o.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("editmap") && args[2].equalsIgnoreCase("world")) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
