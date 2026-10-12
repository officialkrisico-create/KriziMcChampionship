package nl.kmc.hitw.commands;

import nl.kmc.hitw.HoleInTheWallPlugin;
import nl.kmc.hitw.wall.WallSpec;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * /hitw — setup and control.
 *
 * <pre>
 *   /hitw setcenter          stand on the middle of the platform
 *   /hitw setradius &lt;n&gt;      platform radius (blocks)
 *   /hitw setspectator       where eliminated players watch (optional)
 *   /hitw build              (re)build the platform
 *   /hitw start | stop | status | reload
 *   /hitw types              list wall types
 *   /hitw testwall &lt;type&gt; [direction-degrees]   spawn one harmless demo wall to look at
 *   /hitw clearwalls
 * </pre>
 */
public class HitwCommand implements CommandExecutor, TabCompleter {

    private final HoleInTheWallPlugin plugin;
    private BukkitTask demoTask;

    public HitwCommand(HoleInTheWallPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("hitw.admin")) { sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true; }
        if (args.length == 0) { usage(sender); return true; }

        var am = plugin.getArenaManager();
        var gm = plugin.getGameManagerV2();

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (gm.start()) sender.sendMessage(ChatColor.GREEN + "Hole in the Wall gestart!");
                else gm.reportArenaIssues(sender);
            }
            case "stop" -> { gm.end(); stopDemo(); sender.sendMessage(ChatColor.RED + "Game gestopt."); }
            case "setcenter" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                am.setCenter(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Midden = het blok waar je op staat ("
                        + am.getArena().getCx() + ", " + am.getArena().getCy() + ", " + am.getArena().getCz() + ").");
                sender.sendMessage(ChatColor.GRAY + "Gebruik /hitw build om het platform neer te zetten.");
            }
            case "setradius" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /hitw setradius <aantal>"); return true; }
                try { am.setRadius(Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Geen geldig getal."); return true; }
                sender.sendMessage(ChatColor.GREEN + "Radius = " + am.getArena().getRadius());
            }
            case "setspectator" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                am.setSpectator(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Spectator-spot gezet.");
            }
            case "build" -> {
                if (!am.isReady()) { sender.sendMessage(ChatColor.RED + "Zet eerst het midden: /hitw setcenter"); return true; }
                am.getArena().buildPlatform();
                sender.sendMessage(ChatColor.GREEN + "Platform gebouwd (radius " + am.getArena().getRadius() + ").");
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "=== Hole in the Wall ===");
                sender.sendMessage(ChatColor.YELLOW + "State: " + gm.getState());
                for (String line : am.getReadinessReport().split("\n")) sender.sendMessage(ChatColor.GRAY + line);
                sender.sendMessage(ChatColor.GRAY + "Wall-types: " + String.join(", ", plugin.getHitwConfig().wallTypes.ids()));
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getHitwConfig().load(plugin.getConfig());
                am.load();
                sender.sendMessage(ChatColor.GREEN + "Config herladen.");
            }
            case "types" -> {
                sender.sendMessage(ChatColor.GOLD + "Wall-types:");
                plugin.getHitwConfig().wallTypes.all().forEach(t -> sender.sendMessage(ChatColor.YELLOW + t.id()
                        + ChatColor.GRAY + " — " + t.shape() + ", x" + t.speedMult()));
            }
            case "testwall" -> testWall(sender, args);
            case "clearwalls" -> { stopDemo(); gm.getWallManager().clear(); sender.sendMessage(ChatColor.GREEN + "Muren verwijderd."); }
            default -> usage(sender);
        }
        return true;
    }

    /** Spawns one wall with no collision and no game, just to look at its size, colour and speed. */
    private void testWall(CommandSender sender, String[] args) {
        var gm = plugin.getGameManagerV2();
        if (gm.getState().isRunning()) { sender.sendMessage(ChatColor.RED + "Er loopt een game — stop die eerst."); return; }
        var am = plugin.getArenaManager();
        if (!am.isReady()) { sender.sendMessage(ChatColor.RED + "Arena niet klaar (/hitw setcenter)."); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Gebruik: /hitw testwall <type> [graden]"); return; }
        var cfg = plugin.getHitwConfig();
        if (cfg.wallTypes.get(args[1]) == null) {
            sender.sendMessage(ChatColor.RED + "Onbekend type. Zie /hitw types."); return;
        }
        double deg = 0;
        if (args.length >= 3) {
            try { deg = Double.parseDouble(args[2]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Ongeldige hoek."); return; }
        }
        var walls = gm.getWallManager();
        Random rnd = new Random();
        walls.spawn(new WallSpec(args[1].toUpperCase(), Math.toRadians(deg), 0, 1.0), walls.context(0, 4, 1.0, rnd));
        sender.sendMessage(ChatColor.GREEN + "Testmuur " + args[1].toUpperCase() + " komt eraan (geen schade).");

        if (demoTask == null) {
            demoTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                walls.advance(0.05, cfg.speedCurve.speedAt(0));
                if (walls.walls().isEmpty()) stopDemo();
            }, 1L, 1L);
        }
    }

    private void stopDemo() {
        if (demoTask != null) { demoTask.cancel(); demoTask = null; }
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Hole in the Wall ===");
        s.sendMessage(ChatColor.YELLOW + "/hitw setcenter | setradius <n> | setspectator | build");
        s.sendMessage(ChatColor.YELLOW + "/hitw start | stop | status | reload");
        s.sendMessage(ChatColor.YELLOW + "/hitw types | testwall <type> [graden] | clearwalls");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "setcenter", "setradius", "setspectator", "build", "status",
                    "reload", "types", "testwall", "clearwalls").stream()
                    .filter(o -> o.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("testwall")) {
            return plugin.getHitwConfig().wallTypes.ids().stream()
                    .filter(o -> o.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("testwall")) return List.of("0", "90", "180", "270");
        return List.of();
    }
}
