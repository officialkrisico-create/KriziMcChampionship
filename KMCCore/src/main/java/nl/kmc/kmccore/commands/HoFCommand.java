package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.HallOfFameManager;
import nl.kmc.kmccore.models.HoFRecord;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * /kmchof &lt;list|clear|info&gt;
 *
 * <p>Subcommands:
 * <ul>
 *   <li>list — shows all HoF records</li>
 *   <li>info &lt;category&gt; — shows detailed info on one category</li>
 *   <li>clear &lt;category|full&gt; — clears one category or ALL records</li>
 * </ul>
 */
public class HoFCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd-MM-yyyy");

    public HoFCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.hof.admin")) {
            sender.sendMessage(MessageUtil.get("no-permission"));
            return true;
        }

        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "list" -> {
                sender.sendMessage(MessageUtil.color("&6&l═══ Hall of Fame ═══"));
                var categories = plugin.getHallOfFameManager().getCategories();
                if (categories.isEmpty()) {
                    sender.sendMessage(MessageUtil.color("&7Geen categorieën geregistreerd."));
                    return true;
                }
                for (var cat : categories) {
                    HoFRecord rec = plugin.getHallOfFameManager().getRecord(cat.id);
                    if (rec != null) {
                        sender.sendMessage(MessageUtil.color(
                                "&e" + cat.displayName + ": &f" + rec.getPlayerName()
                                + " &8— &a" + rec.getValue()
                                + " &7(KMC #" + rec.getEventNumber() + ")"));
                    } else {
                        sender.sendMessage(MessageUtil.color(
                                "&e" + cat.displayName + ": &8Geen record"));
                    }
                }
            }

            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(MessageUtil.color("&cGebruik: /kmchof info <category>"));
                    listCategories(sender);
                    return true;
                }
                var cat = plugin.getHallOfFameManager().getCategory(args[1].toLowerCase());
                if (cat == null) {
                    sender.sendMessage(MessageUtil.color("&cOnbekende categorie: " + args[1]));
                    listCategories(sender);
                    return true;
                }
                HoFRecord rec = plugin.getHallOfFameManager().getRecord(cat.id);
                sender.sendMessage(MessageUtil.color("&6═══ " + cat.displayName + " ═══"));
                sender.sendMessage(MessageUtil.color("&7Categorie ID: &e" + cat.id));
                sender.sendMessage(MessageUtil.color("&7Strategie: &e" + cat.strategy));
                if (rec != null) {
                    sender.sendMessage(MessageUtil.color("&7Houder: &f" + rec.getPlayerName()));
                    sender.sendMessage(MessageUtil.color("&7Waarde: &a" + rec.getValue()));
                    sender.sendMessage(MessageUtil.color("&7Event: &eKMC #" + rec.getEventNumber()));
                    sender.sendMessage(MessageUtil.color("&7Datum: &e" + DATE_FMT.format(new Date(rec.getTimestamp()))));
                } else {
                    sender.sendMessage(MessageUtil.color("&7Geen record gezet."));
                }
            }

            case "clear" -> {
                if (args.length < 2) {
                    sender.sendMessage(MessageUtil.color("&cGebruik: /kmchof clear <category|full>"));
                    listCategories(sender);
                    return true;
                }
                String target = args[1].toLowerCase();
                if (target.equals("full") || target.equals("all")) {
                    if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                        sender.sendMessage(MessageUtil.color(
                                "&c⚠ Dit wist ALLE Hall of Fame records!"));
                        sender.sendMessage(MessageUtil.color(
                                "&7Typ &e/kmchof clear full confirm &7om door te gaan."));
                        return true;
                    }
                    plugin.getHallOfFameManager().clearAll();
                    sender.sendMessage(MessageUtil.color("&a✔ Alle HoF records gewist."));
                } else {
                    var cat = plugin.getHallOfFameManager().getCategory(target);
                    if (cat == null) {
                        sender.sendMessage(MessageUtil.color("&cOnbekende categorie: " + target));
                        listCategories(sender);
                        return true;
                    }
                    plugin.getHallOfFameManager().clearCategory(target);
                    sender.sendMessage(MessageUtil.color(
                            "&a✔ Record voor '" + cat.displayName + "' gewist."));
                }
            }

            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.color("&cGebruik: /kmchof <list|info|clear>"));
    }

    private void listCategories(CommandSender s) {
        String cats = plugin.getHallOfFameManager().getCategories().stream()
                .map(c -> c.id).collect(Collectors.joining(", "));
        s.sendMessage(MessageUtil.color("&7Beschikbare categorieën: &e" + cats + ", full"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("list", "info", "clear").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("clear"))) {
            List<String> opts = new ArrayList<>();
            opts.addAll(plugin.getHallOfFameManager().getCategories().stream()
                    .map(cat -> cat.id).toList());
            if (args[0].equalsIgnoreCase("clear")) opts.add("full");
            return opts.stream().filter(o -> o.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
