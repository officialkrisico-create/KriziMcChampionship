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
                if (sender instanceof org.bukkit.entity.Player p) {
                    var lm = plugin.getLanguageManager();
                    if (!plugin.getReadyUpManager().isActive()) {
                        p.sendMessage(lm.tr(p, "director.ready.none"));
                    } else if (!plugin.getReadyUpManager().markReady(p)) {
                        p.sendMessage(lm.tr(p, "director.ready.no-team"));
                    }
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "/kmcready force | status");
                }
                return true;
            }
            if (!sender.hasPermission("kmc.admin")) {
                sender.sendMessage(ChatColor.RED + "Alleen voor admins: /kmcready force | status");
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

    public static class LobbyNPCCommand implements CommandExecutor, org.bukkit.command.TabExecutor {
        private final KMCCore plugin;
        public LobbyNPCCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "In-game only.");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "/kmclobbynpc spawn <stats|hof|easter_egg>");
                sender.sendMessage(ChatColor.YELLOW + "/kmclobbynpc rank <set <1-10>|own|remove <n|own|all|here>|list|refresh|clean>");
                sender.sendMessage(ChatColor.YELLOW + "/kmclobbynpc list");
                sender.sendMessage(ChatColor.YELLOW + "/kmclobbynpc despawnall  " + ChatColor.GRAY + "(lobby NPCs and rank NPCs)");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "spawn" -> {
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "Specify type: stats, hof, or easter_egg");
                        return true;
                    }
                    LobbyNPCManager.NPCType type;
                    try {
                        type = LobbyNPCManager.NPCType.valueOf(args[1].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(ChatColor.RED + "Invalid type. Use: stats, hof, or easter_egg");
                        return true;
                    }
                    plugin.getLobbyNPCManager().spawnNPC(p.getLocation(), type);
                    sender.sendMessage(ChatColor.GREEN + "" + type.name() + " NPC spawned at your location.");
                }
                case "despawnall" -> {
                    plugin.getLobbyNPCManager().despawnAll();
                    var rm = plugin.getRankNpcManager();
                    int ranks = rm.getSlots().size();
                    rm.removeAllSlots();
                    sender.sendMessage(ChatColor.GREEN + "All KMC lobby NPCs removed" + ChatColor.GRAY
                            + " (" + ranks + " rank NPC slot" + (ranks == 1 ? "" : "s") + " cleared too).");
                }
                case "list" -> {
                    var rm = plugin.getRankNpcManager();
                    sender.sendMessage(ChatColor.GOLD + "Rank NPCs: " + ChatColor.WHITE + rm.getSlots().size() + "/"
                            + nl.kmc.kmccore.npc.RankNpcManager.MAX_SLOT + ChatColor.GRAY + "  own position: "
                            + (rm.getOwnSpot() != null ? ChatColor.GREEN + "yes" : ChatColor.GRAY + "no"));
                    rm.getSlots().forEach((n, l) -> sender.sendMessage(ChatColor.GRAY + "  #" + n + " " + ChatColor.WHITE
                            + (l.getWorld() != null ? l.getWorld().getName() : "?") + " " + l.getBlockX() + ", "
                            + l.getBlockY() + ", " + l.getBlockZ()));
                }
                case "rank" -> rank(p, args);
                default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
            }
            return true;
        }

        /** /kmclobbynpc rank set <1-10> | own | remove <n|own|all|here> | list | refresh | clean */
        private void rank(Player p, String[] args) {
            var rm = plugin.getRankNpcManager();
            String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
            switch (sub) {
                case "set" -> {
                    int n = args.length >= 3 ? parseInt(args[2]) : -1;
                    if (n < 1 || n > nl.kmc.kmccore.npc.RankNpcManager.MAX_SLOT) {
                        p.sendMessage(ChatColor.RED + "Usage: /kmclobbynpc rank set <1-10>");
                        return;
                    }
                    rm.setSlot(n, p.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Rank NPC #" + n + " placed at your position.");
                }
                case "own" -> {
                    rm.setOwnSpot(p.getLocation().add(0, 2, 0));
                    p.sendMessage(ChatColor.GREEN + "\"Your position\" display placed (everyone sees their own rank).");
                }
                case "remove" -> {
                    String what = args.length >= 3 ? args[2].toLowerCase() : "";
                    switch (what) {
                        case "all" -> {
                            rm.removeAllSlots();
                            p.sendMessage(ChatColor.GREEN + "All rank NPCs removed.");
                        }
                        case "own" -> p.sendMessage(rm.removeOwnSpot()
                                ? ChatColor.GREEN + "Own-position display removed." : ChatColor.GRAY + "There is none.");
                        case "here", "" -> {
                            int n = rm.removeNear(p.getLocation(), 4.0);
                            p.sendMessage(n > 0 ? ChatColor.GREEN + "Removed " + n + " rank NPC part" + (n == 1 ? "" : "s")
                                    + " within 4 blocks." : ChatColor.GRAY + "No rank NPC within 4 blocks. Stand next to it, or use /kmclobbynpc rank remove <1-10|all>.");
                        }
                        default -> {
                            int n = parseInt(what);
                            p.sendMessage(n > 0 && rm.removeSlot(n)
                                    ? ChatColor.GREEN + "Rank NPC #" + n + " removed." : ChatColor.GRAY + "No rank NPC #" + what + ".");
                        }
                    }
                }
                case "refresh" -> {
                    rm.refresh();
                    p.sendMessage(ChatColor.GREEN + "Rank NPCs refreshed.");
                }
                case "clean" -> {
                    int n = rm.sweepOrphans();
                    p.sendMessage(ChatColor.GREEN + "Removed " + n + " leftover rank NPC entit" + (n == 1 ? "y" : "ies") + ".");
                }
                default -> {
                    p.sendMessage(ChatColor.GOLD + "Rank NPCs: " + ChatColor.WHITE + rm.getSlots().size() + "/"
                            + nl.kmc.kmccore.npc.RankNpcManager.MAX_SLOT);
                    p.sendMessage(ChatColor.GRAY + "/kmclobbynpc rank set <1-10> | own | remove <n|own|all|here> | list | refresh | clean");
                }
            }
        }

        private static int parseInt(String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        }

        
        public java.util.List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (args.length == 1) out.addAll(java.util.List.of("spawn", "rank", "list", "despawnall"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) out.addAll(java.util.List.of("stats", "hof", "easter_egg"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("rank"))
                out.addAll(java.util.List.of("set", "own", "remove", "list", "refresh", "clean"));
            else if (args.length == 3 && args[0].equalsIgnoreCase("rank") && args[1].equalsIgnoreCase("set")) {
                for (int i = 1; i <= nl.kmc.kmccore.npc.RankNpcManager.MAX_SLOT; i++) out.add(String.valueOf(i));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("rank") && args[1].equalsIgnoreCase("remove")) {
                out.addAll(java.util.List.of("here", "all", "own"));
                for (int n : plugin.getRankNpcManager().slotNumbers()) out.add(String.valueOf(n));
            }
            String prefix = args[args.length - 1].toLowerCase();
            out.removeIf(s -> !s.toLowerCase().startsWith(prefix));
            return out;
        }
    }
}