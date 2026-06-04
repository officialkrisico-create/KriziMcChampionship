package nl.kmc.tournament.command;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /kmc — thin alias that forwards tournament control to the single V1 engine
 * in KMCCore ({@code AutomationManager} + {@code TournamentManager}).
 *
 * <pre>
 *   /kmc start   → start tournament + automation   (same as /kmcauto start)
 *   /kmc end     → stop automation + end tournament
 *   /kmc pause   → pause automation
 *   /kmc resume  → resume automation
 *   /kmc skip    → force-skip the current game
 *   /kmc status  → automation status
 * </pre>
 */
public final class TournamentCommand implements CommandExecutor, TabCompleter {

    private static final String PERM   = "kmc.admin";
    private static final String PREFIX = ChatColor.GOLD + "[KMC] " + ChatColor.RESET;

    private final KMCCore core;

    public TournamentCommand(KMCCore core) { this.core = core; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }

        var auto = core.getAutomationManager();
        var tournament = core.getTournamentManager();

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (!tournament.isActive()) tournament.start();
                if (auto.isRunning()) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Automation already running.");
                } else {
                    auto.start();
                    sender.sendMessage(PREFIX + ChatColor.GREEN + "Tournament starting!");
                }
            }
            case "end", "stop" -> {
                auto.stop();
                tournament.endTournament();
                sender.sendMessage(PREFIX + ChatColor.RED + "Tournament ended.");
            }
            case "pause" -> {
                auto.pause();
                sender.sendMessage(PREFIX + ChatColor.YELLOW + "Automation paused.");
            }
            case "resume" -> {
                auto.resume();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Automation resumed.");
            }
            case "skip" -> {
                if (core.getGameManager().forceSkipCurrentGame())
                    sender.sendMessage(PREFIX + ChatColor.AQUA + "Current game skipped.");
                else
                    sender.sendMessage(PREFIX + ChatColor.RED + "No active game to skip.");
            }
            case "status" -> {
                sender.sendMessage(PREFIX + "Automation: §e" + auto.getState().name());
                sender.sendMessage(PREFIX + "Round: §e" + tournament.getCurrentRound()
                        + "§7/§e" + tournament.getTotalRounds()
                        + "  §7×§e" + tournament.getMultiplier());
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return List.of("start", "end", "pause", "resume", "skip", "status").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "§eTournament control (alias of /kmcauto):");
        sender.sendMessage("§7  /kmc start|end|pause|resume|skip|status");
    }
}
