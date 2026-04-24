package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;

import java.util.List;

/** /kmclb <teams|players> [page] */
public class LeaderboardCommand implements CommandExecutor {

    private static final int PAGE_SIZE = 10;
    private final KMCCore plugin;
    public LeaderboardCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.leaderboard")) { sender.sendMessage(MessageUtil.get("no-permission")); return true; }

        String type = args.length >= 1 ? args[0].toLowerCase() : "teams";
        int page    = args.length >= 2 ? parseInt(args[1]) : 1;
        if (page < 1) page = 1;

        if (type.equals("teams")) {
            List<KMCTeam> list = plugin.getTeamManager().getTeamsSortedByPoints();
            sender.sendMessage(MessageUtil.get("leaderboard.teams-header").replace("{page}", String.valueOf(page)));
            int start = (page - 1) * PAGE_SIZE;
            if (start >= list.size()) { sender.sendMessage(MessageUtil.get("leaderboard.page-end")); return true; }
            for (int i = start; i < Math.min(start + PAGE_SIZE, list.size()); i++) {
                KMCTeam t = list.get(i);
                sender.sendMessage(MessageUtil.get("leaderboard.team-entry")
                        .replace("{rank}", String.valueOf(i + 1))
                        .replace("{team_color}", t.getColor().toString())
                        .replace("{team_name}", t.getDisplayName())
                        .replace("{points}", String.valueOf(t.getPoints())));
            }
        } else {
            List<PlayerData> list = plugin.getPlayerDataManager().getLeaderboard();
            sender.sendMessage(MessageUtil.get("leaderboard.players-header").replace("{page}", String.valueOf(page)));
            int start = (page - 1) * PAGE_SIZE;
            if (start >= list.size()) { sender.sendMessage(MessageUtil.get("leaderboard.page-end")); return true; }
            for (int i = start; i < Math.min(start + PAGE_SIZE, list.size()); i++) {
                PlayerData pd = list.get(i);
                sender.sendMessage(MessageUtil.get("leaderboard.player-entry")
                        .replace("{rank}", String.valueOf(i + 1))
                        .replace("{player}", pd.getName())
                        .replace("{points}", String.valueOf(pd.getPoints())));
            }
        }
        return true;
    }

    private int parseInt(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; } }
}
