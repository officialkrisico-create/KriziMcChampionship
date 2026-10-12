package nl.kmc.formula.commands;

import nl.kmc.formula.FormulaMCPlugin;
import nl.kmc.formula.models.Circuit;
import nl.kmc.formula.models.Gate;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /fmc — Formula MC control and circuit setup.
 *
 * <pre>
 * /fmc create <id> [name…]   new circuit (selected, in your current world)
 * /fmc delete <id>           remove a circuit
 * /fmc select <id>           circuit for the next race
 * /fmc list | info           overview of circuits / the selected one
 * /fmc laps <n>              number of laps
 * /fmc spawn add | clear     grid slots (yaw = the way the boat faces)
 * /fmc pos1 | pos2           select the corners of a gate (the block you stand in)
 * /fmc line                  selected gate = start/finish line
 * /fmc cp add | clear | list selected gate = next checkpoint (order matters)
 * /fmc reset                 safe reset spot for stuck racers
 * /fmc start | stop | status
 * /fmc cam                   flyover camera menu (arena-formula_mc)
 * </pre>
 */
public final class FmcCommand implements CommandExecutor, TabCompleter {

    private final FormulaMCPlugin plugin;

    public FmcCommand(FormulaMCPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("formula.admin")) { s.sendMessage("§cGeen toestemming."); return true; }
        if (a.length == 0) { help(s, label); return true; }
        var mgr = plugin.getCircuitManager();
        Circuit sel = mgr.get(mgr.getSelectedId());

        switch (a[0].toLowerCase()) {
            case "create" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                if (a.length < 2) { s.sendMessage("§cGebruik: /" + label + " create <id> [naam]"); return true; }
                String name = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : a[1];
                mgr.create(a[1], name, p.getWorld());
                s.sendMessage("§a[Formula MC] Circuit §e" + a[1] + "§a aangemaakt en geselecteerd. Volgende: /" + label + " spawn add");
            }
            case "delete" -> {
                if (a.length < 2) { s.sendMessage("§cGebruik: /" + label + " delete <id>"); return true; }
                s.sendMessage(mgr.delete(a[1]) ? "§a[Formula MC] Circuit verwijderd." : "§cOnbekend circuit.");
            }
            case "select" -> {
                if (a.length < 2 || mgr.get(a[1]) == null) { s.sendMessage("§cOnbekend circuit. /" + label + " list"); return true; }
                mgr.select(mgr.get(a[1]).getId());
                s.sendMessage("§a[Formula MC] Geselecteerd: §e" + a[1]);
            }
            case "list" -> {
                if (mgr.all().isEmpty()) { s.sendMessage("§7Geen circuits. Maak er een met /" + label + " create <id>"); return true; }
                for (Circuit c : mgr.all().values()) {
                    s.sendMessage((c == sel ? "§a▶ " : "§7  ") + "§e" + c.getId() + " §8— §f" + c.getDisplayName()
                            + " §8(" + c.getLaps() + " rondes, " + c.getSpawns().size() + " slots, "
                            + c.getCheckpoints().size() + " cp) " + (c.isReady() ? "§a✔" : "§c✘ " + c.problems()));
                }
            }
            case "info" -> {
                if (!need(s, sel, label)) return true;
                s.sendMessage("§b§lCircuit §e" + sel.getId() + " §7(" + sel.getDisplayName() + ")");
                s.sendMessage("§7Wereld: §f" + (sel.getWorld() != null ? sel.getWorld().getName() : "?")
                        + " §7| Rondes: §f" + sel.getLaps() + " §7| Slots: §f" + sel.getSpawns().size());
                s.sendMessage("§7Lijn: §f" + (sel.getLine() != null ? sel.getLine().describe() : "niet gezet"));
                s.sendMessage("§7Checkpoints: §f" + sel.getCheckpoints().size()
                        + " §7| Reset: §f" + (sel.getReset() != null ? "gezet" : "niet gezet"));
                s.sendMessage(sel.isReady() ? "§a✔ Klaar om te racen" : "§c✘ " + sel.problems());
            }
            case "laps" -> {
                if (!need(s, sel, label)) return true;
                try { sel.setLaps(Integer.parseInt(a[1])); mgr.save(); s.sendMessage("§a[Formula MC] Rondes: " + sel.getLaps()); }
                catch (Exception e) { s.sendMessage("§cGebruik: /" + label + " laps <aantal>"); }
            }
            case "spawn" -> {
                if (!need(s, sel, label) || !(s instanceof Player p)) return true;
                if (a.length >= 2 && a[1].equalsIgnoreCase("clear")) {
                    sel.getSpawns().clear(); mgr.save();
                    s.sendMessage("§a[Formula MC] Startposities gewist.");
                } else {
                    sel.getSpawns().add(p.getLocation().clone());
                    mgr.save();
                    s.sendMessage("§a[Formula MC] Slot #" + sel.getSpawns().size() + " toegevoegd (kijkrichting telt!).");
                }
            }
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                Location[] sels = mgr.selection(p.getUniqueId());
                sels[a[0].equalsIgnoreCase("pos1") ? 0 : 1] = p.getLocation().getBlock().getLocation();
                s.sendMessage("§a[Formula MC] " + a[0] + " gezet op het blok waar je staat.");
            }
            case "line" -> {
                if (!need(s, sel, label) || !(s instanceof Player p)) return true;
                Gate g = gateFromSelection(p);
                if (g == null) { s.sendMessage("§cZet eerst /" + label + " pos1 en pos2 (hoogte: reken ~3 blokken)."); return true; }
                sel.setLine(g); mgr.save();
                s.sendMessage("§a[Formula MC] Start/finish-lijn gezet: " + g.describe());
            }
            case "cp" -> {
                if (!need(s, sel, label) || !(s instanceof Player p)) return true;
                String sub = a.length >= 2 ? a[1].toLowerCase() : "list";
                switch (sub) {
                    case "add" -> {
                        Gate g = gateFromSelection(p);
                        if (g == null) { s.sendMessage("§cZet eerst /" + label + " pos1 en pos2."); return true; }
                        sel.getCheckpoints().add(g); mgr.save();
                        s.sendMessage("§a[Formula MC] Checkpoint #" + sel.getCheckpoints().size() + " toegevoegd.");
                    }
                    case "clear" -> { sel.getCheckpoints().clear(); mgr.save(); s.sendMessage("§a[Formula MC] Checkpoints gewist."); }
                    default -> {
                        int i = 1;
                        for (Gate g : sel.getCheckpoints()) s.sendMessage("§7#" + (i++) + " §f" + g.describe());
                        if (i == 1) s.sendMessage("§7Geen checkpoints.");
                    }
                }
            }
            case "reset" -> {
                if (!need(s, sel, label) || !(s instanceof Player p)) return true;
                sel.setReset(p.getLocation()); mgr.save();
                s.sendMessage("§a[Formula MC] Reset-locatie gezet.");
            }
            case "start" -> {
                var gm = plugin.getGameManagerV2();
                if (gm == null || !gm.start()) s.sendMessage("§c[Formula MC] Start mislukt (circuit niet klaar of er loopt al een game). /" + label + " list");
                else s.sendMessage("§a[Formula MC] Race gestart!");
            }
            case "stop" -> {
                var gm = plugin.getGameManagerV2();
                if (gm != null && gm.isRunning()) { gm.end(); s.sendMessage("§e[Formula MC] Race gestopt."); }
                else s.sendMessage("§7Er loopt geen race.");
            }
            case "status" -> {
                var gm = plugin.getGameManagerV2();
                s.sendMessage("§b[Formula MC] §7Status: §f" + (gm != null ? gm.getState() : "?")
                        + " §7| Racers: §f" + (gm != null ? gm.getRacers().size() : 0));
            }
            default -> help(s, label);
        }
        return true;
    }

    private boolean need(CommandSender s, Circuit sel, String label) {
        if (sel == null) { s.sendMessage("§cGeen circuit geselecteerd. /" + label + " create <id> of /" + label + " select <id>"); return false; }
        return true;
    }

    private Gate gateFromSelection(Player p) {
        Location[] sels = plugin.getCircuitManager().selection(p.getUniqueId());
        if (sels[0] == null || sels[1] == null) return null;
        // pos1/pos2 are at your feet: give the gate some height so a boat on ice (and a jump) is inside.
        Location a = sels[0].clone(), b = sels[1].clone();
        if (Math.abs(a.getBlockY() - b.getBlockY()) < 2) {
            int base = Math.min(a.getBlockY(), b.getBlockY());
            a.setY(base);
            b.setY(base + 3);
        }
        return new Gate(a, b);
    }

    private void help(CommandSender s, String l) {
        s.sendMessage("§b§lFormula MC §7— /" + l + " create|delete|select|list|info|laps|spawn|pos1|pos2|line|cp|reset|start|stop|status|cam");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return Arrays.asList("create", "delete", "select", "list", "info", "laps", "spawn", "pos1",
                "pos2", "line", "cp", "reset", "start", "stop", "status", "cam");
        if (a.length == 2 && (a[0].equalsIgnoreCase("select") || a[0].equalsIgnoreCase("delete")))
            return new ArrayList<>(plugin.getCircuitManager().all().keySet());
        if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) return Arrays.asList("add", "clear");
        if (a.length == 2 && a[0].equalsIgnoreCase("cp")) return Arrays.asList("add", "clear", "list");
        return List.of();
    }
}
