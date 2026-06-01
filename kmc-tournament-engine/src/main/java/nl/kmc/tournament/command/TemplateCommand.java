package nl.kmc.tournament.command;

import nl.kmc.tournament.template.TemplateManager;
import nl.kmc.tournament.template.TournamentTemplate;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /kmctemplate — manage tournament templates.
 *
 *   /kmctemplate list
 *   /kmctemplate info <id>
 *   /kmctemplate delete <id>
 */
public final class TemplateCommand implements CommandExecutor, TabCompleter {

    private static final String PERM   = "kmc.admin";
    private static final String PREFIX = ChatColor.GOLD + "[KMC] " + ChatColor.RESET;

    private final TemplateManager templates;

    public TemplateCommand(TemplateManager templates) {
        this.templates = templates;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) { sendHelp(sender); return true; }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                var all = templates.getAll();
                if (all.isEmpty()) { sender.sendMessage(PREFIX + "No templates saved."); yield true; }
                sender.sendMessage(PREFIX + "§eSaved templates:");
                all.forEach(t -> sender.sendMessage("§7  §e" + t.getId()
                        + "§7 — " + t.getDisplayName()
                        + " §8(" + t.getTotalRounds() + " rounds)"));
                yield true;
            }
            case "info" -> {
                if (args.length < 2) { sender.sendMessage(PREFIX + "Usage: /kmctemplate info <id>"); yield true; }
                Optional<TournamentTemplate> opt = templates.load(args[1]);
                if (opt.isEmpty()) { sender.sendMessage(PREFIX + ChatColor.RED + "Template not found."); yield true; }
                TournamentTemplate t = opt.get();
                sender.sendMessage(PREFIX + "§eTemplate: " + t.getDisplayName());
                sender.sendMessage("§7  ID: §f" + t.getId());
                sender.sendMessage("§7  Rounds: §f" + t.getTotalRounds());
                sender.sendMessage("§7  Voting: §f" + (t.isVotingEnabled() ? "Yes (" + t.getVotingDurationSeconds() + "s)" : "No"));
                sender.sendMessage("§7  Games: §f" + String.join(", ", t.getGameRotation()));
                yield true;
            }
            case "delete" -> {
                if (args.length < 2) { sender.sendMessage(PREFIX + "Usage: /kmctemplate delete <id>"); yield true; }
                boolean deleted = templates.delete(args[1]);
                sender.sendMessage(deleted
                        ? PREFIX + ChatColor.GREEN + "Deleted template: " + args[1]
                        : PREFIX + ChatColor.RED + "Template not found: " + args[1]);
                yield true;
            }
            default -> { sendHelp(sender); yield true; }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("list","info","delete").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("delete"))) {
            List<String> ids = new ArrayList<>();
            templates.getAll().forEach(t -> ids.add(t.getId()));
            return ids.stream().filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "§eTemplate commands:");
        sender.sendMessage("§7  /kmctemplate list");
        sender.sendMessage("§7  /kmctemplate info <id>");
        sender.sendMessage("§7  /kmctemplate delete <id>");
    }
}
