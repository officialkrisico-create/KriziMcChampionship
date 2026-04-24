package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;

/**
 * Manages KMC teams.
 *
 * <p>Changes in this version:
 * <ul>
 *   <li>{@link #sendPlayerToLobby(Player)} — teleport after team add/remove</li>
 *   <li>{@link #createTeam(String, String, ChatColor, String)} — admin create</li>
 *   <li>{@link #deleteTeam(String)} — admin delete, kicks members to no-team</li>
 *   <li>{@link #sendTeamChat(Player, String)} — sends a message to every
 *       member of the player's team (restored — was used by TeamChatCommand)</li>
 *   <li>All public methods defensively null-check</li>
 *   <li>Team load order respected — used for color-sorted tablist</li>
 * </ul>
 */
public class TeamManager {

    public enum AddResult { OK, ALREADY_IN_TEAM, TEAM_FULL, TEAM_NOT_FOUND }

    private final KMCCore plugin;

    /** LinkedHashMap preserves insertion order — used for tablist sort order. */
    private final LinkedHashMap<String, KMCTeam> teams = new LinkedHashMap<>();

    public TeamManager(KMCCore plugin) {
        this.plugin = plugin;
        loadTeams();
    }

    // ----------------------------------------------------------------
    // Loading
    // ----------------------------------------------------------------

    private void loadTeams() {
        teams.clear();

        Map<String, KMCTeam> fromDb = plugin.getDatabaseManager().loadAllTeams();

        ConfigurationSection ts = plugin.getConfig().getConfigurationSection("teams.list");
        if (ts != null) {
            for (String id : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(id);
                if (t == null) continue;
                String displayName = t.getString("display-name", id);
                String tagColor    = t.getString("tag-color", "&7");
                ChatColor color;
                try { color = ChatColor.valueOf(t.getString("color", "WHITE").toUpperCase()); }
                catch (IllegalArgumentException e) { color = ChatColor.WHITE; }

                KMCTeam existing = fromDb.get(id);
                if (existing != null) {
                    teams.put(id, existing);
                } else {
                    KMCTeam fresh = new KMCTeam(id, displayName, color, tagColor);
                    teams.put(id, fresh);
                    plugin.getDatabaseManager().saveTeam(fresh);
                }
            }
        }

        for (Map.Entry<String, KMCTeam> e : fromDb.entrySet()) {
            teams.putIfAbsent(e.getKey(), e.getValue());
        }

        for (PlayerData pd : plugin.getDatabaseManager().loadAllPlayers()) {
            if (pd.getTeamId() != null && teams.containsKey(pd.getTeamId())) {
                teams.get(pd.getTeamId()).addMember(pd.getUuid());
            }
        }

        plugin.getLogger().info("Loaded " + teams.size() + " teams.");
    }

    public void saveAll() {
        for (KMCTeam t : teams.values()) plugin.getDatabaseManager().saveTeam(t);
    }

    // ----------------------------------------------------------------
    // Membership
    // ----------------------------------------------------------------

    public AddResult addPlayerToTeam(UUID uuid, String teamId) {
        if (uuid == null || teamId == null) return AddResult.TEAM_NOT_FOUND;

        KMCTeam target = teams.get(teamId);
        if (target == null) return AddResult.TEAM_NOT_FOUND;

        int maxPlayers = plugin.getConfig().getInt("teams.max-players-per-team", 4);
        if (target.getMemberCount() >= maxPlayers) return AddResult.TEAM_FULL;
        if (getTeamByPlayer(uuid) != null)         return AddResult.ALREADY_IN_TEAM;

        target.addMember(uuid);

        PlayerData pd = plugin.getPlayerDataManager().getOrCreate(uuid, null);
        pd.setTeamId(teamId);
        plugin.getDatabaseManager().savePlayer(pd);
        plugin.getDatabaseManager().saveTeam(target);

        safeRefreshAllUi();

        Player online = Bukkit.getPlayer(uuid);
        if (online != null) sendPlayerToLobby(online);

        return AddResult.OK;
    }

    public boolean removePlayerFromTeam(UUID uuid) {
        if (uuid == null) return false;

        KMCTeam team = getTeamByPlayer(uuid);
        if (team == null) return false;

        team.removeMember(uuid);
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd != null) {
            pd.setTeamId(null);
            plugin.getDatabaseManager().savePlayer(pd);
        }
        plugin.getDatabaseManager().saveTeam(team);

        safeRefreshAllUi();
        return true;
    }

    // ----------------------------------------------------------------
    // Team chat (restored)
    // ----------------------------------------------------------------

    /**
     * Sends a team-chat message to every online member of the sender's team.
     * Used by {@code TeamChatCommand}.
     *
     * @param sender  the sending player
     * @param message raw message text
     */
    public void sendTeamChat(Player sender, String message) {
        if (sender == null || message == null) return;

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
    // Lobby teleport
    // ----------------------------------------------------------------

    /**
     * Sends a player to the KMC lobby in adventure mode, full HP/food.
     * No-op if the lobby isn't set or if a game is currently active.
     */
    public void sendPlayerToLobby(Player player) {
        if (player == null || !player.isOnline()) return;
        if (plugin.getGameManager().isGameActive()) return;

        var lobby = plugin.getArenaManager().getLobby();
        if (lobby == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            player.teleport(lobby);
            player.setGameMode(GameMode.ADVENTURE);
            player.setHealth(20);
            player.setFoodLevel(20);
        });
    }

    // ----------------------------------------------------------------
    // Create / delete teams at runtime
    // ----------------------------------------------------------------

    public KMCTeam createTeam(String id, String displayName, ChatColor color, String tagColor) {
        if (id == null) return null;
        String key = id.toLowerCase();
        if (teams.containsKey(key)) return null;

        KMCTeam t = new KMCTeam(key, displayName, color, tagColor);
        teams.put(key, t);
        plugin.getDatabaseManager().saveTeam(t);

        try {
            String path = "teams.list." + key;
            plugin.getConfig().set(path + ".display-name", displayName);
            plugin.getConfig().set(path + ".color", color.name());
            plugin.getConfig().set(path + ".tag-color", tagColor);
            plugin.saveConfig();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't persist new team to config", e);
        }

        safeRefreshAllUi();
        return t;
    }

    public boolean deleteTeam(String id) {
        if (id == null) return false;
        String key = id.toLowerCase();
        KMCTeam t = teams.get(key);
        if (t == null) return false;

        for (UUID uuid : new ArrayList<>(t.getMembers())) {
            removePlayerFromTeam(uuid);
        }

        teams.remove(key);

        try {
            plugin.getConfig().set("teams.list." + key, null);
            plugin.saveConfig();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't remove team from config", e);
        }

        try {
            plugin.getDatabaseManager().deleteTeam(key);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't remove team from DB", e);
        }

        safeRefreshAllUi();
        return true;
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public KMCTeam getTeam(String id) {
        if (id == null) return null;
        return teams.get(id.toLowerCase());
    }

    public Collection<KMCTeam> getAllTeams() {
        return Collections.unmodifiableCollection(teams.values());
    }

    public KMCTeam getTeamByPlayer(UUID uuid) {
        if (uuid == null) return null;
        for (KMCTeam t : teams.values()) {
            if (t.hasMember(uuid)) return t;
        }
        return null;
    }

    public List<KMCTeam> getTeamsSortedByPoints() {
        List<KMCTeam> list = new ArrayList<>(teams.values());
        list.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        return list;
    }

    /** Teams in insertion / config order — used for tablist sort. */
    public List<KMCTeam> getTeamsInOrder() {
        return new ArrayList<>(teams.values());
    }

    public int getMaxPlayersPerTeam() {
        return plugin.getConfig().getInt("teams.max-players-per-team", 4);
    }

    public int getTeamCount() { return teams.size(); }

    // ----------------------------------------------------------------
    // Bulk
    // ----------------------------------------------------------------

    public void resetScores() {
        for (KMCTeam t : teams.values()) {
            t.setPoints(0);
            t.setWins(0);
            plugin.getDatabaseManager().saveTeam(t);
        }
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private void safeRefreshAllUi() {
        try {
            if (plugin.getTabListManager() != null) {
                plugin.getTabListManager().refreshAllNametags();
                plugin.getTabListManager().refreshAll();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "TabList refresh failed", e);
        }
        try {
            if (plugin.getScoreboardManager() != null) {
                plugin.getScoreboardManager().refreshAll();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Sidebar refresh failed", e);
        }
    }
}