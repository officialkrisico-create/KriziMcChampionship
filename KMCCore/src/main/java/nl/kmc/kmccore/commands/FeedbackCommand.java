package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.EditionArchive;
import nl.kmc.kmccore.tournament.EventEndSequence;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /kmcfeedback &lt;1-5&gt; [comment…] — rate the event (the clickable stars after a tournament run this).
 * /kmcfeedback results            — admins: average rating per edition.
 */
public final class FeedbackCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public FeedbackCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        var lm = plugin.getLanguageManager();
        if (args.length >= 1 && args[0].equalsIgnoreCase("results")) {
            if (!sender.hasPermission("kmc.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
            var editions = EditionArchive.readAll(plugin);
            if (editions.isEmpty()) { sender.sendMessage(MessageUtil.color("&7Nog geen edities in het archief.")); return true; }
            sender.sendMessage(MessageUtil.color("&6[KMC] Feedback per editie:"));
            for (var e : editions) {
                sender.sendMessage(MessageUtil.color("  &eKMC #" + e.number() + " &8— "
                        + (e.feedbackVotes() == 0 ? "&7geen stemmen"
                        : "&a" + String.format(java.util.Locale.ROOT, "%.1f", e.feedbackAverage()) + "&7/5 &8(" + e.feedbackVotes() + " stemmen)")));
            }
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }

        int edition = EventEndSequence.feedbackEdition();
        if (edition < 0) { p.sendMessage(lm.tr(p, "feedback.closed")); return true; }
        int stars;
        try { stars = Integer.parseInt(args.length > 0 ? args[0] : "x"); }
        catch (NumberFormatException e) { p.sendMessage(lm.tr(p, "feedback.usage")); return true; }
        if (stars < 1 || stars > 5) { p.sendMessage(lm.tr(p, "feedback.usage")); return true; }
        String comment = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
        if (comment != null && comment.length() > 200) comment = comment.substring(0, 200);

        EditionArchive.addFeedback(plugin, edition, p.getUniqueId(), stars, comment);
        p.sendMessage(lm.tr(p, "feedback.thanks", stars));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("1", "2", "3", "4", "5", "results");
        return List.of();
    }
}
