package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.snapshot.SnapshotManager;
import org.bukkit.ChatColor;
import org.bukkit.command.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /event — admin tooling for tournament simulation and snapshot rollback.
 *
 * <ul>
 *   <li>/event simulate &lt;rounds&gt; [players] — run a dry-run sim with bots</li>
 *   <li>/event snapshot [label] — capture current state manually</li>
 *   <li>/event rollback [label] — restore a snapshot (defaults to latest)</li>
 *   <li>/event listsnapshots — list available snapshots</li>
 * </ul>
 */
public class EventCommand implements CommandExecutor, TabCompleter {

    private static final SimpleDateFormat TS_FMT = new SimpleDateFormat("HH:mm:ss");

    private final KMCCore plugin;

    public EventCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.event.admin") && !sender.hasPermission("kmc.tournament.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "simulate", "sim" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /event simulate <rounds> [players]");
                    return true;
                }
                int rounds, players = 16;
                try { rounds = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Ongeldig getal: " + args[1]);
                    return true;
                }
                if (args.length >= 3) {
                    try { players = Integer.parseInt(args[2]); }
                    catch (NumberFormatException e) {
                        sender.sendMessage(ChatColor.RED + "Ongeldig getal: " + args[2]);
                        return true;
                    }
                }
                plugin.getSimulationEngine().run(sender, rounds, players);
            }

            case "snapshot", "snap" -> {
                String snapLabel = args.length >= 2
                        ? String.join("_", java.util.Arrays.copyOfRange(args, 1, args.length))
                        : "manual-" + System.currentTimeMillis();
                var s = plugin.getSnapshotManager().snapshot(snapLabel);
                sender.sendMessage(ChatColor.GREEN + "Snapshot opgeslagen: " + ChatColor.YELLOW + s.label
                        + ChatColor.GRAY + " (" + s.players.size() + " players, "
                        + s.teams.size() + " teams)");
            }

            case "rollback" -> {
                if (args.length < 2) {
                    // Latest
                    boolean ok = plugin.getSnapshotManager().restoreLatest();
                    if (ok) {
                        SnapshotManager.Snapshot latest = plugin.getSnapshotManager().getLatest();
                        sender.sendMessage(ChatColor.GREEN + "Rollback voltooid naar laatste snapshot: "
                                + ChatColor.YELLOW + (latest != null ? latest.label : "?"));
                    } else {
                        sender.sendMessage(ChatColor.RED + "Geen snapshots beschikbaar.");
                    }
                } else {
                    String snapLabel = String.join("_", java.util.Arrays.copyOfRange(args, 1, args.length));
                    boolean ok = plugin.getSnapshotManager().restore(snapLabel);
                    if (ok) sender.sendMessage(ChatColor.GREEN + "Rollback voltooid naar: "
                            + ChatColor.YELLOW + snapLabel);
                    else    sender.sendMessage(ChatColor.RED + "Snapshot niet gevonden: " + snapLabel);
                }
            }

            case "listsnapshots", "list" -> {
                List<SnapshotManager.Snapshot> all = plugin.getSnapshotManager().listAll();
                if (all.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Geen snapshots in de ring buffer.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Snapshots (" + all.size() + ") ===");
                for (SnapshotManager.Snapshot s : all) {
                    String when = TS_FMT.format(new Date(s.timestampMs));
                    sender.sendMessage(ChatColor.YELLOW + "• " + s.label
                            + ChatColor.GRAY + " — " + when
                            + " (" + s.players.size() + " players, " + s.teams.size() + " teams)");
                }
            }

            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== /event ===");
        s.sendMessage(ChatColor.YELLOW + "/event simulate <rounds> [players]"
                + ChatColor.GRAY + " — run dry-run sim met bots (default 16 players)");
        s.sendMessage(ChatColor.YELLOW + "/event snapshot [label]"
                + ChatColor.GRAY + " — sla huidige state op");
        s.sendMessage(ChatColor.YELLOW + "/event rollback [label]"
                + ChatColor.GRAY + " — herstel een snapshot (zonder label = laatste)");
        s.sendMessage(ChatColor.YELLOW + "/event listsnapshots"
                + ChatColor.GRAY + " — toon beschikbare snapshots");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("simulate", "snapshot", "rollback", "listsnapshots").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rollback")) {
            return plugin.getSnapshotManager().listAll().stream()
                    .map(snap -> snap.label)
                    .filter(o -> o.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
