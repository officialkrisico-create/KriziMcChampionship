package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.hof.HoFNpcManager;
import org.bukkit.ChatColor;
import org.bukkit.command.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /kmchof — admin commands for the Hall of Fame display.
 *
 * <ul>
 *   <li>/kmchof setnpc &lt;slot&gt; &lt;fancyNpcId&gt; — bind an NPC to a slot</li>
 *   <li>/kmchof refresh — manually refresh the NPCs (skin + name updates)</li>
 *   <li>/kmchof list — show current bindings</li>
 *   <li>/kmchof slots — list available slot names</li>
 * </ul>
 */

public class HofCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public HofCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.hof.admin") && !sender.hasPermission("kmc.tournament.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "setnpc" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Gebruik: /kmchof setnpc <slot> <fancyNpcId>");
                    return true;
                }
                HoFNpcManager.Slot slot;
                try { slot = HoFNpcManager.Slot.valueOf(args[1].toUpperCase()); }
                catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Onbekende slot: " + args[1]
                            + ". Gebruik: TOP_PLAYER | MVP | MOST_WINS");
                    return true;
                }
                String npcId = args[2];
                plugin.getHoFNpcManager().bind(slot, npcId);
                sender.sendMessage(ChatColor.GREEN + "Slot " + slot.name()
                        + " gebonden aan NPC " + npcId);
            }
            case "refresh" -> {
                plugin.getHoFNpcManager().refresh();
                sender.sendMessage(ChatColor.GREEN + "HoF NPCs geforceerd ge-refreshed.");
            }
            case "list" -> {
                sender.sendMessage(ChatColor.GOLD + "=== HoF NPC Bindings ===");
                var bindings = plugin.getHoFNpcManager().getBindings();
                if (bindings.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Geen NPCs gebonden.");
                } else {
                    bindings.forEach((slot, id) -> sender.sendMessage(ChatColor.YELLOW
                            + "  " + slot.name() + ChatColor.GRAY + " → " + ChatColor.WHITE + id));
                }
            }
            case "slots" -> {
                sender.sendMessage(ChatColor.GOLD + "Beschikbare slots:");
                for (HoFNpcManager.Slot s : HoFNpcManager.Slot.values()) {
                    sender.sendMessage(ChatColor.YELLOW + "  " + s.name());
                }
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== /kmchof ===");
        s.sendMessage(ChatColor.YELLOW + "/kmchof setnpc <slot> <fancyNpcId>"
                + ChatColor.GRAY + " — bind een NPC aan een slot");
        s.sendMessage(ChatColor.YELLOW + "/kmchof refresh"
                + ChatColor.GRAY + " — handmatig refreshen");
        s.sendMessage(ChatColor.YELLOW + "/kmchof list"
                + ChatColor.GRAY + " — toon alle bindings");
        s.sendMessage(ChatColor.YELLOW + "/kmchof slots"
                + ChatColor.GRAY + " — toon beschikbare slot namen");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("setnpc","refresh","list","slots").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("setnpc"))
            return Arrays.stream(HoFNpcManager.Slot.values())
                    .map(HoFNpcManager.Slot::name)
                    .filter(o -> o.startsWith(args[1].toUpperCase()))
                    .collect(Collectors.toList());
        return List.of();
    }
}
