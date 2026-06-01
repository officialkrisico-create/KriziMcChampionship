package nl.kmc.tournament.command;

import nl.kmc.tournament.engine.TournamentEngine;
import nl.kmc.tournament.recovery.TournamentRecoveryEngine;
import nl.kmc.tournament.template.TemplateManager;
import nl.kmc.tournament.template.TournamentTemplate;
import nl.kmc.tournament.voting.VotingEngine;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /kmc — root command for tournament control.
 *
 * Subcommands:
 *   /kmc start            — start the tournament
 *   /kmc end              — end the tournament
 *   /kmc pause            — pause automation
 *   /kmc resume           — resume automation
 *   /kmc skip             — skip the current phase
 *   /kmc status           — show current phase & round
 *   /kmc vote <gameId>    — cast a vote (players)
 *   /kmc ready            — confirm ready (players)
 */
public final class TournamentCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN  = "kmc.admin";
    private static final String PREFIX      = ChatColor.GOLD + "[KMC] " + ChatColor.RESET;

    private final TournamentEngine         engine;
    private final VotingEngine             voting;
    private final TournamentRecoveryEngine recovery;
    private final TemplateManager          templates;

    public TournamentCommand(TournamentEngine engine, VotingEngine voting,
                             TournamentRecoveryEngine recovery, TemplateManager templates) {
        this.engine    = engine;
        this.voting    = voting;
        this.recovery  = recovery;
        this.templates = templates;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start"  -> adminCmd(sender, () -> {
                engine.startTournament();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Tournament starting!");
            });
            case "end"    -> adminCmd(sender, () -> {
                engine.endTournament();
                sender.sendMessage(PREFIX + ChatColor.RED + "Tournament ending…");
            });
            case "pause"  -> adminCmd(sender, () -> {
                engine.pause();
                sender.sendMessage(PREFIX + ChatColor.YELLOW + "Tournament paused.");
            });
            case "resume" -> adminCmd(sender, () -> {
                engine.resume();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Tournament resumed.");
            });
            case "skip"   -> adminCmd(sender, () -> {
                engine.skip();
                sender.sendMessage(PREFIX + ChatColor.AQUA + "Phase skipped.");
            });
            case "status" -> {
                var phase = engine.getTournament().getPhase();
                int round = engine.getTournament().getCurrentRound();
                int total = engine.getTournament().getTotalRounds();
                sender.sendMessage(PREFIX + "Phase: §e" + phase.name().replace('_',' ')
                        + ChatColor.RESET + "  Round: §e" + round + "/" + total);
                yield true;
            }
            case "vote"   -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(PREFIX + "Only players can vote.");
                    yield true;
                }
                if (args.length < 2) { p.sendMessage(PREFIX + "Usage: /kmc vote <gameId>"); yield true; }
                boolean ok = voting.castVote(p.getUniqueId(), args[1]);
                if (!ok) p.sendMessage(PREFIX + ChatColor.RED + "No active vote, or invalid game ID.");
                yield true;
            }
            case "ready"  -> {
                // ReadyUpService will handle the actual confirmation logic.
                // For now, just acknowledge.
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Ready confirmed!");
                yield true;
            }
            default -> { sendHelp(sender); yield true; }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("start","end","pause","resume","skip","status","vote","ready"));
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean adminCmd(CommandSender sender, Runnable action) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }
        action.run();
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "§eCommands:");
        sender.sendMessage("§7  /kmc start|end|pause|resume|skip|status");
        sender.sendMessage("§7  /kmc vote <gameId>");
        sender.sendMessage("§7  /kmc ready");
    }
}
