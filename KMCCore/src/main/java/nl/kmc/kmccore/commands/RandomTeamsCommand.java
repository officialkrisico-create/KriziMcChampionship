package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.TeamManager;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /kmcrandomteams [all|new] [confirm]
 *
 * <p>Distributes online players across teams evenly.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code new}     (default) — only places players NOT currently in a team.
 *       Manual assignments are preserved.</li>
 *   <li>{@code all}     — wipes all teams first, then redistributes everyone
 *       (destructive — requires {@code confirm} arg).</li>
 * </ul>
 *
 * <p>Distribution: round-robin over teams sorted by current member count
 * (ascending), so smaller teams fill up first. This gives even counts
 * even with only 8 players and 8 teams (1 per team), or 20 players
 * (2-3 per team).
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

        String mode = args.length >= 1 ? args[0].toLowerCase() : "new";
        boolean confirmed = args.length >= 2 && args[1].equalsIgnoreCase("confirm");

        if (mode.equals("all") && !confirmed) {
            sender.sendMessage(MessageUtil.color(
                    "&c⚠ Dit wist ALLE team toewijzingen."));
            sender.sendMessage(MessageUtil.color(
                    "&7Typ &e/kmcrandomteams all confirm &7om door te gaan."));
            return true;
        }

        // If "all" mode: wipe all members first
        if (mode.equals("all")) {
            for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
                // Copy list to avoid concurrent modification
                List<UUID> members = new ArrayList<>(t.getMembers());
                for (UUID uuid : members) {
                    plugin.getTeamManager().removePlayerFromTeam(uuid);
                }
            }
            sender.sendMessage(MessageUtil.color("&7Alle teams gewist."));
        }

        // Collect eligible online players — those without a team
        List<Player> toAssign = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Skip exempt players (optional — e.g. admins with a specific perm)
            if (p.hasPermission("kmc.team.exempt")) continue;

            if (plugin.getTeamManager().getTeamByPlayer(p.getUniqueId()) == null) {
                // Ensure PlayerData exists for team assignment
                plugin.getPlayerDataManager().getOrCreate(p.getUniqueId(), p.getName());
                toAssign.add(p);
            }
        }

        if (toAssign.isEmpty()) {
            sender.sendMessage(MessageUtil.color("&eGeen spelers om toe te wijzen (iedereen zit al in een team)."));
            return true;
        }

        // Shuffle the player list so assignment is random
        Collections.shuffle(toAssign, random);

        int maxPerTeam  = plugin.getTeamManager().getMaxPlayersPerTeam();
        int assignedCount = 0;
        int skippedFull   = 0;

        // Round-robin: on each iteration pick the team with the FEWEST members
        // This produces even distribution regardless of player count.
        for (Player p : toAssign) {
            KMCTeam target = findSmallestTeamWithRoom(maxPerTeam);
            if (target == null) {
                skippedFull++;
                continue;
            }

            TeamManager.AddResult r =
                    plugin.getTeamManager().addPlayerToTeam(p.getUniqueId(), target.getId());

            if (r == TeamManager.AddResult.OK) {
                assignedCount++;
                p.sendMessage(MessageUtil.get("team.join-message")
                        .replace("{team}", target.getDisplayName()));
            }
        }

        // Report
        sender.sendMessage(MessageUtil.color(
                "&a✔ " + assignedCount + " spelers verdeeld over teams."));
        if (skippedFull > 0) {
            sender.sendMessage(MessageUtil.color(
                    "&c" + skippedFull + " spelers konden niet geplaatst worden (alle teams vol)."));
        }

        // Refresh nametags so prefixes show up immediately
        plugin.getTabListManager().refreshAllNametags();
        plugin.getTabListManager().refreshAll();
        plugin.getScoreboardManager().refreshAll();
        return true;
    }

    /**
     * Finds the team with the fewest current members that still has
     * room. Returns null if every team is at max capacity.
     */
    private KMCTeam findSmallestTeamWithRoom(int maxPerTeam) {
        KMCTeam smallest = null;
        int smallestSize = Integer.MAX_VALUE;
        for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
            int size = t.getMemberCount();
            if (size < maxPerTeam && size < smallestSize) {
                smallest = t;
                smallestSize = size;
            }
        }
        return smallest;
    }
}
