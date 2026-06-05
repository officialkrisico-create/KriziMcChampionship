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
                // ── Tournament start protection (EVS) ─────────────────────────
                // Block on critical validation issues unless 'force' is given.
                boolean force = args.length >= 2 && args[1].equalsIgnoreCase("force");
                java.util.List<String> critical = ValidateCommand.criticalIssues(plugin);
                if (!critical.isEmpty() && !force) {
                    sender.sendMessage(MessageUtil.color("&c&l⚠ Tournament Validation Failed"));
                    sender.sendMessage(MessageUtil.color("&c" + critical.size() + " kritieke problemen gevonden:"));
                    critical.stream().limit(8).forEach(i ->
                            sender.sendMessage(MessageUtil.color("&7 - &f" + i)));
                    if (critical.size() > 8)
                        sender.sendMessage(MessageUtil.color("&7   ...en nog " + (critical.size() - 8) + " meer."));
                    sender.sendMessage(MessageUtil.color("&7Open &e/kmcvalidate &7voor details, of"));
                    sender.sendMessage(MessageUtil.color("&7gebruik &e/kmcauto start force &7om toch te starten."));
                    return true;
                }
                if (force && !critical.isEmpty())
                    sender.sendMessage(MessageUtil.color("&e[KMC] Geforceerde start ondanks " + critical.size() + " problemen."));

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

                // Reset HealthMonitor's hang-check baseline so it doesn't
                // think the tournament is hung the moment it starts
                try {
                    if (plugin.getHealthMonitor() != null) {
                        plugin.getHealthMonitor().notifyAutomationStarted();
                    }
                } catch (Throwable ignored) { /* older builds don't have the method */ }

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

            case "schedule" -> handleSchedule(sender, am, args);

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

    /**
     * /kmcauto schedule &lt;HH:mm | in &lt;minutes&gt; | cancel | status&gt;
     *
     * <p>Schedules the whole ceremony + automation flow to auto-start at a
     * given clock time (server local time), a relative delay, or shows/cancels
     * the current schedule.
     */
    private void handleSchedule(CommandSender sender, AutomationManager am, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.color("&cGebruik: /kmcauto schedule <HH:mm | in <minuten> | cancel | status>"));
            return;
        }
        String sub = args[1].toLowerCase();

        switch (sub) {
            case "cancel" -> {
                am.cancelScheduledStart();
                sender.sendMessage(MessageUtil.color("&a[KMC] Geplande start geannuleerd."));
            }
            case "status" -> {
                if (!am.hasScheduledStart()) { sender.sendMessage(MessageUtil.color("&7Geen start gepland.")); return; }
                long secs = am.scheduledStartInMs() / 1000;
                sender.sendMessage(MessageUtil.color("&6[KMC] Toernooi start over &e"
                        + (secs / 60) + "m " + (secs % 60) + "s&6."));
            }
            case "in" -> {
                if (args.length < 3) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcauto schedule in <minuten>")); return; }
                double minutes;
                try { minutes = Double.parseDouble(args[2]); }
                catch (NumberFormatException e) { sender.sendMessage(MessageUtil.get("invalid-number")); return; }
                if (minutes <= 0) { sender.sendMessage(MessageUtil.color("&cMoet groter dan 0 zijn.")); return; }
                long ticks = (long) (minutes * 60 * 20);
                am.scheduleStart(ticks);
                sender.sendMessage(MessageUtil.color("&a[KMC] Toernooi gepland over &e" + minutes + " minuten&a."));
            }
            default -> {
                // Parse as HH:mm clock time (server local), next occurrence.
                java.time.LocalTime target;
                try {
                    String[] hm = sub.split(":");
                    target = java.time.LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
                } catch (Exception e) {
                    sender.sendMessage(MessageUtil.color("&cOngeldige tijd. Gebruik HH:mm, bv. 20:00."));
                    return;
                }
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                java.time.LocalDateTime when = now.toLocalDate().atTime(target);
                if (!when.isAfter(now)) when = when.plusDays(1); // already passed → tomorrow
                long delayMs = java.time.Duration.between(now, when).toMillis();
                long ticks = delayMs / 50L;
                am.scheduleStart(ticks);
                sender.sendMessage(MessageUtil.color("&a[KMC] Toernooi gepland om &e"
                        + when.toLocalTime() + "&a (" + (delayMs / 60000) + " min vanaf nu)."));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","stop","pause","resume","status","endgame","schedule")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && args[0].equalsIgnoreCase("schedule"))
            return List.of("in","cancel","status","20:00").stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.color("&cGebruik: /kmcauto <start|stop|pause|resume|status|endgame|schedule> [winner]"));
    }
}