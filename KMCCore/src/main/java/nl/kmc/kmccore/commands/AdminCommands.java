package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.health.HealthMonitor;
import nl.kmc.kmccore.lobby.LobbyNPCManager;
import nl.kmc.kmccore.maps.MapRotationManager;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Container file with all 5 new admin commands for the megapatch.
 *
 * <p>Register each in plugin.yml + onEnable as separate commands
 * (e.g. /kmcprefs, /kmchealth, /kmcready, /kmcmap, /kmclobbynpc).
 *
 * <p>Each command is a public static class — just expose them via
 * `new PreferencesCommand(plugin)` etc.
 */
public class AdminCommands {

    // --------------------------------------------------------------
    // /kmcprefs — view + manage player preferences
    // --------------------------------------------------------------

    public static class PreferencesCommand implements CommandExecutor {
        private final KMCCore plugin;
        public PreferencesCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GOLD + "=== /kmcprefs ===");
                sender.sendMessage(ChatColor.YELLOW + "/kmcprefs view <player> <gameId>");
                sender.sendMessage(ChatColor.YELLOW + "/kmcprefs save");
                sender.sendMessage(ChatColor.YELLOW + "/kmcprefs reset <player>");
                sender.sendMessage(ChatColor.YELLOW + "/kmcprefs cleargame <gameId>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "view" -> {
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.RED + "Usage: /kmcprefs view <player> <gameId>");
                        return true;
                    }
                    Player target = plugin.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Player not online.");
                        return true;
                    }
                    Map<String, Object> all = plugin.getPlayerPreferences().getAll(target.getUniqueId(), args[2]);
                    if (all.isEmpty()) {
                        sender.sendMessage(ChatColor.GRAY + "No preferences for " + target.getName() + " in " + args[2]);
                    } else {
                        sender.sendMessage(ChatColor.GOLD + "Prefs for " + target.getName() + " in " + args[2] + ":");
                        all.forEach((k, v) -> sender.sendMessage(
                                ChatColor.GRAY + "  " + k + " = " + ChatColor.WHITE + v));
                    }
                }
                case "save" -> {
                    plugin.getPlayerPreferences().save();
                    sender.sendMessage(ChatColor.GREEN + "Preferences saved.");
                }
                case "reset" -> {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /kmcprefs reset <player>"); return true; }
                    Player target = plugin.getServer().getPlayerExact(args[1]);
                    if (target == null) { sender.sendMessage(ChatColor.RED + "Player not online."); return true; }
                    plugin.getPlayerPreferences().clearPlayer(target.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "Cleared preferences for " + target.getName());
                }
                case "cleargame" -> {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /kmcprefs cleargame <gameId>"); return true; }
                    plugin.getPlayerPreferences().clearGame(args[1]);
                    sender.sendMessage(ChatColor.GREEN + "Cleared all preferences for game: " + args[1]);
                }
                default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
            return true;
        }
    }

    // --------------------------------------------------------------
    // /kmchealth — view recent health events
    // --------------------------------------------------------------

    public static class HealthCommand implements CommandExecutor {
        private final KMCCore plugin;
        public HealthCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0 || args[0].equalsIgnoreCase("recent")) {
                sender.sendMessage(ChatColor.GOLD + "=== Recent Health Events (last 20) ===");
                List<HealthMonitor.HealthEvent> events = plugin.getHealthMonitor().getRecentEvents();
                int from = Math.max(0, events.size() - 20);
                for (int i = from; i < events.size(); i++) {
                    var e = events.get(i);
                    ChatColor color = e.severity() == HealthMonitor.Severity.CRITICAL ? ChatColor.RED
                            : e.severity() == HealthMonitor.Severity.WARNING ? ChatColor.YELLOW
                              : ChatColor.GRAY;
                    long secAgo = (System.currentTimeMillis() - e.timestamp()) / 1000;
                    sender.sendMessage(color + "[" + e.severity() + "] "
                            + ChatColor.GRAY + secAgo + "s ago "
                            + ChatColor.WHITE + e.code() + ChatColor.GRAY + " — " + e.message());
                }
            } else if (args[0].equalsIgnoreCase("warnings")) {
                sender.sendMessage(ChatColor.GOLD + "=== Recent Warnings ===");
                for (var e : plugin.getHealthMonitor().getRecentEventsBySeverity(HealthMonitor.Severity.WARNING)) {
                    sender.sendMessage(ChatColor.YELLOW + "[" + e.code() + "] " + e.message());
                }
            } else if (args[0].equalsIgnoreCase("critical")) {
                sender.sendMessage(ChatColor.GOLD + "=== Critical Events ===");
                for (var e : plugin.getHealthMonitor().getRecentEventsBySeverity(HealthMonitor.Severity.CRITICAL)) {
                    sender.sendMessage(ChatColor.RED + "[" + e.code() + "] " + e.message());
                }
            } else {
                sender.sendMessage(ChatColor.YELLOW + "/kmchealth [recent|warnings|critical]");
            }
            return true;
        }
    }

    // --------------------------------------------------------------
    // /kmcready — admin override of ready check
    // --------------------------------------------------------------

    public static class ReadyCommand implements CommandExecutor {
        private final KMCCore plugin;
        public ReadyCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "/kmcready force | status");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "force" -> {
                    if (!plugin.getReadyUpManager().isActive()) {
                        sender.sendMessage(ChatColor.GRAY + "No ready check active.");
                        return true;
                    }
                    plugin.getReadyUpManager().forceSkip();
                    sender.sendMessage(ChatColor.GREEN + "Ready check force-completed.");
                }
                case "status" -> {
                    sender.sendMessage(ChatColor.GOLD + "Ready check active: "
                            + (plugin.getReadyUpManager().isActive() ? ChatColor.GREEN + "yes"
                            : ChatColor.GRAY + "no"));
                }
                default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
            return true;
        }
    }

    // --------------------------------------------------------------
    // /kmcmap — manage map rotation
    // --------------------------------------------------------------

    public static class MapCommand implements CommandExecutor {
        private final KMCCore plugin;
        public MapCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GOLD + "=== /kmcmap ===");
                sender.sendMessage(ChatColor.YELLOW + "/kmcmap list <gameId>");
                sender.sendMessage(ChatColor.YELLOW + "/kmcmap add <gameId> <mapId> <displayName> [weight]");
                sender.sendMessage(ChatColor.YELLOW + "/kmcmap remove <gameId> <mapId>");
                sender.sendMessage(ChatColor.YELLOW + "/kmcmap mode <gameId> <RANDOM|WEIGHTED|VOTE|SEQUENCE|FIXED>");
                sender.sendMessage(ChatColor.YELLOW + "/kmcmap setfixed <gameId> <mapId>");
                sender.sendMessage(ChatColor.YELLOW + "/kmcmap pick <gameId>");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "list" -> {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /kmcmap list <gameId>"); return true; }
                    var maps = plugin.getMapRotation().getMaps(args[1]);
                    if (maps.isEmpty()) {
                        sender.sendMessage(ChatColor.GRAY + "No maps registered for " + args[1]);
                        return true;
                    }
                    sender.sendMessage(ChatColor.GOLD + "Maps for " + args[1]
                            + " (mode: " + plugin.getMapRotation().getMode(args[1]) + "):");
                    for (var m : maps) {
                        sender.sendMessage(ChatColor.YELLOW + "  " + m.id()
                                + ChatColor.GRAY + " — " + m.displayName()
                                + ChatColor.GRAY + " (weight " + m.weight() + ")");
                    }
                }
                case "add" -> {
                    if (args.length < 4) {
                        sender.sendMessage(ChatColor.RED + "Usage: /kmcmap add <gameId> <mapId> <displayName> [weight]");
                        return true;
                    }
                    int weight = args.length >= 5 ? parseIntSafe(args[4], 1) : 1;
                    plugin.getMapRotation().registerMap(args[1], args[2], args[3], weight);
                    sender.sendMessage(ChatColor.GREEN + "Map registered: " + args[2] + " for " + args[1]);
                }
                case "remove" -> {
                    if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /kmcmap remove <gameId> <mapId>"); return true; }
                    plugin.getMapRotation().removeMap(args[1], args[2]);
                    sender.sendMessage(ChatColor.GREEN + "Map removed.");
                }
                case "mode" -> {
                    if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /kmcmap mode <gameId> <MODE>"); return true; }
                    try {
                        MapRotationManager.SelectionMode mode =
                                MapRotationManager.SelectionMode.valueOf(args[2].toUpperCase());
                        plugin.getMapRotation().setMode(args[1], mode);
                        sender.sendMessage(ChatColor.GREEN + "Mode set to " + mode + " for " + args[1]);
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(ChatColor.RED + "Invalid mode. Try: RANDOM, WEIGHTED, VOTE, SEQUENCE, FIXED");
                    }
                }
                case "setfixed" -> {
                    if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /kmcmap setfixed <gameId> <mapId>"); return true; }
                    plugin.getMapRotation().setFixed(args[1], args[2]);
                    sender.sendMessage(ChatColor.GREEN + "Fixed map set: " + args[2]);
                }
                case "pick" -> {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /kmcmap pick <gameId>"); return true; }
                    String picked = plugin.getMapRotation().pickNext(args[1]);
                    sender.sendMessage(ChatColor.GREEN + "Picked: "
                            + (picked != null ? picked : "(none — not configured)"));
                }
                default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
            return true;
        }

        private int parseIntSafe(String s, int fallback) {
            try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
        }
    }

    // --------------------------------------------------------------
    // /kmclobbynpc — spawn lobby NPCs
    // --------------------------------------------------------------

    public static class LobbyNPCCommand implements CommandExecutor {
        private final KMCCore plugin;
        public LobbyNPCCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "In-game only.");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "/kmclobbynpc spawn <stats|hof>");
                sender.sendMessage(ChatColor.YELLOW + "/kmclobbynpc despawnall");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "spawn" -> {
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Specify type: stats or hof");
                        return true;
                    }
                    LobbyNPCManager.NPCType type;
                    try {
                        type = LobbyNPCManager.NPCType.valueOf(args[1].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(ChatColor.RED + "Invalid type. Use: stats or hof");
                        return true;
                    }
                    plugin.getLobbyNPCManager().spawnNPC(p.getLocation(), type);
                    sender.sendMessage(ChatColor.GREEN + "" + type.name() + " NPC spawned at your location.");
                }
                case "despawnall" -> {
                    plugin.getLobbyNPCManager().despawnAll();
                    sender.sendMessage(ChatColor.GREEN + "All KMC lobby NPCs removed.");
                }
                default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
            return true;
        }
    }
}