package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.CeremonyManager;
import org.bukkit.command.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /kmcceremonies — in-game editor for ceremonies.yml
 *
 * <p>Sub-commands:
 * <pre>
 *   /kmcceremonies list                             — list all phases
 *   /kmcceremonies info  <phase>                    — show current config
 *   /kmcceremonies duration <phase> <seconds>       — set duration
 *   /kmcceremonies title    <phase> <text...>       — set title
 *   /kmcceremonies subtitle <phase> <text...>       — set subtitle
 *   /kmcceremonies addmsg   <phase> <text...>       — add a message line
 *   /kmcceremonies setmsg   <phase> <index> <text>  — replace a line (0-indexed)
 *   /kmcceremonies delmsg   <phase> <index>         — delete a line
 *   /kmcceremonies clearmsg <phase>                 — delete all lines
 *   /kmcceremonies reload                           — reload ceremonies.yml from disk
 * </pre>
 */
public class CeremoniesCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public CeremoniesCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        CeremonyManager cm = plugin.getCeremonyManager();

        switch (args[0].toLowerCase()) {

            case "list" -> {
                sender.sendMessage("§6§lCeremony Phases:");
                CeremonyManager.PHASES.forEach(p -> sender.sendMessage("  §e" + p));
            }

            case "info" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmcceremonies info <phase>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase. Use /kmcceremonies list."); return true; }
                cm.getSummary(phase).forEach(sender::sendMessage);
                List<String> msgs = plugin.getCeremonyManager()
                        .getMessages(phase, java.util.Map.of());
                for (int i = 0; i < msgs.size(); i++)
                    sender.sendMessage("  §7[" + i + "] §f" + msgs.get(i));
            }

            case "duration" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /kmcceremonies duration <phase> <seconds>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                try {
                    int sec = Integer.parseInt(args[2]);
                    cm.setDuration(phase, sec);
                    sender.sendMessage("§a[KMC] §e" + phase + "§a duration set to §e" + sec + "s§a.");
                } catch (NumberFormatException e) { sender.sendMessage("§cNot a number: " + args[2]); }
            }

            case "title" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /kmcceremonies title <phase> <text...>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                cm.setTitle(phase, text);
                sender.sendMessage("§a[KMC] Title for §e" + phase + "§a set.");
            }

            case "subtitle" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /kmcceremonies subtitle <phase> <text...>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                cm.setSubtitle(phase, text);
                sender.sendMessage("§a[KMC] Subtitle for §e" + phase + "§a set.");
            }

            case "addmsg" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /kmcceremonies addmsg <phase> <text...>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                cm.addMessage(phase, text);
                sender.sendMessage("§a[KMC] Message added to §e" + phase + "§a.");
            }

            case "setmsg" -> {
                if (args.length < 4) { sender.sendMessage("§cUsage: /kmcceremonies setmsg <phase> <index> <text...>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                try {
                    int idx  = Integer.parseInt(args[2]);
                    String text = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                    cm.setMessage(phase, idx, text);
                    sender.sendMessage("§a[KMC] Message §e" + idx + "§a in §e" + phase + "§a updated.");
                } catch (NumberFormatException e) { sender.sendMessage("§cIndex must be a number."); }
            }

            case "delmsg" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /kmcceremonies delmsg <phase> <index>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                try {
                    int idx = Integer.parseInt(args[2]);
                    cm.removeMessage(phase, idx);
                    sender.sendMessage("§a[KMC] Message §e" + idx + "§a removed from §e" + phase + "§a.");
                } catch (NumberFormatException e) { sender.sendMessage("§cIndex must be a number."); }
            }

            case "clearmsg" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmcceremonies clearmsg <phase>"); return true; }
                String phase = args[1].toLowerCase();
                if (!CeremonyManager.PHASES.contains(phase)) { sender.sendMessage("§cUnknown phase."); return true; }
                cm.clearMessages(phase);
                sender.sendMessage("§a[KMC] All messages cleared for §e" + phase + "§a.");
            }

            case "reload" -> {
                cm.reload();
                sender.sendMessage("§a[KMC] ceremonies.yml reloaded.");
            }

            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("list","info","duration","title","subtitle","addmsg","setmsg","delmsg","clearmsg","reload")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        if (args.length == 2 && !args[0].equalsIgnoreCase("list") && !args[0].equalsIgnoreCase("reload"))
            return CeremonyManager.PHASES.stream()
                    .filter(p -> p.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage("§6/kmcceremonies §e<list|info|duration|title|subtitle|addmsg|setmsg|delmsg|clearmsg|reload>");
    }
}
