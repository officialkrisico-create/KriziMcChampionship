package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /kmcvote [open|<number>]
 *
 * <ul>
 *   <li>{@code /kmcvote}          — opens the GUI if a vote is active</li>
 *   <li>{@code /kmcvote open}     — same (triggered by the clickable chat prompt)</li>
 *   <li>{@code /kmcvote <number>} — casts a vote for option N directly</li>
 * </ul>
 */
public class VoteCommand implements CommandExecutor {

    private final KMCCore plugin;

    public VoteCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can vote.");
            return true;
        }

        if (!plugin.getGameManager().isVotingActive()) {
            player.sendMessage(MessageUtil.get("vote.not-active"));
            return true;
        }

        // No arg OR "open" → open the GUI
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            plugin.getVoteGuiListener().openVoteGui(player);
            return true;
        }

        // Numeric → direct vote
        int option;
        try {
            option = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(MessageUtil.get("invalid-number"));
            return true;
        }
        plugin.getGameManager().castVote(player, option);
        return true;
    }
}
