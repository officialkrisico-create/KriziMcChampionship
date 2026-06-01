package nl.kmc.tournament.command;

import nl.kmc.tournament.engine.TournamentEngine;
import nl.kmc.tournament.recovery.TournamentRecoveryEngine;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /kmcrecover — emergency recovery from last snapshot.
 *
 *   /kmcrecover status   — show last snapshot info
 *   /kmcrecover restore  — restore tournament from last snapshot
 */
public final class RecoverCommand implements CommandExecutor {

    private static final String PERM   = "kmc.admin";
    private static final String PREFIX = ChatColor.GOLD + "[KMC] " + ChatColor.RESET;

    private final TournamentEngine         engine;
    private final TournamentRecoveryEngine recovery;

    public RecoverCommand(TournamentEngine engine, TournamentRecoveryEngine recovery) {
        this.engine   = engine;
        this.recovery = recovery;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "status" -> {
                recovery.getLastSnapshot().ifPresentOrElse(
                        s -> sender.sendMessage(PREFIX + "§eLast snapshot: §f" + s),
                        () -> sender.sendMessage(PREFIX + ChatColor.RED + "No snapshot available."));
            }
            case "restore" -> {
                sender.sendMessage(PREFIX + ChatColor.YELLOW + "Attempting recovery…");
                boolean ok = recovery.restore(engine);
                sender.sendMessage(ok
                        ? PREFIX + ChatColor.GREEN + "Recovery successful."
                        : PREFIX + ChatColor.RED + "Recovery failed — no snapshot available.");
            }
            default -> {
                sender.sendMessage(PREFIX + "§eUsage: /kmcrecover <status|restore>");
            }
        }
        return true;
    }
}
