package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/** /kmcgame <start|stop|skip|forceskip|next|vote|list|set> [game] */
public class GameCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;
    public GameCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.game.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (args.length < 2) { usage(sender); return true; }
                String gameId = args[1].toLowerCase();
                if (!plugin.getGameManager().startGame(gameId))
                    sender.sendMessage(MessageUtil.get("game.not-found").replace("{game}", gameId));
            }
            case "stop" -> {
                String winner = args.length >= 2 ? args[1] : "?";
                if (!plugin.getGameManager().stopGame(winner))
                    sender.sendMessage(MessageUtil.get("game.no-active"));
            }
            case "skip" -> {
                if (!plugin.getGameManager().skipGame())
                    sender.sendMessage(MessageUtil.get("game.no-active"));
                else
                    sender.sendMessage(MessageUtil.get("game.skipped").replace("{game}", "huidige game"));
            }
            case "forceskip" -> {
                if (!plugin.getGameManager().forceSkipCurrentGame())
                    sender.sendMessage(MessageUtil.get("game.no-active"));
                else
                    sender.sendMessage(org.bukkit.ChatColor.YELLOW
                            + "[KMC] Game geforceerd overgeslagen. Volgende stemronde start direct.");
            }
            case "next" -> {
                KMCGame next = plugin.getGameManager().randomNextGame();
                if (next == null) sender.sendMessage("Geen games beschikbaar.");
                else sender.sendMessage(MessageUtil.get("game.next").replace("{game}", next.getDisplayName()));
            }
            case "vote" -> plugin.getGameManager().startVote();
            case "list" -> {
                sender.sendMessage(MessageUtil.get("game.list-header"));
                for (KMCGame g : plugin.getGameManager().getAllGames())
                    sender.sendMessage(MessageUtil.get("game.list-entry").replace("{game}", g.getDisplayName()));
            }
            case "set" -> {
                if (args.length < 2) { usage(sender); return true; }
                if (!plugin.getGameManager().forceNextGame(args[1].toLowerCase()))
                    sender.sendMessage(MessageUtil.get("game.not-found").replace("{game}", args[1]));
                else
                    sender.sendMessage(MessageUtil.get("game.force-set").replace("{game}", args[1]));
            }
            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return List.of("start","stop","skip","forceskip","next","vote","list","set").stream().filter(o -> o.startsWith(args[0])).collect(Collectors.toList());
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("set")))
            return plugin.getGameManager().getAllGames().stream().map(KMCGame::getId).filter(id -> id.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) { s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}", "/kmcgame <start|stop|skip|forceskip|next|vote|list|set> [game]")); }
}
