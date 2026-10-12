package nl.kmc.dodgebolt.commands;

import nl.kmc.dodgebolt.DodgeboltPlugin;
import nl.kmc.dodgebolt.managers.ArenaConfig;
import nl.kmc.dodgebolt.managers.ArenaConfig.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /dodgebolt — arena setup and manual control.
 *
 * <pre>
 * /dodgebolt spawn <a|b>        add your position as a spawn for that side (stand where a player should start)
 * /dodgebolt clearspawns <a|b>  remove all spawns of a side
 * /dodgebolt drop <a|b>         where supply arrows appear for that side (default: middle of its spawns)
 * /dodgebolt spectator          spectator / eliminated-viewer spot
 * /dodgebolt center             middle of the arena (flyover target)
 * /dodgebolt void [y]           players below this Y are out (no value = your Y - 6)
 * /dodgebolt status             what is configured
 * /dodgebolt start [A B]        start a match (team ids; default = top two of the standings)
 * /dodgebolt stop               end the match now
 * /dodgebolt cam                flyover camera menu
 * </pre>
 */
public final class DodgeboltCommand implements CommandExecutor, TabCompleter {

    private final DodgeboltPlugin plugin;

    public DodgeboltCommand(DodgeboltPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("dodgebolt.admin")) { s.sendMessage("§cGeen toestemming."); return true; }
        if (a.length == 0) { help(s, label); return true; }
        ArenaConfig arena = plugin.getArena();

        switch (a[0].toLowerCase()) {
            case "spawn" -> {
                Player p = player(s); Side side = a.length > 1 ? side(a[1]) : null;
                if (p == null) return true;
                if (side == null) { s.sendMessage("§cGebruik: /" + label + " spawn <a|b>"); return true; }
                arena.addSpawn(side, p.getLocation());
                s.sendMessage("§a[Dodgebolt] Spawn §e" + arena.spawns(side).size() + "§a toegevoegd voor kant §e" + side + "§a.");
            }
            case "clearspawns" -> {
                Side side = a.length > 1 ? side(a[1]) : null;
                if (side == null) { s.sendMessage("§cGebruik: /" + label + " clearspawns <a|b>"); return true; }
                arena.clearSpawns(side);
                s.sendMessage("§a[Dodgebolt] Spawns van kant " + side + " gewist.");
            }
            case "drop" -> {
                Player p = player(s); Side side = a.length > 1 ? side(a[1]) : null;
                if (p == null) return true;
                if (side == null) { s.sendMessage("§cGebruik: /" + label + " drop <a|b>"); return true; }
                arena.setDrop(side, p.getLocation());
                s.sendMessage("§a[Dodgebolt] Pijlen-drop van kant " + side + " ingesteld.");
            }
            case "spectator" -> {
                Player p = player(s);
                if (p == null) return true;
                arena.setSpectator(p.getLocation());
                s.sendMessage("§a[Dodgebolt] Toeschouwerspot ingesteld.");
            }
            case "center" -> {
                Player p = player(s);
                if (p == null) return true;
                arena.setCenter(p.getLocation());
                s.sendMessage("§a[Dodgebolt] Midden van de arena ingesteld.");
            }
            case "void" -> {
                double y;
                if (a.length > 1) {
                    try { y = Double.parseDouble(a[1]); }
                    catch (NumberFormatException e) { s.sendMessage("§cGeen geldig getal."); return true; }
                } else {
                    Player p = player(s);
                    if (p == null) return true;
                    y = p.getLocation().getY() - 6;
                }
                arena.setVoidY(y);
                s.sendMessage("§a[Dodgebolt] Void-niveau: Y=" + y);
            }
            case "status" -> status(s, arena);
            case "start" -> {
                var gm = plugin.getGameManagerV2();
                if (gm == null) { s.sendMessage("§cGame niet beschikbaar."); return true; }
                if (a.length >= 3) gm.requestTeams(a[1], a[2]);
                if (gm.start()) s.sendMessage("§a[Dodgebolt] Match gestart.");
                else gm.reportArenaIssues(s);
            }
            case "stop" -> {
                var gm = plugin.getGameManagerV2();
                if (gm != null && gm.isRunning()) { gm.end(); s.sendMessage("§e[Dodgebolt] Match gestopt."); }
                else s.sendMessage("§7Er loopt geen match.");
            }
            default -> help(s, label);
        }
        return true;
    }

    private void status(CommandSender s, ArenaConfig arena) {
        s.sendMessage("§6=== Dodgebolt ===");
        s.sendMessage("§7Kant A: §e" + arena.spawns(Side.A).size() + " §7spawn(s)  §8| §7Kant B: §e"
                + arena.spawns(Side.B).size() + " §7spawn(s)");
        s.sendMessage("§7Toeschouwerspot: " + (arena.spectator() != null ? "§aingesteld" : "§7niet ingesteld"));
        s.sendMessage("§7Void-niveau: §eY=" + arena.voidY());
        arena.errors().forEach(e -> s.sendMessage("§c✘ " + e));
        arena.warnings().forEach(w -> s.sendMessage("§e⚠ " + w));
        if (arena.errors().isEmpty()) s.sendMessage("§a✔ Arena klaar om te spelen.");
    }

    private void help(CommandSender s, String label) {
        s.sendMessage("§6=== Dodgebolt ===");
        s.sendMessage("§e/" + label + " spawn <a|b> §7| §eclearspawns <a|b> §7| §edrop <a|b>");
        s.sendMessage("§e/" + label + " spectator §7| §ecenter §7| §evoid [y] §7| §estatus");
        s.sendMessage("§e/" + label + " start [teamA teamB] §7| §estop §7| §ecam");
    }

    private static Side side(String s) {
        return switch (s.toLowerCase()) { case "a" -> Side.A; case "b" -> Side.B; default -> null; };
    }

    private static Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        s.sendMessage("§cAlleen spelers.");
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("spawn", "clearspawns", "drop", "spectator", "center", "void", "status",
                "start", "stop", "cam").stream().filter(o -> o.startsWith(a[0].toLowerCase())).toList();
        if (a.length == 2 && List.of("spawn", "clearspawns", "drop").contains(a[0].toLowerCase()))
            return List.of("a", "b");
        return List.of();
    }
}
