package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.AutomationManager;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;

import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * /kmcauto &lt;start|stop|pause|resume|status|endgame&gt;
 *
 * <p>HARDENED VERSION:
 * <ul>
 *   <li>All command logic wrapped in try/catch — exceptions are logged
 *       with full stack trace + reported to the sender, instead of
 *       Paper's truncated "Unhandled exception" message</li>
 *   <li>{@code start} eagerly warms up state that previously lazy-init'd
 *       on first use (which caused the "errors first time, works second
 *       time" pattern)</li>
 *   <li>Friendlier error messages so admins know what went wrong</li>
 * </ul>
 */
public class AutomationCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public AutomationCommand(KMCCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            return handle(sender, args);
        } catch (Exception e) {
            // Surface the real cause so we can debug — Paper otherwise
            // truncates the stack to just "Unhandled exception".
            plugin.getLogger().log(Level.SEVERE,
                    "/kmcauto " + (args.length > 0 ? args[0] : "") + " failed", e);
            sender.sendMessage(MessageUtil.color(
                    "&c[KMC] Fout bij /kmcauto: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " — " + e.getMessage() : "")));
            sender.sendMessage(MessageUtil.color(
                    "&7Zie de console voor de volledige stack trace."));
            return true;
        }
    }

    private boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kmc.automation.admin")) {
            sender.sendMessage(MessageUtil.get("no-permission"));
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        AutomationManager am = plugin.getAutomationManager();
        if (am == null) {
            sender.sendMessage(MessageUtil.color("&c[KMC] AutomationManager niet beschikbaar."));
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "start" -> {
                // Eager warmup — touches every manager that AutomationManager
                // might lazy-resolve, so the first call doesn't NPE.
                warmup(sender);

                if (!plugin.getTournamentManager().isActive()) {
                    plugin.getTournamentManager().start();
                }
                if (am.isRunning()) {
                    sender.sendMessage(MessageUtil.color("&c[KMC] Automatisering draait al."));
                    return true;
                }
                am.start();
                sender.sendMessage(MessageUtil.color("&a[KMC] Automatisering gestart!"));
            }

            case "stop" -> {
                am.stop();
                sender.sendMessage(MessageUtil.color("&c[KMC] Automatisering gestopt."));
            }

            case "pause" -> {
                am.pause();
                sender.sendMessage(MessageUtil.color("&e[KMC] Automatisering gepauzeerd."));
            }

            case "resume" -> {
                am.resume();
                sender.sendMessage(MessageUtil.color("&a[KMC] Automatisering hervat."));
            }

            case "status" -> {
                AutomationManager.State state = am.getState();
                sender.sendMessage(MessageUtil.color("&6[KMC] Status: &e" + state.name()));
                if (am.isRunning()) {
                    sender.sendMessage(MessageUtil.color("&7Countdown: &e"
                            + am.getCountdownSeconds() + "s"));
                    sender.sendMessage(MessageUtil.color("&7Ronde: &e"
                            + plugin.getTournamentManager().getCurrentRound()
                            + " &7/ &e" + plugin.getTournamentManager().getTotalRounds()));
                    sender.sendMessage(MessageUtil.color("&7Multiplier: &e×"
                            + plugin.getTournamentManager().getMultiplier()));
                    var game = plugin.getGameManager().getActiveGame();
                    sender.sendMessage(MessageUtil.color("&7Actieve game: &e"
                            + (game != null ? game.getDisplayName() : "Geen")));
                }
            }

            case "endgame" -> {
                String winner = args.length >= 2 ? args[1] : "?";
                if (plugin.getGameManager().stopGame(winner)) {
                    am.onGameEnd(winner);
                    sender.sendMessage(MessageUtil.color("&a[KMC] Game beëindigd. Tussenpauze gestart."));
                } else {
                    sender.sendMessage(MessageUtil.color("&c[KMC] Geen actieve game om te beëindigen."));
                }
            }

            default -> usage(sender);
        }
        return true;
    }

    /**
     * Touches every manager AutomationManager might use, so any lazy
     * initialization happens here in a controlled context (where we
     * can log + report) rather than blowing up halfway through start().
     */
    private void warmup(CommandSender sender) {
        try {
            // Touch core managers
            plugin.getTeamManager();
            plugin.getPlayerDataManager();
            plugin.getGameManager().getAllGames();   // forces config-loaded games
            plugin.getTournamentManager();
            plugin.getPointsManager();
            plugin.getScoreboardManager();
            plugin.getTabListManager();
            // Touch megapatch managers (defensive — these might not exist
            // on older builds; ignore if methods missing)
            try { plugin.getBossBarLeaderboard(); } catch (Throwable ignored) {}
            try { plugin.getHealthMonitor();      } catch (Throwable ignored) {}
            try { plugin.getDiscordHook();        } catch (Throwable ignored) {}
            // API itself
            plugin.getApi();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Warmup hit a problem (continuing anyway)", t);
            sender.sendMessage(MessageUtil.color(
                    "&e[KMC] Waarschuwing tijdens warmup: " + t.getClass().getSimpleName()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","stop","pause","resume","status","endgame")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.color("&cGebruik: /kmcauto <start|stop|pause|resume|status|endgame> [winner]"));
    }
}
