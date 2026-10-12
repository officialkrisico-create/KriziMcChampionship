package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.DirectorGui;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /pause}, {@code /resume} and {@code /kmcdirector} — the event director's quick controls. */
public final class DirectorCommands {

    private DirectorCommands() {}

    private static boolean denied(CommandSender s) {
        if (s.hasPermission("kmc.automation.admin")) return false;
        s.sendMessage(MessageUtil.get("no-permission"));
        return true;
    }

    /**
     * /pause          — pause the tournament (between games now; during a game: right after it)
     * /pause now      — emergency freeze: every player stands still (game timers keep running)
     * /pause cancel   — withdraw a queued pause
     */
    public static final class PauseCommand implements CommandExecutor, TabCompleter {
        private final KMCCore plugin;
        public PauseCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (denied(sender)) return true;
            var am = plugin.getAutomationManager();
            String sub = args.length > 0 ? args[0].toLowerCase() : "";
            switch (sub) {
                case "now" -> plugin.getDirector().setFrozen(true);
                case "cancel" -> {
                    am.cancelQueuedPause();
                    sender.sendMessage(MessageUtil.color("&7[KMC] Aangevraagde pauze ingetrokken."));
                }
                case "" -> {
                    if (!am.isRunning() && am.getState() != nl.kmc.kmccore.managers.AutomationManager.State.PAUSED) {
                        sender.sendMessage(MessageUtil.color("&c[KMC] De automatisering draait niet."));
                        return true;
                    }
                    boolean now = am.requestPause();
                    sender.sendMessage(MessageUtil.color(now
                            ? "&e[KMC] Toernooi gepauzeerd. Hervatten: &f/resume"
                            : "&e[KMC] Pauze staat klaar: het toernooi pauzeert zodra deze game voorbij is. &7(intrekken: /pause cancel)"));
                }
                default -> sender.sendMessage(MessageUtil.color("&cGebruik: /pause [now|cancel]"));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
            return args.length == 1 ? List.of("now", "cancel").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase())).toList() : List.of();
        }
    }

    /** /resume — lifts the emergency freeze and/or resumes a paused tournament. */
    public static final class ResumeCommand implements CommandExecutor {
        private final KMCCore plugin;
        public ResumeCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (denied(sender)) return true;
            var dir = plugin.getDirector();
            boolean did = false;
            if (dir.isFrozen()) { dir.setFrozen(false); did = true; }
            var am = plugin.getAutomationManager();
            if (am.getState() == nl.kmc.kmccore.managers.AutomationManager.State.PAUSED) { am.resume(); did = true; }
            if (am.isPauseQueued()) { am.cancelQueuedPause(); did = true; }
            if (!did) sender.sendMessage(MessageUtil.color("&7[KMC] Er is niets gepauzeerd."));
            return true;
        }
    }

    /** /kmcdirector — opens the in-game director panel. */
    public static final class DirectorCommand implements CommandExecutor {
        private final KMCCore plugin;
        public DirectorCommand(KMCCore plugin) { this.plugin = plugin; }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (denied(sender)) return true;
            if (!(sender instanceof Player p)) {
                sender.sendMessage(MessageUtil.color("&cAlleen voor spelers."));
                return true;
            }
            new DirectorGui(plugin).open(p);
            return true;
        }
    }
}
