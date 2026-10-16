package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /kmcpodium set | remove | test | info}: the physical podium of the winner ceremony. */
public final class PodiumCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public PodiumCommand(KMCCore plugin) { this.plugin = plugin; }

    private static void msg(CommandSender s, String text) { s.sendMessage(MessageUtil.color(text)); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin") && !sender.hasPermission("kmc.tournament.admin")) {
            msg(sender, "&cGeen toestemming.");
            return true;
        }
        var pm = plugin.getPodium();
        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "set" -> {
                if (!(sender instanceof Player p)) { msg(sender, "&cAlleen spelers."); return true; }
                pm.set(p.getLocation());
                msg(sender, "&a[KMC] Podium gebouwd. &7Het kijkt naar waar jij keek; de winnaars staan er straks op, "
                        + "de rest kijkt mee via een camera. Test met &e/kmcpodium test&7.");
            }
            case "remove" -> msg(sender, pm.remove() ? "&a[KMC] Podium verwijderd." : "&7Er is geen podium.");
            case "test" -> {
                if (!(sender instanceof Player p)) { msg(sender, "&cAlleen spelers."); return true; }
                var spot = pm.spot(1);
                if (spot == null) { msg(sender, "&cEr is nog geen podium: &e/kmcpodium set"); return true; }
                p.teleport(spot);
                msg(sender, "&a[KMC] Je staat op de eerste plek.");
            }
            default -> {
                var c = pm.getCenter();
                msg(sender, "&6[KMC] Podium: " + (c == null ? "&7niet ingesteld"
                        : "&f" + c.getWorld().getName() + " " + c.getBlockX() + ", " + c.getBlockY() + ", " + c.getBlockZ()));
                msg(sender, "&7/kmcpodium set | remove | test &8— zet je op de plek en kijk naar de toeschouwers.");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        return List.of("set", "remove", "test", "info").stream().filter(o -> o.startsWith(a[0].toLowerCase())).toList();
    }
}
