package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.checklist.Checklist;
import nl.kmc.kmccore.gui.ChecklistGui;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /kmcchecklist}: the menu with everything still to do for a tournament, to tick off. Also
 * {@code list}, {@code add <tekst>}, {@code remove <nr>}, {@code toggle <nr>}, {@code reset}, {@code defaults}.
 */
public final class ChecklistCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public ChecklistCommand(KMCCore plugin) { this.plugin = plugin; }

    private static void msg(CommandSender s, String text) { s.sendMessage(MessageUtil.color(text)); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin") && !sender.hasPermission("kmc.tournament.admin")) {
            msg(sender, "&cGeen toestemming.");
            return true;
        }
        Checklist list = plugin.getChecklist();
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "" -> {
                if (sender instanceof Player p) new ChecklistGui(plugin, 0).open(p);
                else print(sender, list);
            }
            case "list" -> print(sender, list);
            case "add" -> {
                if (args.length < 2) { msg(sender, "&cGebruik: /kmcchecklist add <tekst>"); return true; }
                list.add(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                msg(sender, "&a[KMC] Toegevoegd aan de checklist (" + list.size() + " items).");
            }
            case "remove" -> {
                Integer n = number(args);
                String removed = n == null ? null : list.remove(n - 1);
                msg(sender, removed != null ? "&a[KMC] Verwijderd: &7" + removed : "&cGebruik: /kmcchecklist remove <nummer> (zie /kmcchecklist list)");
            }
            case "toggle" -> {
                Integer n = number(args);
                Boolean now = n == null ? null : list.toggle(n - 1);
                msg(sender, now == null ? "&cGebruik: /kmcchecklist toggle <nummer>"
                        : (now ? "&a[KMC] Afgevinkt." : "&e[KMC] Weer open gezet."));
            }
            case "reset" -> {
                list.reset();
                msg(sender, "&a[KMC] Alle vinkjes zijn weg: een nieuwe checklist voor een nieuw evenement.");
            }
            case "defaults" -> {
                list.restoreDefaults();
                msg(sender, "&a[KMC] De standaard-items staan er weer in.");
            }
            default -> msg(sender, "&7Gebruik: &e/kmcchecklist &7[list | add <tekst> | remove <nr> | toggle <nr> | reset | defaults]");
        }
        return true;
    }

    private static Integer number(String[] args) {
        if (args.length < 2) return null;
        try { return Integer.parseInt(args[1]); } catch (NumberFormatException e) { return null; }
    }

    private void print(CommandSender sender, Checklist list) {
        msg(sender, "&6&lChecklist &7— &f" + list.doneCount() + "/" + list.size() + " klaar");
        int n = 1;
        for (Checklist.Item i : list.items()) {
            msg(sender, " &8" + n++ + ". " + (i.done() ? "&a✔ &7" : "&c✘ &f") + i.text());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        return List.of("list", "add", "remove", "toggle", "reset", "defaults").stream()
                .filter(o -> o.startsWith(a[0].toLowerCase())).toList();
    }
}
