package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;

/** /kmcround <set|next|info> [round] */
public class RoundCommand implements CommandExecutor {

    private final KMCCore plugin;
    public RoundCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.round.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (args.length < 2) { usage(sender); return true; }
                try {
                    int r = Integer.parseInt(args[1]);
                    if (!plugin.getTournamentManager().setRound(r))
                        sender.sendMessage("&cOngeldig rondenummer (1-" + plugin.getTournamentManager().getTotalRounds() + ").");
                    else
                        sender.sendMessage(MessageUtil.get("round.set").replace("{round}", String.valueOf(r)));
                } catch (NumberFormatException e) { sender.sendMessage(MessageUtil.get("invalid-number")); }
            }
            case "next" -> {
                if (!plugin.getTournamentManager().nextRound())
                    sender.sendMessage(MessageUtil.get("round.max-reached"));
            }
            case "info" -> {
                int r   = plugin.getTournamentManager().getCurrentRound();
                double m= plugin.getTournamentManager().getMultiplier();
                sender.sendMessage(MessageUtil.get("tournament.status-round").replace("{round}", String.valueOf(r)));
                sender.sendMessage(MessageUtil.get("tournament.status-multiplier").replace("{multiplier}", String.valueOf(m)));
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) { s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}", "/kmcround <set|next|info> [round]")); }
}
