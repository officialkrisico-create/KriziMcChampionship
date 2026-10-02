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
 * {@code /kmctest <game|all|list>} — generates a small throw-away arena for a game (or for every
 * game that supports it) at the admin's position, and configures the game to use it, so a game
 * can be tested without a hand-built map.
 *
 * <p>Arenas are built high in the sky (see {@link TestArenaKit#baseY}); {@code all} places them
 * side by side so one {@code /kmcauto start} can then play a whole tournament through them.
 */
public final class TestArenaCommand implements CommandExecutor, TabCompleter {

    /** Distance between arenas when building {@code all} (blocks along +X). */
    private static final int SLOT_SPACING = 240;

    private final JavaPlugin plugin;

    public TestArenaCommand(JavaPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.admin")) {
            sender.sendMessage("§cGeen toestemming.");
            return true;
        }
        if (!(sender instanceof Player admin)) { sender.sendMessage("Alleen spelers."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) { list(sender); return true; }

        if (args[0].equalsIgnoreCase("all")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                admin.sendMessage("§c⚠ §7Dit bouwt een test-arena voor ELKE ondersteunde game en §coverschrijft §7hun arena-instellingen.");
                admin.sendMessage("§7Weet je het zeker? Typ §e/kmctest all confirm");
                return true;
            }
            buildAll(admin);
            return true;
        }

        var entry = TestArenaRegistry.get(args[0].toLowerCase());
        if (entry.isEmpty()) {
            sender.sendMessage("§cOnbekende game '" + args[0] + "' (of die ondersteunt geen test-arena). §7Zie /kmctest list.");
            return true;
        }
        Location tp = build(admin, entry.get(), admin.getLocation());
        if (tp != null) admin.teleport(tp);
        return true;
    }

    private void list(CommandSender sender) {
        sender.sendMessage("§6=== /kmctest ===");
        sender.sendMessage("§e/kmctest <game> §7— bouw een test-arena voor één game op jouw positie");
        sender.sendMessage("§e/kmctest all confirm §7— bouw ze allemaal naast elkaar (daarna /kmcauto start voor een heel toernooi)");
        sender.sendMessage("§7Ondersteunde games: §f" + (TestArenaRegistry.all().isEmpty() ? "(geen)" :
                String.join("§7, §f", TestArenaRegistry.all().stream().map(TestArenaRegistry.Entry::gameId).toList())));
        sender.sendMessage("§8Arena's staan hoog in de lucht (Y≈200), dus terrein maakt niet uit.");
        sender.sendMessage("§c⚠ Dit OVERSCHRIJFT de arena-instellingen van die game (spawns, eilanden, enz.). §7Heb je al een echte map voor een game? Bouw dan geen test-arena voor die game.");
    }

    private void buildAll(Player admin) {
        List<TestArenaRegistry.Entry> entries = new ArrayList<>(TestArenaRegistry.all());
        if (entries.isEmpty()) { admin.sendMessage("§cGeen enkele game ondersteunt een test-arena."); return; }
        admin.sendMessage("§6[KMC] §7Bouwt " + entries.size() + " test-arena's naast elkaar...");

        Location base = admin.getLocation();
        final Location[] firstView = {null};
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            int slot = i;
            // One arena per tick-batch so a big build never stalls the server in a single tick.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location origin = base.clone().add((double) slot * SLOT_SPACING, 0, 0);
                Location view = build(admin, entry, origin);
                if (slot == 0) firstView[0] = view;
                if (slot == entries.size() - 1) {
                    admin.sendMessage("§a[KMC] Klaar! §7Controleer met §e/kmcvalidate§7, start dan §e/kmcauto start§7.");
                    if (firstView[0] != null) admin.teleport(firstView[0]);
                }
            }, 5L + (long) slot * 10L);
        }
    }

    private Location build(Player admin, TestArenaRegistry.Entry entry, Location origin) {
        try {
            Location view = entry.builder().build(admin, origin);
            admin.sendMessage("§a✔ §f" + entry.displayName() + " §7test-arena gebouwd en ingesteld.");
            return view;
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Test arena for " + entry.gameId() + " failed", t);
            admin.sendMessage("§c✘ " + entry.displayName() + ": " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " — " + t.getMessage() : "") + " §7(zie console)");
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("all")) return List.of("confirm");
        if (args.length != 1) return List.of();
        List<String> opts = new ArrayList<>(List.of("all", "list"));
        TestArenaRegistry.all().forEach(e -> opts.add(e.gameId()));
        return opts.stream().filter(o -> o.startsWith(args[0].toLowerCase())).toList();
    }
}
