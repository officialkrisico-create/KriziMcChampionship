package nl.kmc.kmccore.tournament;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Central persistent store for the tournament-presentation features (medals,
 * and — by design — future MVP counts, team ELO/power-ranking and momentum
 * history). Backed by a single YAML file so we don't have to migrate the
 * column-based player database, and so all presentation features share one
 * storage system instead of duplicating their own.
 *
 * <p>Lifetime totals live under {@code medals.<uuid>.*}.
 */
public final class TournamentDataStore {

    private final KMCCore plugin;
    private final File file;
    private FileConfiguration cfg;

    public TournamentDataStore(KMCCore plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "tournament-data.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try { cfg.save(file); }
        catch (Exception e) { plugin.getLogger().warning("Failed to save tournament-data.yml: " + e.getMessage()); }
    }

    private void saveAsync() { Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save); }

    // ── Medals ────────────────────────────────────────────────────────────────

    /** Awards a medal for a per-game placement (1=gold, 2=silver, 3=bronze). */
    public void awardMedal(UUID uuid, String name, int placement) {
        if (uuid == null || placement < 1 || placement > 3) return;
        String base = "medals." + uuid;
        String key  = placement == 1 ? "gold" : placement == 2 ? "silver" : "bronze";
        cfg.set(base + "." + key, cfg.getInt(base + "." + key, 0) + 1);
        if (name != null) cfg.set(base + ".name", name);
        saveAsync();
    }

    /** @return {gold, silver, bronze} lifetime medal counts. */
    public int[] getMedals(UUID uuid) {
        String b = "medals." + uuid;
        return new int[]{ cfg.getInt(b + ".gold", 0), cfg.getInt(b + ".silver", 0), cfg.getInt(b + ".bronze", 0) };
    }

    public int totalMedals(UUID uuid) { int[] m = getMedals(uuid); return m[0] + m[1] + m[2]; }

    /** Weighted medal score (gold 5 / silver 3 / bronze 1) for "most decorated" ranking. */
    public int medalScore(UUID uuid) { int[] m = getMedals(uuid); return m[0] * 5 + m[1] * 3 + m[2]; }

    public String getName(UUID uuid) {
        String n = cfg.getString("medals." + uuid + ".name");
        return n != null ? n : uuid.toString().substring(0, 8);
    }

    /** Players sorted by weighted medal score (descending), most-decorated first. */
    public List<UUID> mostDecorated(int limit) {
        var sec = cfg.getConfigurationSection("medals");
        if (sec == null) return List.of();
        List<UUID> ids = new ArrayList<>();
        for (String key : sec.getKeys(false)) {
            try { ids.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) {}
        }
        ids.sort((a, b) -> Integer.compare(medalScore(b), medalScore(a)));
        return ids.size() > limit ? ids.subList(0, limit) : ids;
    }

    /** Renders the medal trio as a coloured string, e.g. "&63 &7◆ &f1 &7◆ &62". */
    public String formatMedals(UUID uuid) {
        int[] m = getMedals(uuid);
        return "§6🥇 " + m[0] + " §7§l| §f🥈 " + m[1] + " §7§l| §c🥉 " + m[2];
    }

    // ── MVP ─────────────────────────────────────────────────────────────────

    /** Records a per-game MVP (increments this tournament's + lifetime counts). */
    public void recordGameMvp(UUID uuid, String name) {
        if (uuid == null) return;
        cfg.set("mvp.tournament." + uuid, cfg.getInt("mvp.tournament." + uuid, 0) + 1);
        cfg.set("mvp.lifetime." + uuid,   cfg.getInt("mvp.lifetime." + uuid, 0) + 1);
        if (name != null) cfg.set("mvp.name." + uuid, name);
        saveAsync();
    }

    public int getTournamentMvp(UUID uuid) { return cfg.getInt("mvp.tournament." + uuid, 0); }
    public int getLifetimeMvp(UUID uuid)   { return cfg.getInt("mvp.lifetime." + uuid, 0); }
    public String getMvpName(UUID uuid) {
        String n = cfg.getString("mvp.name." + uuid);
        return n != null ? n : uuid.toString().substring(0, 8);
    }

    /** Resets the per-tournament MVP tally (call at tournament start). */
    public void resetTournamentMvp() { cfg.set("mvp.tournament", null); save(); }

    /** Top MVPs by either "tournament" or "lifetime" scope. */
    public List<UUID> topMvp(boolean lifetime, int limit) {
        var sec = cfg.getConfigurationSection(lifetime ? "mvp.lifetime" : "mvp.tournament");
        if (sec == null) return List.of();
        List<UUID> ids = new ArrayList<>();
        for (String key : sec.getKeys(false)) {
            try { ids.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) {}
        }
        ids.sort((a, b) -> Integer.compare(
                lifetime ? getLifetimeMvp(b) : getTournamentMvp(b),
                lifetime ? getLifetimeMvp(a) : getTournamentMvp(a)));
        return ids.size() > limit ? ids.subList(0, limit) : ids;
    }

    // ── Momentum (team rank movement between games) ───────────────────────────

    /**
     * Snapshots the current team standings (ranked teamIds) and computes movement
     * vs the previous game: biggest rise/fall and per-team hot-streak (consecutive
     * top-3 finishes). Call once after each game's points are settled.
     */
    public void recordGameStandings(List<String> rankedTeamIds) {
        if (rankedTeamIds == null || rankedTeamIds.isEmpty()) return;
        Map<String, Integer> prev = new HashMap<>();
        var prevSec = cfg.getConfigurationSection("momentum.last-ranks");
        if (prevSec != null) for (String t : prevSec.getKeys(false)) prev.put(t, prevSec.getInt(t));

        String biggestRiseTeam = null, biggestFallTeam = null;
        int biggestRise = 0, biggestFall = 0;
        for (int i = 0; i < rankedTeamIds.size(); i++) {
            String team = rankedTeamIds.get(i);
            int rank = i + 1;
            cfg.set("momentum.last-ranks." + team, rank);
            if (prev.containsKey(team)) {
                int delta = prev.get(team) - rank;       // positive = moved up
                if (delta > biggestRise) { biggestRise = delta; biggestRiseTeam = team; }
                if (-delta > biggestFall) { biggestFall = -delta; biggestFallTeam = team; }
            }
            // Hot streak: consecutive top-3 finishes.
            int streak = rank <= 3 ? cfg.getInt("momentum.streak." + team, 0) + 1 : 0;
            cfg.set("momentum.streak." + team, streak);
        }
        cfg.set("momentum.biggest-rise.team",  biggestRiseTeam);
        cfg.set("momentum.biggest-rise.value", biggestRise);
        cfg.set("momentum.biggest-fall.team",  biggestFallTeam);
        cfg.set("momentum.biggest-fall.value", biggestFall);
        saveAsync();
    }

    public String getBiggestRiseTeam() { return cfg.getString("momentum.biggest-rise.team"); }
    public int    getBiggestRise()     { return cfg.getInt("momentum.biggest-rise.value", 0); }
    public String getBiggestFallTeam() { return cfg.getString("momentum.biggest-fall.team"); }
    public int    getBiggestFall()     { return cfg.getInt("momentum.biggest-fall.value", 0); }
    public int    getHotStreak(String teamId) { return cfg.getInt("momentum.streak." + teamId, 0); }

    /** Wipes per-tournament momentum (call at tournament start). */
    public void resetMomentum() {
        cfg.set("momentum", null);
        save();
    }

    // ── Power ranking (team ELO, historical) ──────────────────────────────────

    private static final int BASE_ELO = 1000;

    public int getElo(String teamId) { return cfg.getInt("elo." + teamId, BASE_ELO); }

    /**
     * Updates every team's ELO from a game's final standings (multiplayer ELO:
     * teams that out-place the field gain rating, under-performers lose it).
     * Persists across tournaments — that's the historical "power" of a team.
     */
    public void updateElo(List<String> rankedTeamIds) {
        if (rankedTeamIds == null || rankedTeamIds.size() < 2) return;
        int n = rankedTeamIds.size();
        double k = 24.0;
        double[] cur = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { cur[i] = getElo(rankedTeamIds.get(i)); sum += cur[i]; }
        double mean = sum / n;
        for (int i = 0; i < n; i++) {
            double actual   = (n - 1 - i) / (double) (n - 1);                   // 1 = first, 0 = last
            double expected = 1.0 / (1.0 + Math.pow(10, (mean - cur[i]) / 400.0));
            cfg.set("elo." + rankedTeamIds.get(i), (int) Math.round(cur[i] + k * (actual - expected)));
        }
        saveAsync();
    }

    /** A friendly 1–99 power rating derived from ELO (1000 → 50). */
    public int ratingOf(String teamId) {
        return Math.max(1, Math.min(99, (int) Math.round(50 + (getElo(teamId) - BASE_ELO) / 10.0)));
    }

    /** Teams sorted by ELO (highest first). */
    public List<String> powerRanking() {
        var sec = cfg.getConfigurationSection("elo");
        if (sec == null) return List.of();
        List<String> teams = new ArrayList<>(sec.getKeys(false));
        teams.sort((a, b) -> Integer.compare(getElo(b), getElo(a)));
        return teams;
    }
}
