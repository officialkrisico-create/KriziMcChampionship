package nl.kmc.inferno.commands;

import nl.kmc.inferno.RisingInfernoPlugin;
import nl.kmc.inferno.managers.InfernoArena;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /inferno — control and setup of Rising Inferno.
 *
 * <pre>
 * /inferno start | stop | status | info
 * /inferno lava pos1|pos2     corners of the area the lava fills (the block you stand on)
 * /inferno lava base          the lava starts at the block you stand on
 * /inferno finish pos1|pos2   corners of the finish box (give it some height: 3 blocks)
 * /inferno spawn add|clear    start positions
 * /inferno orb add|clear      bonus orbs (on the risky route)
 * /inferno spectator          where eliminated players watch
 * /inferno rise &lt;seconds&gt;     how long the lava needs to reach the top in this arena (0 = config default)
 * </pre>
 */
public final class InfernoCommand implements CommandExecutor, TabCompleter {

    private final RisingInfernoPlugin plugin;

    public InfernoCommand(RisingInfernoPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("inferno.admin")) { s.sendMessage("§cGeen toestemming."); return true; }
        if (a.length == 0) { help(s, label); return true; }
        InfernoArena arena = plugin.getArena();

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                var gm = plugin.getGameManagerV2();
                if (gm == null || !gm.start())
                    s.sendMessage("§c[Rising Inferno] Start mislukt: " + arena.problems());
                else s.sendMessage("§a[Rising Inferno] Gestart!");
            }
            case "stop" -> {
                var gm = plugin.getGameManagerV2();
                if (gm != null && gm.isRunning()) { gm.end(); s.sendMessage("§e[Rising Inferno] Gestopt."); }
                else s.sendMessage("§7Er loopt geen game.");
            }
            case "status", "info" -> {
                var gm = plugin.getGameManagerV2();
                s.sendMessage("§c§lRising Inferno §7— status: §f" + (gm != null ? gm.getState() : "?"));
                s.sendMessage("§7Lava-gebied: §f" + (arena.getLavaArea() != null ? "gezet" : "niet gezet")
                        + " §7| Start-Y: §f" + (arena.getLavaBaseY() != null ? arena.getLavaBaseY() : "-")
                        + " §7| Finish: §f" + (arena.getFinish() != null ? "gezet" : "niet gezet"));
                s.sendMessage("§7Spawns: §f" + arena.getSpawns().size() + " §7| Orbs: §f" + arena.getOrbs().size()
                        + " §7| Stijgtijd: §f" + (arena.getRiseSeconds() > 0 ? arena.getRiseSeconds() + "s"
                        : plugin.getConfig().getInt("game.rise-seconds", 150) + "s (config)"));
                s.sendMessage(arena.isReady() ? "§a✔ Klaar om te spelen" : "§c✘ " + arena.problems());
            }
            case "lava" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "pos1" -> { arena.pick(0, p.getLocation()); s.sendMessage("§a[Rising Inferno] Lava-hoek 1 gezet."); }
                    case "pos2" -> { arena.pick(1, p.getLocation()); s.sendMessage("§a[Rising Inferno] Lava-hoek 2 gezet."); }
                    case "base" -> { arena.setLavaBase(p.getLocation());
                        s.sendMessage("§a[Rising Inferno] De lava begint op Y=" + arena.getLavaBaseY() + "."); }
                    default -> s.sendMessage("§cGebruik: /" + label + " lava <pos1|pos2|base>");
                }
            }
            case "finish" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("pos1")) { arena.pick(2, p.getLocation()); s.sendMessage("§a[Rising Inferno] Finish-hoek 1 gezet."); }
                else if (sub.equals("pos2")) { arena.pick(3, p.getLocation()); s.sendMessage("§a[Rising Inferno] Finish-hoek 2 gezet."); }
                else s.sendMessage("§cGebruik: /" + label + " finish <pos1|pos2>");
            }
            case "spawn" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                if (a.length > 1 && a[1].equalsIgnoreCase("clear")) {
                    arena.clearSpawns();
                    s.sendMessage("§a[Rising Inferno] Startposities gewist.");
                } else {
                    arena.addSpawn(p.getLocation());
                    s.sendMessage("§a[Rising Inferno] Startpositie #" + arena.getSpawns().size() + " toegevoegd.");
                }
            }
            case "orb" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                if (a.length > 1 && a[1].equalsIgnoreCase("clear")) {
                    arena.clearOrbs();
                    s.sendMessage("§a[Rising Inferno] Orbs gewist.");
                } else {
                    arena.addOrb(p.getLocation().add(0, 1, 0));
                    s.sendMessage("§a[Rising Inferno] Orb #" + arena.getOrbs().size() + " toegevoegd (boven je hoofd).");
                }
            }
            case "spectator" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                arena.setSpectator(p.getLocation());
                s.sendMessage("§a[Rising Inferno] Spectator-spot gezet.");
            }
            case "rise" -> {
                try {
                    arena.setRiseSeconds(Integer.parseInt(a[1]));
                    s.sendMessage("§a[Rising Inferno] Stijgtijd: " + arena.getRiseSeconds()
                            + (arena.getRiseSeconds() == 0 ? " (config-standaard)" : "s"));
                } catch (Exception e) { s.sendMessage("§cGebruik: /" + label + " rise <seconden>"); }
            }
            default -> help(s, label);
        }
        return true;
    }

    private void help(CommandSender s, String l) {
        s.sendMessage("§c§lRising Inferno §7— /" + l + " start|stop|status|lava|finish|spawn|orb|spectator|rise");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return Arrays.asList("start", "stop", "status", "lava", "finish", "spawn", "orb", "spectator", "rise");
        if (a.length == 2) {
            return switch (a[0].toLowerCase(Locale.ROOT)) {
                case "lava" -> Arrays.asList("pos1", "pos2", "base");
                case "finish" -> Arrays.asList("pos1", "pos2");
                case "spawn", "orb" -> Arrays.asList("add", "clear");
                default -> List.of();
            };
        }
        return List.of();
    }
}
