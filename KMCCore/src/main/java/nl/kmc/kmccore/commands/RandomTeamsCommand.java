package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.TeamManager;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;

/**
 * /kmcrandomteams [all|new] [confirm]
 *
 * <p>Distributes online players evenly across teams.
 *
 * <p><b>Fixes in this version:</b>
 * <ul>
 *   <li>Wrapped the whole run in try/catch — any exception is now
 *       reported to the sender instead of bubbling as "Unhandled
 *       exception executing command".</li>
 *   <li>Null-safe on the teams collection and on players.</li>
 *   <li>Teleport the assigned player to the lobby in adventure mode
 *       immediately after a successful team add (matching the
 *       "when you get added to a team, go to lobby" behaviour).</li>
 * </ul>
 */
public class RandomTeamsCommand implements CommandExecutor {

    private final KMCCore plugin;
    private final Random  random = new Random();

    public RandomTeamsCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.team.admin")) {
            sender.sendMessage(MessageUtil.get("no-permission"));
            return true;
        }

        // Wrap everything so an unexpected NPE doesn't become an
        // "Unhandled exception executing command" in the log.
        try {
            return runCommand(sender, args);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "RandomTeams failed", e);
            sender.sendMessage(MessageUtil.color(
                    "&c✘ RandomTeams fout: " + e.getClass().getSimpleName()
                    + ": " + (e.getMessage() != null ? e.getMessage() : "null")));
            sender.sendMessage(MessageUtil.color(
                    "&7Details staan in de server console."));
            return true;
        }
    }

    private boolean runCommand(CommandSender sender, String[] args) {
        String mode = args.length >= 1 ? args[0].toLowerCase() : "new";
        boolean confirmed = args.length >= 2 && args[1].equalsIgnoreCase("confirm");

        if (mode.equals("all") && !confirmed) {
            sender.sendMessage(MessageUtil.color("&c⚠ Dit wist ALLE team toewijzingen."));
            sender.sendMessage(MessageUtil.color("&7Typ &e/kmcrandomteams all confirm &7om door te gaan."));
            return true;
        }

        Collection<KMCTeam> teams = plugin.getTeamManager().getAllTeams();
        if (teams == null || teams.isEmpty()) {
            sender.sendMessage(MessageUtil.color(
                    "&c✘ Geen teams gedefinieerd in config.yml!"));
            return true;
        }

        if (mode.equals("all")) {
            for (KMCTeam t : new ArrayList<>(teams)) {
                if (t == null || t.getMembers() == null) continue;
                List<UUID> members = new ArrayList<>(t.getMembers());
                for (UUID uuid : members) {
                    if (uuid != null) plugin.getTeamManager().removePlayerFromTeam(uuid);
                }
            }
            sender.sendMessage(MessageUtil.color("&7Alle teams gewist."));
        }

        // Collect online players without a team
        List<Player> toAssign = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            if (plugin.getTeamManager().getTeamByPlayer(p.getUniqueId()) == null) {
                plugin.getPlayerDataManager().getOrCreate(p.getUniqueId(), p.getName());
                toAssign.add(p);
            }
        }

        if (toAssign.isEmpty()) {
            sender.sendMessage(MessageUtil.color(
                    "&eGeen spelers om toe te wijzen (iedereen zit al in een team)."));
            return true;
        }

        Collections.shuffle(toAssign, random);

        int maxPerTeam    = plugin.getTeamManager().getMaxPlayersPerTeam();
        int assignedCount = 0;
        int skippedFull   = 0;

        for (Player p : toAssign) {
            KMCTeam target = findSmallestTeamWithRoom(maxPerTeam);
            if (target == null) { skippedFull++; continue; }

            TeamManager.AddResult r = plugin.getTeamManager()
                    .addPlayerToTeam(p.getUniqueId(), target.getId());

            if (r == TeamManager.AddResult.OK) {
                assignedCount++;
                p.sendMessage(MessageUtil.get("team.join-message")
                        .replace("{team}", target.getDisplayName()));
                // Teleport the player to lobby in adventure mode
                plugin.getTeamManager().sendPlayerToLobby(p);
            }
        }

        sender.sendMessage(MessageUtil.color(
                "&a✔ " + assignedCount + " spelers verdeeld over teams."));
        if (skippedFull > 0) {
            sender.sendMessage(MessageUtil.color(
                    "&c" + skippedFull + " spelers konden niet geplaatst worden (alle teams vol)."));
        }

        // Refresh UI after bulk changes
        try {
            plugin.getTabListManager().refreshAllNametags();
            plugin.getTabListManager().refreshAll();
            plugin.getScoreboardManager().refreshAll();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "UI refresh after RandomTeams failed", e);
        }
        return true;
    }

    private KMCTeam findSmallestTeamWithRoom(int maxPerTeam) {
        KMCTeam smallest = null;
        int smallestSize = Integer.MAX_VALUE;
        for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
            if (t == null) continue;
            int size = t.getMemberCount();
            if (size < maxPerTeam && size < smallestSize) {
                smallest = t;
                smallestSize = size;
            }
        }
        return smallest;
    }
}
