package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/** /kmcstats [player] — full stats (no coins). */
public class StatsCommand implements CommandExecutor {

    private final KMCCore plugin;
    public StatsCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.stats")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }

        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) { sender.sendMessage(MessageUtil.get("player-not-found").replace("{player}", args[0])); return true; }
        } else if (sender instanceof Player p) target = p;
        else { sender.sendMessage("Usage: /kmcstats <player>"); return true; }

        PlayerData pd = plugin.getPlayerDataManager().getOrCreate(target.getUniqueId(), target.getName());
        KMCTeam team  = plugin.getTeamManager().getTeamByPlayer(target.getUniqueId());

        int mins = pd.getTotalPlayTimeMinutes();
        String playTime = (mins >= 60) ? (mins / 60) + "u " + (mins % 60) + "m" : mins + "m";

        String fav = pd.getFavouriteGame();
        String favDisplay = fav != null ? fav.replace("_", " ") : "Geen";

        sender.sendMessage(MessageUtil.color("&6══════ &eStats: &6" + target.getName() + " &6══════"));
        if (team != null)
            sender.sendMessage(MessageUtil.color("&7Team:          " + team.getColor() + team.getDisplayName()));
        else
            sender.sendMessage(MessageUtil.color("&7Team:          &8Geen"));
        sender.sendMessage(MessageUtil.color("&6── Huidig toernooi ──"));
        sender.sendMessage(MessageUtil.color("&7Punten:        &e" + pd.getPoints()));
        sender.sendMessage(MessageUtil.color("&7Kills:         &c" + pd.getKills()));
        sender.sendMessage(MessageUtil.color("&7Wins:          &a" + pd.getWins()));
        sender.sendMessage(MessageUtil.color("&6── Levenslange stats ──"));
        sender.sendMessage(MessageUtil.color("&7Games gespeeld: &e" + pd.getGamesPlayed()));
        sender.sendMessage(MessageUtil.color("&7Speeltijd:     &e" + playTime));
        sender.sendMessage(MessageUtil.color("&7Win streak:    &a" + pd.getWinStreak() + " &7(best: &a" + pd.getBestWinStreak() + "&7)"));
        sender.sendMessage(MessageUtil.color("&7Favo game:     &b" + favDisplay));
        if (!pd.getWinsPerGame().isEmpty()) {
            sender.sendMessage(MessageUtil.color("&6── Wins per game ──"));
            pd.getWinsPerGame().entrySet().stream()
                    .sorted((a,b) -> b.getValue() - a.getValue())
                    .forEach(e -> sender.sendMessage(MessageUtil.color(
                            "  &7" + e.getKey().replace("_", " ") + ": &a" + e.getValue())));
        }
        sender.sendMessage(MessageUtil.color("&6════════════════════════════════"));
        return true;
    }
}
