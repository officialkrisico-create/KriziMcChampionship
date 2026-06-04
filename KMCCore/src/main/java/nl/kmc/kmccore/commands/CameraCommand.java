package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.CinematicManager;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /kmccamera — in-game camera route editor.
 *
 * <pre>
 * /kmccamera list                              — list all saved routes
 * /kmccamera info   <route>                    — show route details
 * /kmccamera create <route> [description...]   — start recording a new route
 * /kmccamera addpoint [ticks] [interp]         — add current location as waypoint
 * /kmccamera addpoint [ticks] [interp] title=... subtitle=... actionbar=...
 * /kmccamera removepoint                       — remove last added waypoint
 * /kmccamera save                              — save the route and exit recording
 * /kmccamera discard                           — discard the route and exit recording
 * /kmccamera preview [route]                   — preview a route (for yourself only)
 * /kmccamera delete <route>                    — delete a saved route
 * /kmccamera reload                            — reload cameras.yml from disk
 * /kmccamera stop                              — stop all active cinematics
 * </pre>
 */
public class CameraCommand implements CommandExecutor, TabCompleter {

    private static final List<String> INTERP_VALUES = Arrays.stream(InterpolationType.values())
            .map(v -> v.name().toLowerCase()).toList();

    private final KMCCore plugin;

    public CameraCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.camera.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        CinematicManager cm = plugin.getCinematicManager();

        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            // ── list ──────────────────────────────────────────────────────────
            case "list" -> {
                var routes = cm.getAllRoutes();
                if (routes.isEmpty()) { sender.sendMessage("§7No camera routes saved."); return true; }
                sender.sendMessage("§6§lCamera Routes (" + routes.size() + "):");
                routes.forEach(r -> sender.sendMessage(
                        "  §e" + r.getId() + " §8(" + r.size() + " pts, "
                        + r.totalTicks() + " ticks) §7— " + r.getDescription()));
            }

            // ── info ──────────────────────────────────────────────────────────
            case "info" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmccamera info <route>"); return true; }
                String id = args[1];
                cm.getRoute(id).ifPresentOrElse(r -> {
                    sender.sendMessage("§6Route: §e" + r.getId());
                    sender.sendMessage("§7Description: §f" + r.getDescription());
                    sender.sendMessage("§7Waypoints: §e" + r.size());
                    sender.sendMessage("§7Total ticks: §e" + r.totalTicks()
                            + " §8(~§e" + (r.totalTicks() / 20) + "s§8)");
                    for (int i = 0; i < r.getWaypoints().size(); i++) {
                        var wp = r.getWaypoints().get(i);
                        sender.sendMessage(String.format("  §8[%d] §7%.1f,%.1f,%.1f yaw=%.1f pitch=%.1f §8[%s, %dt]",
                                i, wp.getX(), wp.getY(), wp.getZ(),
                                wp.getYaw(), wp.getPitch(),
                                wp.getInterpolation().name(), wp.getDurationTicks()));
                    }
                }, () -> sender.sendMessage("§cRoute '" + id + "' not found."));
            }

            // ── create ────────────────────────────────────────────────────────
            case "create" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayers only."); return true; }
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmccamera create <routeId> [description...]"); return true; }
                String id   = args[1];
                String desc = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : id;
                cm.startRecording(p.getUniqueId(), id, desc);
                sender.sendMessage("§a[Camera] Recording started for route §e" + id + "§a.");
                sender.sendMessage("§7Use §e/kmccamera addpoint§7 to add waypoints at your location.");
                sender.sendMessage("§7Use §e/kmccamera save§7 when done or §e/kmccamera discard§7 to cancel.");
            }

            // ── addpoint ──────────────────────────────────────────────────────
            case "addpoint" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayers only."); return true; }
                if (!cm.isRecording(p.getUniqueId())) {
                    sender.sendMessage("§cYou are not recording a route. Use /kmccamera create first.");
                    return true;
                }

                int durationTicks = args.length > 1 ? parseIntSafe(args[1], 40) : 40;
                InterpolationType interp = args.length > 2
                        ? InterpolationType.parse(args[2]) : InterpolationType.SMOOTH;

                // Parse optional named parameters: title=... subtitle=... actionbar=...
                String title = "", subtitle = "", actionBar = "";
                for (int i = 3; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("title="))     title     = a.substring(6).replace("_", " ");
                    if (a.startsWith("subtitle="))  subtitle  = a.substring(9).replace("_", " ");
                    if (a.startsWith("actionbar=")) actionBar = a.substring(10).replace("_", " ");
                }

                CameraWaypoint wp = new CameraWaypoint(
                        p.getLocation(), durationTicks, interp, title, subtitle, actionBar);
                cm.addWaypoint(p.getUniqueId(), wp);

                int count = cm.getPendingRoute(p.getUniqueId())
                        .map(CameraRoute::size).orElse(0);
                sender.sendMessage("§a[Camera] Waypoint §e#" + count + "§a added. "
                        + "§8(" + durationTicks + "t, " + interp.name() + ")"
                        + (title.isBlank() ? "" : " title=§f" + title));
            }

            // ── removepoint ───────────────────────────────────────────────────
            case "removepoint" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayers only."); return true; }
                if (!cm.isRecording(p.getUniqueId())) { sender.sendMessage("§cNot recording."); return true; }
                if (cm.removeLastWaypoint(p.getUniqueId()))
                    sender.sendMessage("§a[Camera] Last waypoint removed.");
                else
                    sender.sendMessage("§7No waypoints to remove.");
            }

            // ── save ──────────────────────────────────────────────────────────
            case "save" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayers only."); return true; }
                cm.saveRecording(p.getUniqueId()).ifPresentOrElse(
                        r -> sender.sendMessage("§a[Camera] Route §e" + r.getId()
                                + "§a saved with §e" + r.size() + "§a waypoints."),
                        () -> sender.sendMessage("§cYou are not recording a route."));
            }

            // ── discard ───────────────────────────────────────────────────────
            case "discard" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayers only."); return true; }
                cm.discardRecording(p.getUniqueId());
                sender.sendMessage("§7[Camera] Recording discarded.");
            }

            // ── preview ───────────────────────────────────────────────────────
            case "preview" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cPlayers only."); return true; }

                // If previewing a saved route, use its ID. If no arg, preview the
                // route currently being recorded. Both go through previewRoute so
                // the controller is tracked + watchdog-guarded (no stuck spectator).
                if (args.length > 1) {
                    String id = args[1];
                    boolean ok = cm.getRoute(id)
                            .map(r -> plugin.getCinematicManager().previewRoute(r, p,
                                    () -> p.sendMessage("§a[Camera] Preview complete.")))
                            .orElse(false);
                    if (!ok) sender.sendMessage("§cRoute '" + id + "' not found or empty.");
                } else if (cm.isRecording(p.getUniqueId())) {
                    cm.getPendingRoute(p.getUniqueId()).ifPresentOrElse(r -> {
                        if (r.isEmpty()) { p.sendMessage("§cNo waypoints to preview."); return; }
                        plugin.getCinematicManager().previewRoute(r, p,
                                () -> p.sendMessage("§a[Camera] Preview complete."));
                    }, () -> sender.sendMessage("§cNothing to preview."));
                } else {
                    sender.sendMessage("§cUsage: /kmccamera preview <routeId>");
                }
            }

            // ── delete ────────────────────────────────────────────────────────
            case "delete" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmccamera delete <route>"); return true; }
                String id = args[1];
                if (!cm.routeExists(id)) { sender.sendMessage("§cRoute '" + id + "' not found."); return true; }
                cm.deleteRoute(id);
                sender.sendMessage("§a[Camera] Route §e" + id + "§a deleted.");
            }

            // ── reload ────────────────────────────────────────────────────────
            case "reload" -> {
                cm.reload();
                sender.sendMessage("§a[Camera] cameras.yml reloaded. Routes: " + cm.getAllRoutes().size());
            }

            // ── stop ──────────────────────────────────────────────────────────
            case "stop" -> {
                cm.stopAll();
                sender.sendMessage("§a[Camera] All active cinematics stopped.");
            }

            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("list","info","create","addpoint","removepoint","save","discard","preview","delete","reload","stop")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2) {
            if (List.of("info","preview","delete").contains(args[0].toLowerCase()))
                return plugin.getCinematicManager().getAllRoutes().stream()
                        .map(CameraRoute::getId)
                        .filter(id -> id.startsWith(args[1]))
                        .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("addpoint"))
            return List.of("20","40","60","80","100");
        if (args.length == 3 && args[0].equalsIgnoreCase("addpoint"))
            return INTERP_VALUES.stream().filter(v -> v.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage("§6/kmccamera §e<list|info|create|addpoint|removepoint|save|discard|preview|delete|reload|stop>");
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
