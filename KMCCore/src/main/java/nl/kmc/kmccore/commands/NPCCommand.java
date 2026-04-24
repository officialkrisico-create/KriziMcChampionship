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
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.get("invalid-usage").replace("{usage}",
                "/kmcnpc <create|remove|list> [top_teams|top_players|current_game|multiplier] [fancyNpcId]"));
    }
}
