package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Executors that open the various KMC GUIs. */
public final class GuiCommands {

    private GuiCommands() {}

    private static boolean requirePlayer(CommandSender s) {
        if (s instanceof Player) return true;
        s.sendMessage("§cAlleen spelers kunnen GUIs openen.");
        return false;
    }

    /** /kmcprofile [player] */
    public record ProfileCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            Player p = (Player) s;
            if (a.length >= 1 && s.hasPermission("kmc.stats")) {
                var target = Bukkit.getOfflinePlayer(a[0]);
                new ProfileGui(plugin, target.getUniqueId(),
                        target.getName() != null ? target.getName() : a[0]).open(p);
            } else {
                new ProfileGui(plugin, p.getUniqueId(), p.getName()).open(p);
            }
            return true;
        }
    }

    /** /kmcstandings */
    public record StandingsCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            Player p = (Player) s;
            new StandingsGui(plugin, p.getUniqueId()).open(p);
            return true;
        }
    }

    /** /kmchelp */
    public record HelpCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new HelpGui(plugin).open((Player) s);
            return true;
        }
    }

    /** /kmchof — Hall of Fame GUI */
    public record HofCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new HallOfFameGui(plugin).open((Player) s);
            return true;
        }
    }

    /** /kmcsettings */
    public record SettingsCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("kmc.admin") && !s.hasPermission("kmc.tournament.admin")) {
                s.sendMessage("§cGeen toestemming.");
                return true;
            }
            if (!requirePlayer(s)) return true;
            new SettingsGui(plugin).open((Player) s);
            return true;
        }
    }

    /** /kmclanguage — open the personal language picker. */
    public record LanguageCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new nl.kmc.kmccore.gui.LanguageGui(plugin, (Player) s).open((Player) s);
            return true;
        }
    }

    /** /kmcmedals — open your medal cabinet + the Most Decorated leaderboard. */
    public record MedalsCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new nl.kmc.kmccore.gui.MedalsGui(plugin, ((Player) s).getUniqueId()).open((Player) s);
            return true;
        }
    }

    /** /kmcmvp — game MVPs (this tournament + all-time). */
    public record MvpCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new nl.kmc.kmccore.gui.MvpGui(plugin, ((Player) s).getUniqueId()).open((Player) s);
            return true;
        }
    }

    /** /kmcmomentum — biggest rise/fall + hot streaks. */
    public record MomentumCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new nl.kmc.kmccore.gui.MomentumGui(plugin).open((Player) s);
            return true;
        }
    }

    /** /kmcpowerrank — team power rankings (ELO). */
    public record PowerRankCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!requirePlayer(s)) return true;
            new nl.kmc.kmccore.gui.PowerRankGui(plugin).open((Player) s);
            return true;
        }
    }

    /** /kmcwinner [test|skip] — preview or skip the Winner Ceremony 2.0 (admin). */
    public record WinnerCeremonyCommand(KMCCore plugin) implements CommandExecutor {
        @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (!s.hasPermission("kmc.admin") && !s.hasPermission("kmc.tournament.admin")) {
                s.sendMessage("§cGeen toestemming."); return true;
            }
            if (a.length >= 1 && a[0].equalsIgnoreCase("skip")) {
                nl.kmc.kmccore.tournament.WinnerCeremony.skip();
                s.sendMessage("§a[KMC] Winnaarsceremonie overgeslagen.");
            } else {
                s.sendMessage("§a[KMC] Winnaarsceremonie (preview) gestart...");
                nl.kmc.kmccore.tournament.WinnerCeremony.run(plugin, null);
            }
            return true;
        }
    }
}
