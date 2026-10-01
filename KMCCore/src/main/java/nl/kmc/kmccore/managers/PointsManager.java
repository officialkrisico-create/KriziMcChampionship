package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * Calculates and awards all point rewards.
 *
 * <p><b>Key rule:</b> Whenever a player earns points, their team also
 * earns the same amount. This is handled centrally by
 * {@link #awardPlayerPoints(UUID, int)} which routes through
 * {@link PlayerData#addPoints(int)} AND
 * {@link KMCTeam#addPoints(int)} in one call. Every other method
 * that awards player points delegates here so the team total
 * stays in sync automatically.
 *
 * <p>Example: if player A earns 10 and player B (same team) earns 50,
 * the team total is 60 — both players' personal totals show their own
 * amount only.
 */
public class PointsManager {

    private final KMCCore plugin;
    private File             pointsFile;
    private FileConfiguration pointsCfg;

    // Golden Hour: one surprise round per tournament with a boosted multiplier,
    // overriding whatever the round's own configured multiplier would be.
    private boolean goldenHourActive;
    private double  goldenHourMultiplier = 2.0;

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

    // ----------------------------------------------------------------
    // Multiplier
    // ----------------------------------------------------------------

    public double getMultiplierForRound(int round) {
        return plugin.getConfig().getDouble("tournament.multipliers." + round, 1.0);
    }

    public double getCurrentMultiplier() {
        if (goldenHourActive) return goldenHourMultiplier;
        return getMultiplierForRound(plugin.getTournamentManager().getCurrentRound());
    }

    /** Activates Golden Hour: every point award uses this multiplier until {@link #deactivateGoldenHour()}. */
    public void activateGoldenHour(double multiplier) {
        this.goldenHourMultiplier = multiplier;
        this.goldenHourActive = true;
    }

    public void deactivateGoldenHour() { this.goldenHourActive = false; }

    public boolean isGoldenHourActive() { return goldenHourActive; }

    // ----------------------------------------------------------------
    // CENTRAL POINT-AWARD METHOD
    // Every point a player gets flows through here.
    // ----------------------------------------------------------------

    /**
     * Awards {@code amount} points to a player AND the same amount to
     * their team if they have one. Saves both to the database.
     *
     * <p>This is the <b>single source of truth</b> for point awards —
     * every other method that gives points calls this.
     *
     * @param uuid   player UUID
     * @param amount points to award (negative amounts are refused)
     * @return actual amount awarded (0 if player not found)
     */
    public int awardPlayerPoints(UUID uuid, int amount) {
        if (amount <= 0) return 0;

        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd == null) return 0;

        // Comeback bonus: boost BEFORE crediting, so player and team totals
        // stay in sync (the same boosted amount goes to both).
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(uuid);
        amount = applyComebackBonus(amount, team);

        // Player gets the points
        pd.addPoints(amount);
        plugin.getDatabaseManager().savePlayer(pd);

        // Team ALSO gets the same amount (auto-sync)
        if (team != null) {
            team.addPoints(amount);
            plugin.getDatabaseManager().saveTeam(team);
        }

        return amount;
    }

    // ----------------------------------------------------------------
    // Kill rewards
    // ----------------------------------------------------------------

    public int     getPerKill()            { return pointsCfg.getInt("kills.per-kill", 50); }
    public boolean killsUseMultiplier() {
        // Golden Hour is an absolute "everything is boosted" spectacle — it
        // overrides the normal flat-kills design for its one special round.
        if (goldenHourActive) return true;
        return pointsCfg.getBoolean("kills.apply-multiplier", true);
    }

    /**
     * Awards a kill to the killer. Kill stat increments on the player;
     * points flow through the central award method so the team is
     * credited automatically.
     *
     * @return points awarded
     */
    public int awardKill(UUID killerUuid) {
        int base   = getPerKill();
        double mul = killsUseMultiplier() ? getCurrentMultiplier() : 1.0;
        int award  = (int) Math.round(base * mul);

        PlayerData pd = plugin.getPlayerDataManager().get(killerUuid);
        if (pd != null) {
            pd.addKill();
            plugin.getDatabaseManager().savePlayer(pd);
        }

        // Route through central method — team auto-credited
        return awardPlayerPoints(killerUuid, award);
    }

    // ----------------------------------------------------------------
    // Placement rewards (individual)
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

    /** Awards placement points (routes through central method → team auto-credited). */
    public int awardPlayerPlacement(UUID uuid, int position) {
        int base   = getBasePointsForPlacement(position);
        double mul = placementUsesMultiplier() ? getCurrentMultiplier() : 1.0;
        int award  = (int) Math.round(base * mul);
        return awardPlayerPoints(uuid, award);
    }

    // ----------------------------------------------------------------
    // Comeback bonus — keeps a runaway tournament interesting by giving
    // whichever team is currently in last place a small boost on every
    // point award, once a meaningful gap has actually formed.
    // ----------------------------------------------------------------

    /** Boosts {@code amount} if {@code team} is the current last-place team; otherwise returns it unchanged. */
    private int applyComebackBonus(int amount, KMCTeam team) {
        if (team == null) return amount;
        if (!plugin.getConfig().getBoolean("comeback-bonus.enabled", true)) return amount;

        var standings = plugin.getTeamManager().getTeamsSortedByPoints();
        if (standings.size() < 2) return amount;

        KMCTeam last = standings.get(standings.size() - 1);
        if (!last.getId().equals(team.getId())) return amount;

        KMCTeam leader = standings.get(0);
        int minGap = plugin.getConfig().getInt("comeback-bonus.min-gap", 100);
        if (leader.getPoints() - last.getPoints() < minGap) return amount;

        double multiplier = plugin.getConfig().getDouble("comeback-bonus.multiplier", 1.25);
        return (int) Math.round(amount * multiplier);
    }

    // ----------------------------------------------------------------
    // Team-only placement bonus
    // This is a SEPARATE reward on top of player placements — used for
    // team-based games where winning as a team matters even above and
    // beyond individual scores. Does NOT affect personal points.
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
        award = applyComebackBonus(award, team);
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
    // Manual admin adjustments
    // These DON'T auto-credit the team — admins can use team commands
    // explicitly if they want to bump team points directly.
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
        t.setPoints(t.getPoints() - amount);
        plugin.getDatabaseManager().saveTeam(t);
    }

    public void setPlayerPoints(UUID uuid, int amount) {
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd == null) return;
        pd.setPoints(amount);
        plugin.getDatabaseManager().savePlayer(pd);
    }
    /** Admin add — ALSO credits the team, staying consistent with in-game awards. */
    public void addPlayerPoints(UUID uuid, int amount) {
        awardPlayerPoints(uuid, amount);
    }
    public void removePlayerPoints(UUID uuid, int amount) {
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd == null) return;
        pd.removePoints(amount);
        plugin.getDatabaseManager().savePlayer(pd);

        // Also remove from team
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(uuid);
        if (team != null) {
            team.setPoints(team.getPoints() - amount);
            plugin.getDatabaseManager().saveTeam(team);
        }
    }
}