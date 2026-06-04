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
            case "setorigin" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cOnly players can use setorigin."); return true; }
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmcgame setorigin <gameId>"); return true; }
                String gameId = args[1].toLowerCase();
                plugin.getSchematicManager().setOriginForGame(gameId, p.getLocation());
                sender.sendMessage("§a[KMC] Arena origin for §e" + gameId + "§a set to your location.");
                sender.sendMessage("§7Schematic file expected: §e" + gameId + "mapschematic.schem§7 in plugins/KMCCore/schematics/");
            }
            case "resetarena" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kmcgame resetarena <gameId>"); return true; }
                String gameId = args[1].toLowerCase();
                String schematic = gameId + "mapschematic.schem";
                org.bukkit.Location origin = plugin.getSchematicManager().getOriginForGame(gameId);
                if (origin == null) { sender.sendMessage("§cNo origin set for §e" + gameId + "§c. Use /kmcgame setorigin first."); return true; }
                sender.sendMessage("§e[KMC] Pasting §f" + schematic + "§e...");
                boolean ok = plugin.getSchematicManager().resetArena(schematic, origin);
                sender.sendMessage(ok ? "§a[KMC] Arena reset complete." : "§c[KMC] Paste failed — check console.");
            }
            case "repetitions" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /kmcgame repetitions <gameId> <count>"); return true; }
                String gameId = args[1].toLowerCase();
                int count;
                try { count = Integer.parseInt(args[2]); } catch (NumberFormatException e) { sender.sendMessage(MessageUtil.get("invalid-number")); return true; }
                if (count < 1 || count > 10) { sender.sendMessage("§cRepetitions must be between 1 and 10."); return true; }
                if (plugin.getConfig().get("games.list." + gameId) == null) { sender.sendMessage(MessageUtil.get("game.not-found").replace("{game}", gameId)); return true; }
                plugin.getConfig().set("games.list." + gameId + ".repetitions", count);
                plugin.saveConfig();
                sender.sendMessage("§a[KMC] §e" + gameId + "§a will now play §e" + count + "x§a per round.");
            }
            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) return List.of("start","stop","skip","forceskip","next","vote","list","set","setorigin","resetarena","repetitions").stream().filter(o -> o.startsWith(args[0])).collect(Collectors.toList());
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("set")
                || args[0].equalsIgnoreCase("setorigin") || args[0].equalsIgnoreCase("resetarena")
                || args[0].equalsIgnoreCase("repetitions")))
            return plugin.getGameManager().getAllGames().stream().map(KMCGame::getId).filter(id -> id.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("repetitions"))
            return List.of("1","2","3","4","5");
        return List.of();
    }

    private void usage(CommandSender s) { s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}", "/kmcgame <start|stop|skip|forceskip|next|vote|list|set|setorigin|resetarena> [game]")); }
}
