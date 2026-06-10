package nl.kmc.blockparty.commands;

import nl.kmc.blockparty.BlockPartyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Stream;

/** Admin control + setup for Block Party. */
public final class BlockPartyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("start", "stop", "pos1", "pos2", "spectator", "voidy", "status");

    private final BlockPartyPlugin plugin;

    public BlockPartyCommand(BlockPartyPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§d[BlockParty] §7/blockparty <" + String.join("|", SUBS) + ">"); return true; }
        var gm    = plugin.getGameManager();
        var arena = plugin.getArenaManager();

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (gm == null) { sender.sendMessage("§cV2 niet beschikbaar."); return true; }
                if (!gm.start()) gm.reportArenaIssues(sender);
                else sender.sendMessage("§a[BlockParty] Game gestart!");
            }
            case "stop" -> {
                if (gm != null && gm.isRunning()) { gm.end(); sender.sendMessage("§a[BlockParty] Game gestopt."); }
                else sender.sendMessage("§7[BlockParty] Geen actieve game.");
            }
            case "pos1" -> { requirePlayer(sender, p -> { arena.setCorner1(p.getLocation()); p.sendMessage("§a[BlockParty] Vloer-hoek 1 gezet."); }); }
            case "pos2" -> { requirePlayer(sender, p -> { arena.setCorner2(p.getLocation()); p.sendMessage("§a[BlockParty] Vloer-hoek 2 gezet."); }); }
            case "spectator" -> { requirePlayer(sender, p -> { arena.setSpectator(p.getLocation()); p.sendMessage("§a[BlockParty] Spectator-spawn gezet."); }); }
            case "voidy" -> {
                requirePlayer(sender, p -> { arena.setVoidY(p.getLocation().getBlockY()); p.sendMessage("§a[BlockParty] Void-Y = " + p.getLocation().getBlockY()); });
            }
            case "status" -> {
                sender.sendMessage("§d§lBlock Party — Status");
                sender.sendMessage("§7Arena: " + (arena.isReady() ? "§aklaar ✓" : "§cniet klaar ✗"));
                sender.sendMessage("§7Vloer-oppervlak: §f" + arena.area() + " blokken");
                if (!arena.isReady()) arena.issues().forEach(i -> sender.sendMessage("§7  - §c" + i));
                sender.sendMessage("§7Status: §f" + (gm != null ? gm.getState() : "n/a"));
            }
            default -> sender.sendMessage("§cOnbekend subcommando.");
        }
        return true;
    }

    private void requirePlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (sender instanceof Player p) action.accept(p);
        else sender.sendMessage("§cAlleen spelers kunnen dit.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1)
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        return List.of();
    }
}
