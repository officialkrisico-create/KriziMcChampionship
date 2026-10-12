package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.OpeningCeremonyGui;
import nl.kmc.kmccore.presentation.CeremonyFireworks.Moment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /kmcopeningsceremony — set up the opening ceremony's fireworks.
 *
 * <pre>
 * /kmcopeningsceremony                 — open the GUI
 * /kmcopeningsceremony list            — show every moment's settings
 * /kmcopeningsceremony test [moment]   — fire a moment's fireworks now
 * /kmcopeningsceremony addspot <moment> — add your position as a firework spot
 * /kmcopeningsceremony clear <moment>  — remove a moment's spots
 * </pre>
 */
public final class OpeningCeremonyCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public OpeningCeremonyCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin")) {
            sender.sendMessage("§cGeen toestemming.");
            return true;
        }
        var fw = plugin.getCeremonyFireworks();
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (sender instanceof Player p) new OpeningCeremonyGui(plugin).open(p);
            else OpeningCeremonyGui.describe(plugin, sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> OpeningCeremonyGui.describe(plugin, sender);
            case "podium" -> {
                String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
                switch (sub) {
                    case "add" -> {
                        if (!(sender instanceof Player p)) { sender.sendMessage("§cAlleen spelers."); return true; }
                        fw.addPodiumSpot(p.getLocation());
                        sender.sendMessage("§a[Ceremonie] Podium-spot #" + fw.getPodium().size() + " gezet.");
                    }
                    case "clear" -> { fw.clearPodium(); sender.sendMessage("§a[Ceremonie] Podium gewist."); }
                    default -> sender.sendMessage("§7Podium-spots: §f" + fw.getPodium().size()
                            + " §8(/" + label + " podium add|clear)");
                }
            }
            case "test" -> {
                if (args.length >= 2) {
                    Moment m = Moment.byId(args[1]);
                    if (m == null) { sender.sendMessage("§cOnbekend moment. Kies: " + momentNames()); return true; }
                    fw.play(m, true);
                    sender.sendMessage("§a[Ceremonie] Test: §e" + m.label);
                } else {
                    for (Moment m : Moment.values()) fw.play(m, true);
                    sender.sendMessage("§a[Ceremonie] Test: alle momenten tegelijk.");
                }
            }
            case "addspot" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cAlleen spelers."); return true; }
                Moment m = args.length >= 2 ? Moment.byId(args[1]) : null;
                if (m == null) { sender.sendMessage("§cGebruik: /" + label + " addspot <" + momentNames() + ">"); return true; }
                fw.addSpot(m, p.getLocation());
                sender.sendMessage("§a[Ceremonie] Spot #" + fw.get(m).spots.size() + " toegevoegd voor §e" + m.label + "§a.");
            }
            case "clear" -> {
                Moment m = args.length >= 2 ? Moment.byId(args[1]) : null;
                if (m == null) { sender.sendMessage("§cGebruik: /" + label + " clear <" + momentNames() + ">"); return true; }
                fw.clearSpots(m);
                sender.sendMessage("§a[Ceremonie] Spots van §e" + m.label + "§a gewist.");
            }
            default -> sender.sendMessage("§cGebruik: /" + label + " [gui|list|test [moment]|addspot <moment>|clear <moment>]");
        }
        return true;
    }

    private static String momentNames() {
        List<String> names = new ArrayList<>();
        for (Moment m : Moment.values()) names.add(m.id);
        return String.join(", ", names);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return Arrays.asList("gui", "list", "test", "addspot", "clear", "podium");
        if (args.length == 2 && Arrays.asList("test", "addspot", "clear").contains(args[0].toLowerCase())) {
            List<String> out = new ArrayList<>();
            for (Moment m : Moment.values()) out.add(m.id);
            return out;
        }
        return List.of();
    }
}
