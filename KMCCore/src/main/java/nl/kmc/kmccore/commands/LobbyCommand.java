package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /kmclobby <set|tp|tpall>
 *
 * <p>Set the global lobby with {@code set}, teleport yourself with {@code tp},
 * or send everyone to the lobby with {@code tpall}. All lobby teleports
 * put players in ADVENTURE mode so they can't break blocks.
 */
public class LobbyCommand implements CommandExecutor {

    private final KMCCore plugin;

    public LobbyCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            // /kmclobby alone = teleport yourself
            if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
            teleportToLobby(p);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set" -> {
                if (!sender.hasPermission("kmc.lobby.admin")) {
                    sender.sendMessage(MessageUtil.get("no-permission"));
                    return true;
                }
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().setLobby(p.getLocation());
                sender.sendMessage(MessageUtil.color("&a✔ Lobby ingesteld op jouw locatie."));
            }
            case "tp" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                teleportToLobby(p);
            }
            case "tpall" -> {
                if (!sender.hasPermission("kmc.lobby.admin")) {
                    sender.sendMessage(MessageUtil.get("no-permission"));
                    return true;
                }
                plugin.getArenaManager().teleportAllToLobby();
                sender.sendMessage(MessageUtil.color("&a✔ Iedereen is naar de lobby getp't."));
            }
            default -> sender.sendMessage(MessageUtil.color("&cGebruik: /kmclobby <set|tp|tpall>"));
        }
        return true;
    }

    private void teleportToLobby(Player player) {
        if (plugin.getArenaManager().getLobby() == null) {
            player.sendMessage(MessageUtil.color("&cLobby is niet ingesteld. Een admin moet /kmclobby set gebruiken."));
            return;
        }
        player.teleport(plugin.getArenaManager().getLobby());
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20);
        player.setFoodLevel(20);
        player.getInventory().clear();
        player.sendMessage(MessageUtil.color("&aJe bent naar de lobby getp't."));
    }
}
