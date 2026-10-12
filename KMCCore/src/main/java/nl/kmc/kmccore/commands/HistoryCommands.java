package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.SeasonGui;
import nl.kmc.kmccore.gui.TrackMasteryGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Player-facing history views: {@code /kmcseason} (season / all-time) and {@code /kmctrack} (track records). */
public final class HistoryCommands {

    private HistoryCommands() {}

    /** /kmcseason [season|all] */
    public record SeasonCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
            boolean season = a.length > 0 && a[0].equalsIgnoreCase("season");
            new SeasonGui(plugin, season).open(p);
            return true;
        }
    }

    /** /gg — the sportsmanship button (also the clickable chat message after a game). */
    public record GgCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
            plugin.getGgManager().send(p);
            return true;
        }
    }

    /** /kmctrack */
    public record TrackCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
            new TrackMasteryGui(plugin, p).open(p);
            return true;
        }
    }
}
