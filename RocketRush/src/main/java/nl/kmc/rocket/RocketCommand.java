package nl.kmc.rocket;

import nl.kmc.game.api.arcade.ArcadeCommand;
import nl.kmc.game.api.arcade.ArenaConfig;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /rocket — see the setup list at the top of config.yml. */
final class RocketCommand extends ArcadeCommand {

    private final RocketRushPlugin game;

    RocketCommand(RocketRushPlugin plugin, ArenaConfig arena) {
        super(plugin, arena, plugin::manager, "rocket.admin", "Rocket Rush");
        this.game = plugin;
    }

    @Override protected List<String> problems() { return game.problems(); }

    @Override protected List<String> extraSubs() { return List.of("checkpoint", "ring", "pickup", "finish", "floor"); }

    @Override
    protected List<String> extraTab(String sub, String[] a) {
        if (a.length == 2 && List.of("checkpoint", "ring", "pickup").contains(sub)) return List.of("add", "clear", "list");
        return List.of();
    }

    @Override
    protected boolean extra(CommandSender s, String sub, String[] a, String label) {
        if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return true; }
        String act = a.length > 1 ? a[1].toLowerCase() : "add";
        switch (sub) {
            case "checkpoint", "ring", "pickup" -> {
                String path = sub.equals("checkpoint") ? "checkpoints" : sub.equals("ring") ? "rings" : "pickups";
                switch (act) {
                    case "clear" -> { arena.clearLocs(path); ok(s, sub + "s gewist."); }
                    case "list" -> {
                        int i = 1;
                        for (Location l : arena.locs(path))
                            s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                    }
                    default -> ok(s, sub + " #" + arena.addLoc(path, p.getLocation()) + " toegevoegd.");
                }
                return true;
            }
            case "finish" -> {
                arena.setLoc("finish", p.getLocation());
                ok(s, "Finish gezet.");
                return true;
            }
            case "floor" -> {
                plugin.getConfig().set("arena.floor-y", p.getLocation().getBlockY());
                plugin.saveConfig();
                ok(s, "Vloer-hoogte: Y=" + p.getLocation().getBlockY() + " (eronder = gevallen).");
                return true;
            }
            default -> { return false; }
        }
    }
}
