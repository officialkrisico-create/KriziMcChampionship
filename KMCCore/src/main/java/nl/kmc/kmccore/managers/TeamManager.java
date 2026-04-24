package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the 8 KMC teams.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load team definitions from config</li>
 *   <li>Add / remove players from teams</li>
 *   <li>Persist team point totals via DatabaseManager</li>
 *   <li>Maintain Bukkit Scoreboard {@link Team}s for coloured nametags</li>
 * </ul>
 */
public class TeamManager {

    private final KMCCore plugin;

    /** All registered teams, keyed by team ID. */
    private final Map<String, KMCTeam> teams = new LinkedHashMap<>();

    /** Bukkit scoreboard used only for nametag colouring. */
    private Scoreboard nametag;

    // ----------------------------------------------------------------
    // Init
    // ----------------------------------------------------------------

    public TeamManager(KMCCore plugin) {
        this.plugin = plugin;
        loadTeamsFromConfig();
        setupNametagScoreboard();
        loadTeamDataFromDB();
    }

    // ----------------------------------------------------------------
    // Config loading
    // ----------------------------------------------------------------

    /**
     * Reads the {@code teams.list} block from config.yml and creates
     * {@link KMCTeam} instances for each entry.
     */
    private void loadTeamsFromConfig() {
        ConfigurationSection teamSection = plugin.getConfig().getConfigurationSection("teams.list");
        if (teamSection == null) {
            plugin.getLogger().warning("No teams found in config.yml under 'teams.list'!");
            return;
        }

        for (String key : teamSection.getKeys(false)) {
            ConfigurationSection tc = teamSection.getConfigurationSection(key);
            if (tc == null) continue;

            String displayName = tc.getString("display-name", key);
            String colorName   = tc.getString("color", "WHITE");
            String tagColor    = tc.getString("tag-color", "WHITE");

            ChatColor color;
            try {
                color = ChatColor.valueOf(colorName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid color '" + colorName + "' for team " + key + ". Defaulting to WHITE.");
                color = ChatColor.WHITE;
            }

            KMCTeam team = new KMCTeam(key, displayName, color, tagColor);
            teams.put(key, team);
        }

        plugin.getLogger().info("Loaded " + teams.size() + " teams.");
    }

    // ----------------------------------------------------------------
    // Scoreboard nametags
    // ----------------------------------------------------------------

    /**
     * Creates a Bukkit {@link Scoreboard} with one {@link Team} per
     * KMC team so that player nametags appear in the correct colour.
     *
     * <p>This scoreboard is sent to every player on join/team-change
     * via {@link #applyNametagScoreboard(Player)}.
     */
    private void setupNametagScoreboard() {
        ScoreboardManager sbm = Bukkit.getScoreboardManager();
        nametag = sbm.getNewScoreboard();

        for (KMCTeam t : teams.values()) {
            Team bt = nametag.registerNewTeam("kmc_" + t.getId());

            // Build a net.kyori.adventure.text prefix using the team colour
            bt.setPrefix(t.getColor().toString());
            bt.setSuffix(ChatColor.RESET.toString());
            bt.setCanSeeFriendlyInvisibles(true);
            bt.setAllowFriendlyFire(false);
        }
    }

    /**
     * Applies the nametag scoreboard to an online player and adds them
     * to the correct {@link Team} entry.
     */
    public void applyNametagScoreboard(Player player) {
        player.setScoreboard(nametag);

        KMCTeam team = getTeamByPlayer(player.getUniqueId());
        if (team == null) return;

        Team bt = nametag.getTeam("kmc_" + team.getId());
        if (bt != null) bt.addEntry(player.getName());
    }

    // ----------------------------------------------------------------
    // Database load
    // ----------------------------------------------------------------

    private void loadTeamDataFromDB() {
        Map<String, KMCTeam> saved = plugin.getDatabaseManager().loadAllTeams();

        for (Map.Entry<String, KMCTeam> entry : saved.entrySet()) {
            KMCTeam live = teams.get(entry.getKey());
            if (live != null) {
                live.setPoints(entry.getValue().getPoints());
                live.setWins(entry.getValue().getWins());
            }
        }

        // Also restore member assignments from player data
        for (PlayerData pd : plugin.getDatabaseManager().loadAllPlayers()) {
            if (pd.hasTeam()) {
                KMCTeam t = teams.get(pd.getTeamId());
                if (t != null) t.addMember(pd.getUuid());
            }
        }
    }

    // ----------------------------------------------------------------
    // Public API – member management
    // ----------------------------------------------------------------

    /**
     * Adds a player to a team.
     *
     * @param uuid   player UUID
     * @param teamId team ID
     * @return result code; one of: OK, ALREADY_IN_TEAM, TEAM_FULL, TEAM_NOT_FOUND
     */
    public AddResult addPlayerToTeam(UUID uuid, String teamId) {
        KMCTeam target = teams.get(teamId);
        if (target == null) return AddResult.TEAM_NOT_FOUND;

        int maxPlayers = plugin.getConfig().getInt("teams.max-players-per-team", 4);
        if (target.getMemberCount() >= maxPlayers) return AddResult.TEAM_FULL;

        // Check player isn't already in another team
        if (getTeamByPlayer(uuid) != null) return AddResult.ALREADY_IN_TEAM;

        target.addMember(uuid);

        // Update PlayerData
        PlayerData pd = plugin.getPlayerDataManager().getOrCreate(uuid, null);
        pd.setTeamId(teamId);
        plugin.getDatabaseManager().savePlayer(pd);

        // Update nametag for all online players (needed for prefixes to propagate)
        if (plugin.getTabListManager() != null) {
            plugin.getTabListManager().refreshAllNametags();
            plugin.getTabListManager().refreshAll();
        }

        return AddResult.OK;
    }

    /**
     * Removes a player from their current team.
     *
     * @param uuid player UUID
     * @return {@code false} if player was not in any team
     */
    public boolean removePlayerFromTeam(UUID uuid) {
        KMCTeam team = getTeamByPlayer(uuid);
        if (team == null) return false;

        team.removeMember(uuid);

        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd != null) {
            pd.setTeamId(null);
            plugin.getDatabaseManager().savePlayer(pd);
        }

        // Refresh nametags for all players
        if (plugin.getTabListManager() != null) {
            plugin.getTabListManager().refreshAllNametags();
            plugin.getTabListManager().refreshAll();
        }

        return true;
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public KMCTeam getTeam(String id) {
        return teams.get(id.toLowerCase());
    }

    /** Returns the team a player belongs to, or {@code null}. */
    public KMCTeam getTeamByPlayer(UUID uuid) {
        for (KMCTeam t : teams.values()) {
            if (t.hasMember(uuid)) return t;
        }
        return null;
    }

    /** Returns all teams sorted by points descending. */
    public List<KMCTeam> getTeamsSortedByPoints() {
        return teams.values().stream()
                .sorted(Comparator.comparingInt(KMCTeam::getPoints).reversed())
                .collect(Collectors.toList());
    }

    public Collection<KMCTeam> getAllTeams() {
        return Collections.unmodifiableCollection(teams.values());
    }

    /** Returns the nametag scoreboard (used by TabListManager). */
    public Scoreboard getNametagScoreboard() { return nametag; }

    public int getMaxPlayersPerTeam() {
        return plugin.getConfig().getInt("teams.max-players-per-team", 4);
    }

    // ----------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------

    /** Saves all team point / win totals to DB. */
    public void saveAll() {
        for (KMCTeam t : teams.values()) {
            plugin.getDatabaseManager().saveTeam(t);
        }
    }

    // ----------------------------------------------------------------
    // Team chat
    // ----------------------------------------------------------------

    /**
     * Broadcasts a message to all online members of a team.
     *
     * @param sender  the sending player
     * @param message raw message text
     */
    public void sendTeamChat(Player sender, String message) {
        KMCTeam team = getTeamByPlayer(sender.getUniqueId());
        if (team == null) {
            sender.sendMessage(MessageUtil.get("team.no-team"));
            return;
        }

        String format = plugin.getConfig().getString("teams.chat-prefix-format",
                "&8[{team_color}{team_name}&8] &r{player_name}&7: {message}");

        String formatted = format
                .replace("{team_color}",  team.getColor().toString())
                .replace("{team_name}",   team.getDisplayName())
                .replace("{player_name}", sender.getName())
                .replace("{message}",     message);

        String coloured = ChatColor.translateAlternateColorCodes('&', formatted);

        for (UUID memberUuid : team.getMembers()) {
            Player member = Bukkit.getPlayer(memberUuid);
            if (member != null) member.sendMessage(coloured);
        }
    }

    // ----------------------------------------------------------------
    // Reset
    // ----------------------------------------------------------------

    /** Clears all team scores (used on tournament reset). */
    public void resetScores() {
        for (KMCTeam t : teams.values()) {
            t.setPoints(0);
            t.setWins(0);
        }
        saveAll();
    }

    // ----------------------------------------------------------------
    // Inner enum
    // ----------------------------------------------------------------

    public enum AddResult {
        OK, ALREADY_IN_TEAM, TEAM_FULL, TEAM_NOT_FOUND
    }
}
