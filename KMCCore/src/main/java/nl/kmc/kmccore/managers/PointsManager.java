package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.models.PointAward;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * Calculates and awards all point rewards.
 *
 * <p>NEW: every awarded point is now logged to the {@code point_awards}
 * table via {@link nl.kmc.kmccore.database.DatabaseManager#recordPointAward}
 * so the post-event book can break down where each player got their points.
 */
public class PointsManager {

    private final KMCCore plugin;
    private File             pointsFile;
    private FileConfiguration pointsCfg;

    public PointsManager(KMCCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        pointsFile = new File(plugin.getDataFolder(), "points.yml");
        if (!pointsFile.exists()) plugin.saveResource("points.yml", false);
        pointsCfg = YamlConfiguration.loadConfiguration(pointsFile);
        plugin.getLogger().info("Loaded points.yml (kill=" + getPerKill() +
                                ", 1st place=" + getPlacementFirst() + ")");
    }

    public double getMultiplierForRound(int round) {
        return plugin.getConfig().getDouble("tournament.multipliers." + round, 1.0);
    }
    public double getCurrentMultiplier() {
        return getMultiplierForRound(plugin.getTournamentManager().getCurrentRound());
    }

    // ----------------------------------------------------------------
    // Central award method — logs to point_awards table
    // ----------------------------------------------------------------

    /**
     * Awards points and records the source (reason + game).
     *
     * @param uuid   player
     * @param amount points
     * @param reason short id like "kill", "placement_3", "lucky_block_loot"
     * @param gameId active game id, or null
     * @return amount awarded (0 if player not found)
     */
    public int awardPlayerPoints(UUID uuid, int amount, String reason, String gameId) {
        if (amount <= 0 || uuid == null) return 0;

        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd == null) return 0;

        pd.addPoints(amount);
        plugin.getDatabaseManager().savePlayer(pd);

        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(uuid);
        if (team != null) {
            team.addPoints(amount);
            plugin.getDatabaseManager().saveTeam(team);
        }

        // Log it
        try {
            String teamId = team != null ? team.getId() : null;
            int round = plugin.getTournamentManager().getCurrentRound();
            plugin.getDatabaseManager().recordPointAward(new PointAward(
                    uuid, teamId, reason != null ? reason : "unknown", gameId,
                    amount, round, System.currentTimeMillis()));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to log point award: " + e.getMessage());
        }

        return amount;
    }

    /** Backwards-compat wrapper — uses generic "unknown" reason. */
    public int awardPlayerPoints(UUID uuid, int amount) {
        return awardPlayerPoints(uuid, amount, "unknown",
                plugin.getGameManager().getActiveGame() != null
                        ? plugin.getGameManager().getActiveGame().getId() : null);
    }

    // ----------------------------------------------------------------
    // Kills
    // ----------------------------------------------------------------

    public int     getPerKill()         { return pointsCfg.getInt("kills.per-kill", 50); }
    public boolean killsUseMultiplier() { return pointsCfg.getBoolean("kills.apply-multiplier", false); }

    public int awardKill(UUID killerUuid) {
        int base   = getPerKill();
        double mul = killsUseMultiplier() ? getCurrentMultiplier() : 1.0;
        int award  = (int) Math.round(base * mul);

        PlayerData pd = plugin.getPlayerDataManager().get(killerUuid);
        if (pd != null) {
            pd.addKill();
            plugin.getDatabaseManager().savePlayer(pd);
        }

        String gameId = plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getId() : null;
        return awardPlayerPoints(killerUuid, award, "kill", gameId);
    }

    // ----------------------------------------------------------------
    // Placement
    // ----------------------------------------------------------------

    public int  getPlacementFirst()          { return pointsCfg.getInt("placement.first-place", 500); }
    public int  getPlacementLast()           { return pointsCfg.getInt("placement.last-place", 10); }
    public int  getMaxTrackedPosition()      { return pointsCfg.getInt("placement.max-tracked-position", 32); }
    public boolean placementUsesMultiplier() { return pointsCfg.getBoolean("placement.apply-multiplier", true); }

    public int getBasePointsForPlacement(int position) {
        String path = "placement.overrides." + position;
        if (pointsCfg.contains(path)) return pointsCfg.getInt(path);
        if (position > getMaxTrackedPosition()) return getPlacementLast();

        int first = getPlacementFirst();
        int last  = getPlacementLast();
        int maxPos = getMaxTrackedPosition();
        double decrement = (double)(first - last) / Math.max(1, maxPos - 1);
        return (int) Math.max(last, Math.round(first - (position - 1) * decrement));
    }

    public int awardPlayerPlacement(UUID uuid, int position) {
        int base   = getBasePointsForPlacement(position);
        double mul = placementUsesMultiplier() ? getCurrentMultiplier() : 1.0;
        int award  = (int) Math.round(base * mul);
        String gameId = plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getId() : null;
        return awardPlayerPoints(uuid, award, "placement_" + position, gameId);
    }

    // ----------------------------------------------------------------
    // Team-only placement bonus
    // ----------------------------------------------------------------

    public int awardTeamPlacement(String teamId, int position) {
        KMCTeam team = plugin.getTeamManager().getTeam(teamId);
        if (team == null) return 0;
        String path = switch (position) {
            case 1 -> "team-placement.first-place";
            case 2 -> "team-placement.second-place";
            case 3 -> "team-placement.third-place";
            case 4 -> "team-placement.fourth-place";
            default -> null;
        };
        int base = path != null ? pointsCfg.getInt(path, 0) : 0;
        if (base == 0) return 0;
        double mul = pointsCfg.getBoolean("team-placement.apply-multiplier", true)
                     ? getCurrentMultiplier() : 1.0;
        int award = (int) Math.round(base * mul);
        team.addPoints(award);
        plugin.getDatabaseManager().saveTeam(team);
        return award;
    }

    // ----------------------------------------------------------------
    // Bonuses
    // ----------------------------------------------------------------

    public int getLuckyBlockBonus()  { return pointsCfg.getInt("bonus.lucky-block-bonus", 50); }
    public int getDoubleKillBonus()  { return pointsCfg.getInt("bonus.double-kill", 25); }
    public int getTripleKillBonus()  { return pointsCfg.getInt("bonus.triple-kill", 75); }
    public int getMegaKillBonus()    { return pointsCfg.getInt("bonus.mega-kill", 150); }

    // ----------------------------------------------------------------
    // Admin adjustments
    // ----------------------------------------------------------------

    public void setTeamPoints(String teamId, int amount) {
        KMCTeam t = plugin.getTeamManager().getTeam(teamId);
        if (t == null) return;
        t.setPoints(amount);
        plugin.getDatabaseManager().saveTeam(t);
    }
    public void addTeamPoints(String teamId, int amount) {
        KMCTeam t = plugin.getTeamManager().getTeam(teamId);
        if (t == null) return;
        t.addPoints(amount);
        plugin.getDatabaseManager().saveTeam(t);
    }
    public void removeTeamPoints(String teamId, int amount) {
        KMCTeam t = plugin.getTeamManager().getTeam(teamId);
        if (t == null) return;
        t.removePoints(amount);
        plugin.getDatabaseManager().saveTeam(t);
    }

    public void setPlayerPoints(UUID uuid, int amount) {
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd == null) return;
        pd.setPoints(amount);
        plugin.getDatabaseManager().savePlayer(pd);
    }
    public void addPlayerPoints(UUID uuid, int amount) {
        awardPlayerPoints(uuid, amount, "admin_award", null);
    }
    public void removePlayerPoints(UUID uuid, int amount) {
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd == null) return;
        pd.removePoints(amount);
        plugin.getDatabaseManager().savePlayer(pd);
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(uuid);
        if (team != null) {
            team.removePoints(amount);
            plugin.getDatabaseManager().saveTeam(team);
        }
    }
}
