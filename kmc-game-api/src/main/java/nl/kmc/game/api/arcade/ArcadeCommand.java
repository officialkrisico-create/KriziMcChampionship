package nl.kmc.game.api.arcade;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The admin command every arcade game shares: {@code start [dry]}, {@code stop}, {@code status},
 * {@code validate}, {@code world}, {@code spawn add|clear|list}, {@code pos1}, {@code pos2}. A game adds its
 * own sub-commands by overriding {@link #extra}.
 */
public abstract class ArcadeCommand implements CommandExecutor, TabCompleter {

    protected final JavaPlugin plugin;
    protected final ArenaConfig arena;
    private final Supplier<ArcadeGameManager> manager;
    private final String permission;
    private final String title;

    protected ArcadeCommand(JavaPlugin plugin, ArenaConfig arena, Supplier<ArcadeGameManager> manager,
                            String permission, String title) {
        this.plugin = plugin;
        this.arena = arena;
        this.manager = manager;
        this.permission = permission;
        this.title = title;
    }

    /** Problems that stop a start (same list the validator uses). */
    protected abstract List<String> problems();

    /** Game-specific sub-commands; return true when handled. */
    protected boolean extra(CommandSender s, String sub, String[] a, String label) { return false; }

    protected List<String> extraSubs() { return List.of(); }

    protected List<String> extraTab(String sub, String[] a) { return List.of(); }

    protected String help(String label) {
        return "§6" + title + " §7— /" + label + " start [dry]|stop|status|validate|world|spawn|pos1|pos2"
                + (extraSubs().isEmpty() ? "" : "|" + String.join("|", extraSubs()));
    }

    protected final void ok(CommandSender s, String msg) { s.sendMessage("§a[" + title + "] §f" + msg); }
    protected final void err(CommandSender s, String msg) { s.sendMessage("§c[" + title + "] " + msg); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(permission)) { s.sendMessage("§cGeen toestemming."); return true; }
        if (a.length == 0) { s.sendMessage(help(label)); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        ArcadeGameManager gm = manager.get();
        switch (sub) {
            case "start" -> {
                boolean dry = a.length > 1 && a[1].equalsIgnoreCase("dry");
                if (gm == null) { err(s, "Game niet beschikbaar."); return true; }
                boolean started = dry ? gm.startDry() : gm.start();
                if (started) ok(s, dry ? "Dry-run gestart (er worden geen punten of statistieken geboekt)." : "Gestart!");
                else err(s, "Start mislukt: " + problems());
            }
            case "stop" -> {
                if (gm != null && gm.isRunning()) { gm.end(); ok(s, "Gestopt."); }
                else s.sendMessage("§7Er loopt geen game.");
            }
            case "status" -> {
                s.sendMessage("§6" + title + " §7— status: §f" + (gm != null ? gm.getState() : "?")
                        + " §7| spelers: §f" + (gm != null ? gm.getParticipants().size() : 0));
                List<String> p = problems();
                s.sendMessage(p.isEmpty() ? "§a✔ Klaar om te spelen" : "§c✘ " + p);
            }
            case "validate" -> {
                List<String> p = problems();
                s.sendMessage(p.isEmpty() ? "§a✔ Configuratie is geldig." : "§c✘ " + String.join("§7; §c", p));
            }
            case "world" -> {
                if (!(s instanceof Player pl)) { err(s, "Alleen spelers."); return true; }
                arena.setWorld(pl.getWorld());
                ok(s, "Arena-wereld: " + pl.getWorld().getName());
            }
            case "spawn" -> {
                if (!(s instanceof Player pl)) { err(s, "Alleen spelers."); return true; }
                String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "add";
                switch (act) {
                    case "clear" -> { arena.clearLocs("spawns"); ok(s, "Startposities gewist."); }
                    case "list" -> {
                        int i = 1;
                        for (Location l : arena.locs("spawns"))
                            s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                    }
                    default -> ok(s, "Startpositie #" + arena.addLoc("spawns", pl.getLocation()) + " toegevoegd.");
                }
            }
            case "pos1", "pos2" -> {
                if (!(s instanceof Player pl)) { err(s, "Alleen spelers."); return true; }
                arena.pick(pl, sub.equals("pos1") ? 0 : 1, pl.getLocation());
                ok(s, sub + " gezet op het blok waar je staat.");
            }
            default -> {
                if (!extra(s, sub, a, label)) s.sendMessage(help(label));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>(List.of("start", "stop", "status", "validate", "world", "spawn", "pos1", "pos2"));
        out.addAll(extraSubs());
        if (a.length == 1) {
            out.removeIf(o -> !o.startsWith(a[0].toLowerCase(Locale.ROOT)));
            return out;
        }
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "start": return List.of("dry");
                case "spawn": return List.of("add", "clear", "list");
                default: return extraTab(a[0].toLowerCase(Locale.ROOT), a);
            }
        }
        return extraTab(a[0].toLowerCase(Locale.ROOT), a);
    }
}
