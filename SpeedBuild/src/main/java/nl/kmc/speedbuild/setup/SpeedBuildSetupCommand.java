package nl.kmc.speedbuild.setup;

import nl.kmc.speedbuild.SpeedBuildPlugin;
import nl.kmc.speedbuild.game.BuildDefinition;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** Admin control + setup for Speed Build (also surfaced in /kmcsetup). */
public final class SpeedBuildSetupCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "start", "stop", "anchor", "spawn", "gap", "addbuild", "listbuilds", "clearbuilds", "status");

    private final SpeedBuildPlugin plugin;

    public SpeedBuildSetupCommand(SpeedBuildPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§e[SpeedBuild] §7/speedbuild <" + String.join("|", SUBS) + ">"); return true; }
        var gm    = plugin.getGameManager();
        var arena = plugin.getArena();

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (gm == null) { sender.sendMessage("§cV2 niet beschikbaar."); return true; }
                if (!gm.start()) gm.reportArenaIssues(sender);
                else sender.sendMessage("§a[SpeedBuild] Gestart!");
            }
            case "stop" -> {
                if (gm != null && gm.isRunning()) { gm.end(); sender.sendMessage("§a[SpeedBuild] Gestopt."); }
                else sender.sendMessage("§7[SpeedBuild] Geen actieve game.");
            }
            case "anchor" -> player(sender, p -> { arena.setAnchor(p.getLocation()); p.sendMessage("§a[SpeedBuild] Build-anker gezet."); });
            case "spawn"  -> player(sender, p -> { arena.setSpawn(p.getLocation()); p.sendMessage("§a[SpeedBuild] Spawn gezet."); });
            case "gap" -> {
                if (args.length < 2) { sender.sendMessage("§c/speedbuild gap <blokken>"); return true; }
                try { arena.setSlotGap(Integer.parseInt(args[1])); sender.sendMessage("§a[SpeedBuild] Slot-gap = " + args[1]); }
                catch (NumberFormatException ex) { sender.sendMessage("§cGeen geldig getal."); }
            }
            case "addbuild" -> {
                if (args.length < 3) { sender.sendMessage("§c/speedbuild addbuild <id> <schematic.schem> [moeilijkheid] [gewicht] [naam...]"); return true; }
                String id = args[1];
                String schem = args[2];
                int diff   = args.length > 3 ? parseInt(args[3], 1) : 1;
                double w   = args.length > 4 ? parseDouble(args[4], 1.0) : 1.0;
                String name = args.length > 5 ? String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length)) : id;
                arena.addBuild(new BuildDefinition(id, name, schem, diff, w));
                sender.sendMessage("§a[SpeedBuild] Build toegevoegd (§f" + arena.getBuilds().size() + "/10§a): " + name);
                if (!plugin.getLoader().exists(schem))
                    sender.sendMessage("§e⚠ Let op: schematic-bestand '" + schem + "' nog niet gevonden.");
            }
            case "listbuilds" -> {
                sender.sendMessage("§e§lSpeed Build — Builds (" + arena.getBuilds().size() + "/10)");
                int i = 1;
                for (BuildDefinition b : arena.getBuilds())
                    sender.sendMessage("  §7" + (i++) + ". §f" + b.name() + " §8(§7" + b.schematic()
                            + ", d" + b.difficulty() + ", x" + b.weight() + "§8)");
            }
            case "clearbuilds" -> { arena.clearBuilds(); sender.sendMessage("§a[SpeedBuild] Alle builds gewist."); }
            case "status" -> {
                sender.sendMessage("§e§lSpeed Build — Status");
                sender.sendMessage("§7Klaar: " + (arena.isReady() ? "§aja ✓" : "§cnee ✗"));
                sender.sendMessage("§7Builds: §f" + arena.getBuilds().size() + "/10");
                if (!arena.isReady()) arena.issues().forEach(x -> sender.sendMessage("  §c- " + x));
                sender.sendMessage("§7Status: §f" + (gm != null ? gm.getState() : "n/a"));
            }
            default -> sender.sendMessage("§cOnbekend subcommando.");
        }
        return true;
    }

    private void player(CommandSender s, java.util.function.Consumer<Player> a) {
        if (s instanceof Player p) a.accept(p); else s.sendMessage("§cAlleen spelers.");
    }
    private int parseInt(String s, int d)      { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }
    private double parseDouble(String s, double d) { try { return Double.parseDouble(s); } catch (Exception e) { return d; } }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        return List.of();
    }
}
