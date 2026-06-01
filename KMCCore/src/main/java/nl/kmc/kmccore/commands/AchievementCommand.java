package nl.kmc.kmccore.commands;

import nl.kmc.core.api.AchievementApi;
import nl.kmc.core.api.KMCApiProvider;
import nl.kmc.core.domain.AchievementDefinition;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /kmcachievements — admin management of the achievement system.
 *
 * <pre>
 * /kmcachievements list [player]          — list unlocked achievements
 * /kmcachievements progress <player>      — show progress counters
 * /kmcachievements grant <player> <id>    — manually unlock achievement
 * /kmcachievements revoke <player> <id>   — remove unlock (keeps DB record)
 * /kmcachievements reset <player>         — clear all unlocks for player (dangerous)
 * /kmcachievements reload                 — reload YAML definitions
 * /kmcachievements info <id>              — show definition details
 * </pre>
 */
public final class AchievementCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "kmc.admin";

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) { sendHelp(sender); return true; }

        AchievementApi api = api();
        if (api == null) {
            sender.sendMessage("§cAchievement system is not initialised.");
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "list"     -> cmdList(sender, args, api);
            case "progress" -> cmdProgress(sender, args, api);
            case "grant"    -> cmdGrant(sender, args, api);
            case "revoke"   -> cmdRevoke(sender, args, api);
            case "reset"    -> cmdReset(sender, args, api);
            case "reload"   -> cmdReload(sender, api);
            case "info"     -> cmdInfo(sender, args, api);
            default         -> { sendHelp(sender); yield true; }
        };
    }

    private boolean cmdList(CommandSender sender, String[] args, AchievementApi api) {
        Player target = resolvePlayer(sender, args, 1);
        if (target == null) return true;

        Set<String> ids = api.getUnlocked(target.getUniqueId());
        sender.sendMessage("§6§l" + target.getName() + "'s achievements §7(" + ids.size() + "):");
        if (ids.isEmpty()) { sender.sendMessage("§7  None unlocked."); return true; }

        for (String id : ids) {
            AchievementDefinition def = api.get(id);
            String name = def != null ? def.getName() : id;
            String rar  = def != null ? def.getRarity().getLabel() : "§8Unknown";
            sender.sendMessage("  " + rar + " §f" + name + " §8[" + id + "]");
        }
        return true;
    }

    private boolean cmdProgress(CommandSender sender, String[] args, AchievementApi api) {
        Player target = resolvePlayer(sender, args, 1);
        if (target == null) return true;

        UUID uuid = target.getUniqueId();
        sender.sendMessage("§6Progress for §f" + target.getName() + "§6:");
        boolean any = false;
        for (AchievementDefinition def : api.getAll()) {
            if (!def.isProgressBased()) continue;
            int cur = api.getProgress(uuid, def.getId());
            if (cur == 0) continue;
            any = true;
            boolean done = api.has(uuid, def.getId());
            String bar = done ? "§a[DONE]" : "§e" + cur + "/" + def.getProgressTarget();
            sender.sendMessage("  §7" + def.getName() + ": " + bar);
        }
        if (!any) sender.sendMessage("§7  No progress yet.");
        return true;
    }

    private boolean cmdGrant(CommandSender sender, String[] args, AchievementApi api) {
        if (args.length < 3) { sender.sendMessage("§cUsage: /kmcachievements grant <player> <id>"); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage("§cPlayer not found: " + args[1]); return true; }
        String id = args[2];
        if (api.get(id) == null) { sender.sendMessage("§cUnknown achievement id: §f" + id); return true; }
        api.grant(target.getUniqueId(), id);
        sender.sendMessage("§aGranted §f" + id + " §ato §f" + target.getName());
        return true;
    }

    private boolean cmdRevoke(CommandSender sender, String[] args, AchievementApi api) {
        if (args.length < 3) { sender.sendMessage("§cUsage: /kmcachievements revoke <player> <id>"); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage("§cPlayer not found: " + args[1]); return true; }
        api.revoke(target.getUniqueId(), args[2]);
        sender.sendMessage("§eRevoked §f" + args[2] + " §efrom §f" + target.getName() + " §8(DB record kept)");
        return true;
    }

    private boolean cmdReset(CommandSender sender, String[] args, AchievementApi api) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /kmcachievements reset <player>"); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage("§cPlayer not found: " + args[1]); return true; }
        UUID uuid = target.getUniqueId();
        Set<String> toRevoke = new HashSet<>(api.getUnlocked(uuid));
        toRevoke.forEach(id -> api.revoke(uuid, id));
        sender.sendMessage("§cReset §f" + toRevoke.size() + " §cachievement(s) for §f" + target.getName());
        return true;
    }

    private boolean cmdReload(CommandSender sender, AchievementApi api) {
        api.reload();
        sender.sendMessage("§aAchievement definitions reloaded. §7" + api.getAll().size() + " loaded.");
        return true;
    }

    private boolean cmdInfo(CommandSender sender, String[] args, AchievementApi api) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /kmcachievements info <id>"); return true; }
        AchievementDefinition def = api.get(args[1]);
        if (def == null) { sender.sendMessage("§cUnknown achievement: " + args[1]); return true; }
        sender.sendMessage("§6§l" + def.getName());
        sender.sendMessage("  §7ID: §f"          + def.getId());
        sender.sendMessage("  §7Category: §f"    + def.getCategory().getDisplayName());
        sender.sendMessage("  §7Rarity: "        + def.getRarity().getLabel());
        sender.sendMessage("  §7Trigger: §f"     + def.getTrigger());
        sender.sendMessage("  §7Hidden: §f"      + def.isHidden());
        if (def.isProgressBased())
            sender.sendMessage("  §7Target: §f"  + def.getProgressTarget());
        if (def.getScopeGameId() != null)
            sender.sendMessage("  §7Game scope: §f" + def.getScopeGameId());
        sender.sendMessage("  §7Description: §f" + def.getDescription());
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§6/kmcachievements §7— Achievement admin");
        s.sendMessage("  §flist [player] §7— show unlocked");
        s.sendMessage("  §fprogress <player> §7— show progress");
        s.sendMessage("  §fgrant <player> <id> §7— unlock");
        s.sendMessage("  §frevoke <player> <id> §7— remove unlock");
        s.sendMessage("  §freset <player> §7— clear all unlocks");
        s.sendMessage("  §freload §7— reload YAML definitions");
        s.sendMessage("  §finfo <id> §7— show definition");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission(PERM)) return List.of();
        if (args.length == 1) return List.of("list", "progress", "grant", "revoke", "reset", "reload", "info");
        AchievementApi api = api();

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (Set.of("list", "progress", "grant", "revoke", "reset").contains(sub)) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            }
            if ("info".equals(sub) && api != null) {
                return api.getAll().stream().map(AchievementDefinition::getId).toList();
            }
        }
        if (args.length == 3 && Set.of("grant", "revoke").contains(args[0].toLowerCase()) && api != null) {
            return api.getAll().stream().map(AchievementDefinition::getId).toList();
        }
        return List.of();
    }

    private Player resolvePlayer(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Player p = Bukkit.getPlayer(args[idx]);
            if (p == null) { sender.sendMessage("§cPlayer not online: " + args[idx]); return null; }
            return p;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage("§cSpecify a player name.");
        return null;
    }

    private AchievementApi api() {
        try { return KMCApiProvider.get().achievements(); }
        catch (Exception e) { return null; }
    }
}
