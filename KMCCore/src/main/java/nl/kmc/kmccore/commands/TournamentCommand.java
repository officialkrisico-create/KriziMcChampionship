package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;

/** /kmctournament <start|stop|end|status|reset|hardreset> */
public class TournamentCommand implements CommandExecutor {

    private final KMCCore plugin;
    public TournamentCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.tournament.admin")) {
            sender.sendMessage(MessageUtil.get("no-permission")); return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (!plugin.getTournamentManager().start())
                    sender.sendMessage(MessageUtil.get("tournament.already-running"));
            }
            case "stop" -> {
                if (!plugin.getTournamentManager().stop())
                    sender.sendMessage(MessageUtil.get("tournament.not-running"));
            }
            case "end" -> {
                // Announces winner and zeros points for next event
                plugin.getTournamentManager().endTournament();
                sender.sendMessage(MessageUtil.color("&a✔ Toernooi beëindigd. Punten zijn gereset."));
            }
            case "reset" -> {
                plugin.getTournamentManager().reset();
                sender.sendMessage(MessageUtil.color("&aSoft reset gedaan: tournament stats gewist, lifetime stats behouden."));
            }
            case "hardreset" -> {
                plugin.getTournamentManager().hardReset();
                sender.sendMessage(MessageUtil.color("&c⚠ HARD RESET: alle data gewist inclusief lifetime stats."));
            }
            case "status" -> {
                var tm = plugin.getTournamentManager();
                sender.sendMessage(MessageUtil.get("tournament.status-header"));
                sender.sendMessage(tm.isActive()
                        ? MessageUtil.get("tournament.status-active")
                        : MessageUtil.get("tournament.status-inactive"));
                sender.sendMessage(MessageUtil.get("tournament.status-round").replace("{round}", String.valueOf(tm.getCurrentRound())));
                sender.sendMessage(MessageUtil.get("tournament.status-multiplier").replace("{multiplier}", String.valueOf(tm.getMultiplier())));
                var game = plugin.getGameManager().getActiveGame();
                sender.sendMessage(game != null
                        ? MessageUtil.get("tournament.status-game").replace("{game}", game.getDisplayName())
                        : MessageUtil.get("tournament.status-no-game"));
                sender.sendMessage(MessageUtil.color("&7Games gespeeld: &e"
                        + plugin.getGameManager().getPlayedGamesThisTournament().size()));
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}",
                "/kmctournament <start|stop|end|status|reset|hardreset>"));
    }
}
