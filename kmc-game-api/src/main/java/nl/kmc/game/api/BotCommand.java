package nl.kmc.game.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /kmcbot} — test players for when you're testing alone. A bot is a real server-side player
 * with no client: it stands where it's put, falls, takes damage, respawns, and counts as an online
 * player for every game. See {@link FakePlayerSpawner}.
 */
public final class BotCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_BOTS = 30;

    private final JavaPlugin plugin;

    public BotCommand(JavaPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin")) {
            sender.sendMessage("§cGeen toestemming.");
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "add" -> add(sender, args);
            case "remove" -> {
                if (args.length < 2) { sender.sendMessage("§cGebruik: /kmcbot remove <naam|all>"); return true; }
                if (args[1].equalsIgnoreCase("all")) {
                    sender.sendMessage("§a" + FakePlayerSpawner.removeAll() + " bot(s) verwijderd.");
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    sender.sendMessage(FakePlayerSpawner.isBot(target)
                            ? (FakePlayerSpawner.remove(target) ? "§aBot " + args[1] + " verwijderd." : "§cVerwijderen mislukt.")
                            : "§c" + args[1] + " is geen bot.");
                }
            }
            case "list" -> {
                var bots = FakePlayerSpawner.bots();
                sender.sendMessage("§6Bots (" + bots.size() + "): §f" + (bots.isEmpty() ? "geen"
                        : String.join("§7, §f", bots.stream().map(Player::getName).toList())));
            }
            case "here" -> {
                if (!(sender instanceof Player admin)) { sender.sendMessage("Alleen spelers."); return true; }
                var bots = new ArrayList<>(FakePlayerSpawner.bots());
                for (int i = 0; i < bots.size(); i++) bots.get(i).teleport(spread(admin.getLocation(), i, bots.size()));
                sender.sendMessage("§a" + bots.size() + " bot(s) naar jou toe geteleporteerd.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void add(CommandSender sender, String[] args) {
        int count = 1;
        if (args.length >= 2) {
            try { count = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { sender.sendMessage("§cGebruik: /kmcbot add [aantal] [voorvoegsel]"); return; }
        }
        String prefix = args.length >= 3 ? args[2] : "Bot";
        if (count < 1 || count > MAX_BOTS) { sender.sendMessage("§cAantal moet tussen 1 en " + MAX_BOTS + " liggen."); return; }
        if (prefix.length() > 12) { sender.sendMessage("§cVoorvoegsel max 12 tekens."); return; }

        Location base = sender instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
        int made = 0, number = 1;
        while (made < count && number < 1000) {
            String name = prefix + number++;
            if (Bukkit.getPlayerExact(name) != null) continue;   // taken (by a bot or a real player)
            try {
                FakePlayerSpawner.spawn(plugin, name, spread(base, made, count));
                made++;
            } catch (IllegalStateException e) {
                sender.sendMessage("§c" + e.getMessage());
                plugin.getLogger().warning("Bot spawn failed: " + e.getMessage());
                break;
            }
        }
        if (made > 0) sender.sendMessage("§a" + made + " bot(s) toegevoegd. §7Ze blijven staan, vallen, krijgen schade en respawnen. "
                + "Verwijderen: §e/kmcbot remove all");
    }

    /** Positions on a small ring around {@code centre} so bots don't stand inside each other (or the admin). */
    private static Location spread(Location centre, int index, int total) {
        if (total <= 1) return centre.clone().add(2, 0, 0);
        double ang = Math.PI * 2 * index / Math.max(1, total);
        return centre.clone().add(Math.cos(ang) * 2.5, 0, Math.sin(ang) * 2.5);
    }

    private void usage(CommandSender s) {
        s.sendMessage("§6=== /kmcbot §7(testspelers) §6===");
        s.sendMessage("§e/kmcbot add [aantal] [voorvoegsel] §7— voeg bots toe (naast jou)");
        s.sendMessage("§e/kmcbot remove <naam|all> §7— verwijder een bot of alle bots");
        s.sendMessage("§e/kmcbot list §7— toon alle bots");
        s.sendMessage("§e/kmcbot here §7— teleporteer alle bots naar jou");
        s.sendMessage("§8Bots tellen als echte spelers voor alle games en blijven staan waar je ze zet.");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("add", "remove", "list", "here").stream().filter(o -> o.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            List<String> names = new ArrayList<>(FakePlayerSpawner.bots().stream().map(Player::getName).toList());
            names.add("all");
            return names.stream().filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) return List.of("1", "2", "4", "8");
        return List.of();
    }
}
