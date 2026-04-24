package nl.kmc.luckyblock.commands;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import nl.kmc.luckyblock.managers.GameStateManager;
import org.bukkit.ChatColor;
import org.bukkit.command.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /luckyblock <start|stop|status>
 *
 * <p>Arena setup is now handled by KMCCore via /kmcarena commands.
 * Setup workflow:
 *   1. Drop your schematic in plugins/KMCCore/schematics/lucky_block.schem
 *   2. Add to KMCCore config.yml: games.list.lucky_block.schematic: lucky_block.schem
 *   3. /kmcarena setorigin lucky_block          (stand at paste location)
 *   4. /kmcarena addsolospawn lucky_block       (stand at each spawn, repeat)
 *   5. /kmcarena setlobby                       (stand in lobby)
 *   6. Place yellow_concrete blocks in your schematic where you want lucky blocks
 *   7. /luckyblock start
 */
public class LuckyBlockCommand implements CommandExecutor, TabCompleter {

    private final LuckyBlockPlugin plugin;

    public LuckyBlockCommand(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.luckyblock.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "start" -> {
                String error = plugin.getGameState().startCountdown();
                if (error != null) {
                    sender.sendMessage(ChatColor.RED + error);
                } else {
                    sender.sendMessage(ChatColor.GREEN + "[Lucky Block] Countdown gestart!");
                }
            }

            case "stop" -> {
                plugin.getGameState().forceStop();
                sender.sendMessage(ChatColor.RED + "[Lucky Block] Game gestopt.");
            }

            case "status" -> {
                GameStateManager.State s = plugin.getGameState().getState();
                sender.sendMessage(ChatColor.GOLD + "=== Lucky Block ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + s);
                if (plugin.getGameState().isActive()) {
                    sender.sendMessage(ChatColor.YELLOW + "Spelers over: " + plugin.getGameState().getAliveCount());
                    sender.sendMessage(ChatColor.YELLOW + "Actieve lucky blocks: " + plugin.getTracker().getActiveCount());
                }
                sender.sendMessage(ChatColor.GRAY + "Setup via /kmcarena commands in KMCCore.");
            }

            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("start","stop","status").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.RED + "Gebruik: /luckyblock <start|stop|status>");
        s.sendMessage(ChatColor.GRAY + "Arena setup: /kmcarena commands in KMCCore.");
    }
}
