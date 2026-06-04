package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.CinematicManager;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /kmcpresentation — runtime control over active cinematics.
 *
 * <pre>
 * /kmcpresentation start  <routeId> [player|all]  — force-play a route
 * /kmcpresentation skip                            — skip / stop all active cinematics
 * /kmcpresentation stop   <routeId>               — stop a specific route
 * /kmcpresentation status                          — show what is currently playing
 * /kmcpresentation routes                          — alias for /kmccamera list
 * /kmcpresentation reload                          — reload cameras.yml
 * </pre>
 */
public class PresentationCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public PresentationCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }

        CinematicManager cm = plugin.getCinematicManager();

        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "start" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmcpresentation start <routeId> [player|all]"); return true; }
                String routeId = args[1];
                if (!cm.routeExists(routeId)) { sender.sendMessage("§cRoute '" + routeId + "' not found. See /kmccamera list."); return true; }

                List<Player> targets;
                if (args.length >= 3 && !args[2].equalsIgnoreCase("all")) {
                    Player target = Bukkit.getPlayer(args[2]);
                    if (target == null) { sender.sendMessage("§cPlayer '" + args[2] + "' not online."); return true; }
                    targets = List.of(target);
                } else {
                    targets = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
                }

                boolean ok = cm.playRoute(routeId, targets,
                        () -> sender.sendMessage("§a[Presentation] Route '§e" + routeId + "§a' complete."));
                if (ok) sender.sendMessage("§a[Presentation] Playing '§e" + routeId + "§a' for §e" + targets.size() + "§a player(s).");
                else    sender.sendMessage("§c[Presentation] Route '" + routeId + "' is empty.");
            }

            case "skip" -> {
                cm.stopAll();
                sender.sendMessage("§a[Presentation] All cinematics skipped.");
                Bukkit.broadcastMessage("§7[KMC] Cinematic skipped.");
            }

            case "stop" -> {
                if (args.length < 2) { cm.stopAll(); sender.sendMessage("§a[Presentation] All stopped."); return true; }
                cm.stopRoute(args[1]);
                sender.sendMessage("§a[Presentation] Stopped route '§e" + args[1] + "§a'.");
            }

            case "status" -> {
                var routes = cm.getAllRoutes();
                sender.sendMessage("§6Loaded routes: §e" + routes.size());
                routes.forEach(r -> sender.sendMessage("  §7" + r.getId() + " §8— §e" + r.size() + " pts"));
            }

            case "routes" -> {
                var routes = cm.getAllRoutes();
                if (routes.isEmpty()) { sender.sendMessage("§7No routes saved."); return true; }
                sender.sendMessage("§6Camera Routes:");
                routes.forEach(r -> sender.sendMessage(
                        "  §e" + r.getId() + " §8(" + r.size() + " pts) §7" + r.getDescription()));
            }

            case "reload" -> {
                cm.reload();
                sender.sendMessage("§a[Presentation] cameras.yml reloaded.");
            }

            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","skip","stop","status","routes","reload")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop")))
            return plugin.getCinematicManager().getAllRoutes().stream()
                    .map(CameraRoute::getId)
                    .filter(id -> id.startsWith(args[1]))
                    .collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("start"))
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.startsWith(args[2]))
                    .collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage("§6/kmcpresentation §e<start|skip|stop|status|routes|reload>");
    }
}
