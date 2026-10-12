package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.npc.NPCManager;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /kmcnpc <create|remove|list> [type] [fancyNpcId]
 *
 * <p>Create a leaderboard hologram at your location. Optionally link
 * with a FancyNpcs NPC so the hologram appears above it.
 *
 * <p>Types: top_teams, top_players, current_game, multiplier
 */
public class NPCCommand implements CommandExecutor {

    private final KMCCore plugin;
    public NPCCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.npc.admin")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { usage(sender); return true; }
                NPCManager.NpcType type = NPCManager.NpcType.fromString(args[1]);
                if (type == null) { sender.sendMessage(MessageUtil.get("npc.invalid-type")); return true; }
                String fancyId = args.length >= 3 ? args[2] : null;
                plugin.getNpcManager().createNpc(type, player.getLocation().add(0, 2, 0), fancyId);
                sender.sendMessage(MessageUtil.get("npc.created").replace("{type}", args[1]));
                if (fancyId != null) {
                    sender.sendMessage(MessageUtil.color("&7Gekoppeld aan FancyNpcs ID: &e" + fancyId));
                }
            }
            case "remove" -> {
                if (args.length < 2) { usage(sender); return true; }
                if (!plugin.getNpcManager().removeNpc(args[1]))
                    sender.sendMessage(MessageUtil.get("npc.not-found"));
                else sender.sendMessage(MessageUtil.get("npc.removed"));
            }
            case "list" -> {
                sender.sendMessage(MessageUtil.get("npc.list-header"));
                for (NPCManager.KmcNpc n : plugin.getNpcManager().getAllNpcs()) {
                    String loc = n.location.getBlockX() + "," + n.location.getBlockY() + "," + n.location.getBlockZ();
                    String line = MessageUtil.get("npc.list-entry")
                            .replace("{id}", n.id)
                            .replace("{type}", n.type.name().toLowerCase())
                            .replace("{location}", loc);
                    if (n.fancyNpcId != null) line += MessageUtil.color(" &8[FancyNpc: " + n.fancyNpcId + "]");
                    sender.sendMessage(line);
                }
            }
            case "rank" -> handleRank(sender, args);
            case "presenter" -> handlePresenter(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    /**
     * /kmcnpc rank set <1-10> | own | remove <1-10|own|all> | list | refresh
     * Stand where the NPC should be: a mannequin with the skin of the player at that rank appears there.
     */
    private void handleRank(CommandSender sender, String[] args) {
        var rm = plugin.getRankNpcManager();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
        switch (sub) {
            case "set" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                int n = args.length >= 3 ? parse(args[2]) : -1;
                if (n < 1 || n > nl.kmc.kmccore.npc.RankNpcManager.MAX_SLOT) {
                    sender.sendMessage(MessageUtil.color("&cGebruik: /kmcnpc rank set <1-10>")); return;
                }
                rm.setSlot(n, p.getLocation());
                sender.sendMessage(MessageUtil.color("&a[KMC] Plek &e#" + n + " &aingesteld op jouw positie."));
            }
            case "own" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                rm.setOwnSpot(p.getLocation().add(0, 2, 0));
                sender.sendMessage(MessageUtil.color("&a[KMC] \"Jouw plek\"-weergave ingesteld (elke speler ziet zijn eigen positie)."));
            }
            case "remove" -> {
                String what = args.length >= 3 ? args[2].toLowerCase() : "";
                boolean ok;
                if (what.equals("all")) { rm.removeAllSlots(); ok = true; }
                else if (what.equals("own")) ok = rm.removeOwnSpot();
                else ok = rm.removeSlot(parse(what));
                sender.sendMessage(MessageUtil.color(ok ? "&a[KMC] Verwijderd." : "&7Niet gevonden."));
            }
            case "refresh" -> { rm.refresh(); sender.sendMessage(MessageUtil.color("&a[KMC] Ververst.")); }
            default -> {
                sender.sendMessage(MessageUtil.color("&6[KMC] Rank-NPC's: &e" + rm.getSlots().size() + "&7/10"
                        + " &8| &7eigen plek: " + (rm.getOwnSpot() != null ? "&aja" : "&7nee")));
                rm.getSlots().forEach((n, l) -> sender.sendMessage(MessageUtil.color(
                        "  &7#" + n + " &8— &f" + l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ())));
                sender.sendMessage(MessageUtil.color("&7/kmcnpc rank set <1-10> | own | remove <n|own|all> | refresh"));
            }
        }
    }

    /** /kmcnpc presenter set | remove | skin <speler> | say <tekst> */
    private void handlePresenter(CommandSender sender, String[] args) {
        var pm = plugin.getPresenter();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "set" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return; }
                pm.setSpot(p.getLocation());
                sender.sendMessage(MessageUtil.color("&a[KMC] Presentator staat nu op jouw positie. &7Skin: /kmcnpc presenter skin <speler>"));
            }
            case "remove" -> sender.sendMessage(MessageUtil.color(pm.remove() ? "&a[KMC] Presentator verwijderd." : "&7Er is geen presentator."));
            case "skin" -> {
                if (args.length < 3) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcnpc presenter skin <speler>")); return; }
                pm.setSkin(args[2]);
                sender.sendMessage(MessageUtil.color("&a[KMC] Skin van de presentator: &e" + args[2]));
            }
            case "say" -> {
                if (args.length < 3) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcnpc presenter say <tekst>")); return; }
                pm.say("presenter.custom", 8, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            }
            default -> sender.sendMessage(MessageUtil.color("&7/kmcnpc presenter set | remove | skin <speler> | say <tekst> &8(geplaatst: "
                    + (pm.isPlaced() ? "ja" : "nee") + ")"));
        }
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}",
                "/kmcnpc <create|remove|list> [top_teams|top_players|current_game|multiplier] [fancyNpcId]"));
    }
}
