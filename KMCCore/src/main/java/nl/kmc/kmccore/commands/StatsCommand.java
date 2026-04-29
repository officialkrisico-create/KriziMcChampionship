package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * /kmcstats [player] — opens the multi-page stats GUI.
 *
 * <p>Replaces the previous text-only output with an inventory GUI
 * showing overview, per-game breakdown, achievements, and tournament
 * history across 4 tabbed pages.
 */
public class StatsCommand implements CommandExecutor {

    private final KMCCore plugin;

    public StatsCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Stats GUI is alleen beschikbaar voor spelers in-game.");
            return true;
        }

        UUID targetUuid;
        if (args.length == 0) {
            targetUuid = viewer.getUniqueId();
        } else {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (target == null || target.getUniqueId() == null) {
                viewer.sendMessage(ChatColor.RED + "Speler niet gevonden: " + args[0]);
                return true;
            }
            targetUuid = target.getUniqueId();
        }

        if (plugin.getStatsGUI() == null) {
            viewer.sendMessage(ChatColor.RED + "Stats GUI niet beschikbaar.");
            return true;
        }
        plugin.getStatsGUI().open(viewer, targetUuid, 1);
        return true;
    }
}
