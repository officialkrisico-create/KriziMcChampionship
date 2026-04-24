package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.AutomationManager;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /kmcauto <start|stop|pause|resume|status|endgame>
 *
 * <p>Admin controls for the automation engine:
 * <ul>
 *   <li>{@code start}   – begins automated tournament loop</li>
 *   <li>{@code stop}    – halts automation entirely</li>
 *   <li>{@code pause}   – freezes countdown, keeps state</li>
 *   <li>{@code resume}  – unfreezes from where it paused</li>
 *   <li>{@code status}  – shows current automation state</li>
 *   <li>{@code endgame} – signals current game is done (triggers intermission)</li>
 * </ul>
 */
public class AutomationCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public AutomationCommand(KMCCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.automation.admin")) {
            sender.sendMessage(MessageUtil.get("no-permission"));
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        AutomationManager am = plugin.getAutomationManager();

        switch (args[0].toLowerCase()) {

            case "start" -> {
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
                    sender.sendMessage(MessageUtil.color("&7Countdown: &e" + am.getCountdownSeconds() + "s"));
                    sender.sendMessage(MessageUtil.color("&7Ronde: &e" + plugin.getTournamentManager().getCurrentRound()
                            + " &7/ &e" + plugin.getTournamentManager().getTotalRounds()));
                    sender.sendMessage(MessageUtil.color("&7Multiplier: &e×" + plugin.getTournamentManager().getMultiplier()));
                    var game = plugin.getGameManager().getActiveGame();
                    sender.sendMessage(MessageUtil.color("&7Actieve game: &e" + (game != null ? game.getDisplayName() : "Geen")));
                }
            }

            case "endgame" -> {
                // Admin manually signals the current game is finished
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
