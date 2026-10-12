package nl.kmc.kmccore.lobbygames;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /ttt, /darts and /lobbygames (see plugin.yml). */
public final class LobbyGamesCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;
    private final LobbyGamesManager mgr;
    private final LobbyGamesListener listener;

    public LobbyGamesCommand(KMCCore plugin, LobbyGamesManager mgr, LobbyGamesListener listener) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
        var lm = plugin.getLanguageManager();
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "ttt" -> {
                if (a.length == 0) {
                    if (!mgr.reopen(p)) p.sendMessage(lm.tr(p, "lobbygames.ttt.usage"));
                    return true;
                }
                switch (a[0].toLowerCase(Locale.ROOT)) {
                    case "accept" -> mgr.accept(p);
                    case "decline" -> mgr.decline(p);
                    case "stop", "forfeit" -> mgr.forfeit(p);
                    default -> {
                        Player target = Bukkit.getPlayerExact(a[0]);
                        if (target == null) p.sendMessage(lm.tr(p, "lobbygames.ttt.offline", a[0]));
                        else mgr.challenge(p, target);
                    }
                }
            }
            case "lobbyspleef" -> mgr.spleef().join(p);
            case "darts" -> {
                if (!mgr.checkAvailable(p)) return true;
                p.getInventory().addItem(listener.dartStack(p));
                p.sendMessage(lm.tr(p, "lobbygames.darts.given"));
                if (mgr.getBoards().isEmpty())
                    p.sendMessage(lm.tr(p, "lobbygames.darts.hint"));
            }
            default -> lobbyGames(p, a);
        }
        return true;
    }

    private void lobbyGames(Player p, String[] a) {
        var lm = plugin.getLanguageManager();
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "top";
        switch (sub) {
            case "top" -> {
                String game = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                List<String> all = List.of("ttt", "darts", "fish", "parkour", "spleef");
                List<String> games = game.isEmpty() ? all : List.of(game);
                for (String g : games) {
                    if (!all.contains(g)) { p.sendMessage(lm.tr(p, "lobbygames.top.usage")); return; }
                    p.sendMessage(lm.tr(p, "lobbygames.top.header." + g));
                    List<String[]> rows = mgr.top(g, 5);
                    if (rows.isEmpty()) p.sendMessage(lm.tr(p, "lobbygames.top.empty"));
                    int i = 1;
                    for (String[] r : rows) p.sendMessage(lm.tr(p, "lobbygames.top.line", i++, r[0], r[1]));
                }
            }
            case "parkour" -> {
                var pk = mgr.parkour();
                String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "reset";
                boolean admin = p.hasPermission("kmc.lobbygames.admin");
                switch (act) {
                    case "start", "checkpoint", "finish", "clear" -> {
                        if (!admin) { p.sendMessage(lm.tr(p, "common.no-permission")); return; }
                        switch (act) {
                            case "start" -> pk.setStart(p.getLocation());
                            case "finish" -> pk.setFinish(p.getLocation());
                            case "checkpoint" -> p.sendMessage(lm.tr(p, "lobbygames.parkour.cp-added", pk.addCheckpoint(p.getLocation())));
                            default -> pk.clear();
                        }
                        if (!act.equals("checkpoint")) p.sendMessage(lm.tr(p, "lobbygames.parkour.set." + act));
                    }
                    case "top" -> p.performCommand("lobbygames top parkour");
                    default -> {
                        if (!pk.isReady()) p.sendMessage(lm.tr(p, "lobbygames.parkour.not-ready"));
                        else pk.reset(p);
                    }
                }
            }
            case "spleef" -> {
                var sp = mgr.spleef();
                String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "join";
                switch (act) {
                    case "pos1", "pos2", "material" -> {
                        if (!p.hasPermission("kmc.lobbygames.admin")) { p.sendMessage(lm.tr(p, "common.no-permission")); return; }
                        if (act.equals("pos1")) sp.setPos1(p.getLocation().subtract(0, 1, 0));
                        else if (act.equals("pos2")) sp.setPos2(p.getLocation().subtract(0, 1, 0));
                        else if (a.length < 3 || !sp.setMaterial(a[2])) { p.sendMessage(lm.tr(p, "lobbygames.spleef.bad-material")); return; }
                        p.sendMessage(lm.tr(p, "lobbygames.spleef.set." + act));
                    }
                    case "top" -> p.performCommand("lobbygames top spleef");
                    default -> sp.join(p);
                }
            }
            case "board" -> {
                if (!p.hasPermission("kmc.lobbygames.admin")) { p.sendMessage(lm.tr(p, "common.no-permission")); return; }
                String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
                switch (act) {
                    case "add" -> {
                        var b = p.getTargetBlockExact(6);
                        if (b == null) { p.sendMessage(lm.tr(p, "lobbygames.board.look")); return; }
                        p.sendMessage(lm.tr(p, mgr.addBoard(b.getLocation()) ? "lobbygames.board.added" : "lobbygames.board.exists"));
                    }
                    case "clear" -> { mgr.clearBoards(); p.sendMessage(lm.tr(p, "lobbygames.board.cleared")); }
                    default -> {
                        p.sendMessage(lm.tr(p, "lobbygames.board.count", mgr.getBoards().size()));
                        for (Location l : mgr.getBoards())
                            p.sendMessage("§7 - " + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                    }
                }
            }
            default -> p.sendMessage(lm.tr(p, "lobbygames.top.usage"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("ttt") && a.length == 1) {
            List<String> out = new ArrayList<>(Arrays.asList("accept", "decline", "stop"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out;
        }
        if (n.equals("lobbygames")) {
            if (a.length == 1) return Arrays.asList("top", "board", "parkour", "spleef");
            if (a.length == 2 && a[0].equalsIgnoreCase("top")) return Arrays.asList("ttt", "darts", "fish", "parkour", "spleef");
            if (a.length == 2 && a[0].equalsIgnoreCase("parkour")) return Arrays.asList("reset", "start", "checkpoint", "finish", "clear", "top");
            if (a.length == 2 && a[0].equalsIgnoreCase("spleef")) return Arrays.asList("join", "pos1", "pos2", "material", "top");
            if (a.length == 2 && a[0].equalsIgnoreCase("board")) return Arrays.asList("add", "clear", "list");
        }
        return List.of();
    }
}
