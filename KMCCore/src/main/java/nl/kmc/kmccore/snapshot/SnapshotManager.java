package nl.kmc.kmccore.snapshot;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Captures, stores, and restores tournament state at point-in-time
 * snapshots so admins can roll back if a round goes wrong.
 *
 * <p>State captured per snapshot:
 * <ul>
 *   <li>Every PlayerData (points, kills, wins, win streaks, best streak,
 *       games played)</li>
 *   <li>Every KMCTeam (points, wins)</li>
 *   <li>TournamentManager state (active flag, current round, total rounds)</li>
 * </ul>
 *
 * <p>NOT captured (these can't easily be restored mid-event):
 * <ul>
 *   <li>In-flight game state (active arena, racer positions, etc.)</li>
 *   <li>World/block changes from games (Bridge wool, Spleef holes)</li>
 *   <li>Player inventories</li>
 *   <li>Database history rows</li>
 * </ul>
 *
 * <p>Storage: dual-channel.
 * <ul>
 *   <li><b>In-memory ring buffer</b> of the last 10 snapshots — instant access</li>
 *   <li><b>File backup</b> in {@code plugins/KMCCore/snapshots/} — survives
 *       server restart, can be inspected in any text editor</li>
 * </ul>
 *
 * <p>Note: this manager assumes you've added a public
 * {@code DatabaseManager.getConnection()} getter (Phase 2 Fix Pack ships that).
 */
public class SnapshotManager {

    public static final int RING_BUFFER_SIZE = 10;

    private final KMCCore plugin;
    private final File    snapshotDir;
    private final Deque<Snapshot> ringBuffer = new ArrayDeque<>();

    public SnapshotManager(KMCCore plugin) {
        this.plugin      = plugin;
        this.snapshotDir = new File(plugin.getDataFolder(), "snapshots");
        if (!snapshotDir.exists()) snapshotDir.mkdirs();
        loadFromDisk();
    }

    // ----------------------------------------------------------------
    // Capture
    // ----------------------------------------------------------------

    public Snapshot snapshot(String label) {
        Snapshot snap = captureCurrentState(label);

        ringBuffer.addFirst(snap);
        while (ringBuffer.size() > RING_BUFFER_SIZE) ringBuffer.removeLast();

        // Async disk write so we don't block the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(snap));

        plugin.getLogger().info("Snapshot taken: '" + label + "' ("
                + snap.players.size() + " players, " + snap.teams.size() + " teams)");
        return snap;
    }

    private Snapshot captureCurrentState(String label) {
        Snapshot snap = new Snapshot();
        snap.label       = label;
        snap.timestampMs = System.currentTimeMillis();

        // Players — use the leaderboard (which is the canonical source)
        for (PlayerData pd : plugin.getPlayerDataManager().getLeaderboard()) {
            PlayerSnap ps = new PlayerSnap();
            ps.uuid          = pd.getUuid().toString();
            ps.name          = pd.getName();
            ps.teamId        = pd.getTeamId();
            ps.points        = pd.getPoints();
            ps.kills         = pd.getKills();
            ps.wins          = pd.getWins();
            ps.gamesPlayed   = pd.getGamesPlayed();
            ps.winStreak     = pd.getWinStreak();
            ps.bestWinStreak = pd.getBestWinStreak();
            snap.players.add(ps);
        }

        // Teams
        for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
            TeamSnap ts = new TeamSnap();
            ts.id     = t.getId();
            ts.points = t.getPoints();
            ts.wins   = t.getWins();
            ts.memberUuids = new ArrayList<>();
            for (UUID m : t.getMembers()) ts.memberUuids.add(m.toString());
            snap.teams.add(ts);
        }

        // Tournament
        snap.tournamentActive = plugin.getTournamentManager().isActive();
        snap.tournamentRound  = plugin.getTournamentManager().getCurrentRound();
        snap.tournamentTotal  = plugin.getTournamentManager().getTotalRounds();

        return snap;
    }

    // ----------------------------------------------------------------
    // Restore
    // ----------------------------------------------------------------

    public boolean restore(String label) {
        Snapshot snap = findSnapshot(label);
        if (snap == null) return false;
        applySnapshot(snap);
        plugin.getLogger().log(Level.WARNING, "ROLLBACK: restored snapshot '"
                + snap.label + "' (taken " + formatAge(snap.timestampMs) + " ago)");
        return true;
    }

    public boolean restoreLatest() {
        if (ringBuffer.isEmpty()) return false;
        applySnapshot(ringBuffer.peekFirst());
        plugin.getLogger().log(Level.WARNING, "ROLLBACK: restored latest snapshot '"
                + ringBuffer.peekFirst().label + "'");
        return true;
    }

    private void applySnapshot(Snapshot snap) {
        // Players
        for (PlayerSnap ps : snap.players) {
            try {
                UUID uuid = UUID.fromString(ps.uuid);
                PlayerData pd = plugin.getPlayerDataManager().getOrCreate(uuid, ps.name);
                pd.setPoints(ps.points);
                pd.setKills(ps.kills);
                pd.setWins(ps.wins);
                pd.setGamesPlayed(ps.gamesPlayed);
                pd.setWinStreak(ps.winStreak);
                pd.setBestWinStreak(ps.bestWinStreak);
                plugin.getDatabaseManager().savePlayer(pd);
            } catch (Exception e) {
                plugin.getLogger().warning("Restore: failed for player " + ps.name
                        + " — " + e.getMessage());
            }
        }

        // Teams (only restore points/wins of EXISTING teams; can't create new)
        for (TeamSnap ts : snap.teams) {
            try {
                KMCTeam t = plugin.getTeamManager().getTeam(ts.id);
                if (t == null) continue;  // team no longer exists, skip
                t.setPoints(ts.points);
                t.setWins(ts.wins);
                plugin.getDatabaseManager().saveTeam(t);
            } catch (Exception e) {
                plugin.getLogger().warning("Restore: failed for team " + ts.id
                        + " — " + e.getMessage());
            }
        }

        // Tournament round (only the round number; not the active flag)
        try { plugin.getTournamentManager().setRound(snap.tournamentRound); }
        catch (Exception ignored) {}

        // Refresh visible state (best-effort)
        try { plugin.getScoreboardManager().refreshAll(); } catch (Throwable ignored) {}
        try { plugin.getTabListManager().refreshAll(); }    catch (Throwable ignored) {}
    }

    // ----------------------------------------------------------------
    // Listing / lookup
    // ----------------------------------------------------------------

    public List<Snapshot> listAll() { return new ArrayList<>(ringBuffer); }

    public Snapshot findSnapshot(String label) {
        for (Snapshot s : ringBuffer) if (s.label.equalsIgnoreCase(label)) return s;
        return loadSingleFromDisk(label);
    }

    public Snapshot getLatest() { return ringBuffer.peekFirst(); }

    // ----------------------------------------------------------------
    // Disk I/O
    // ----------------------------------------------------------------

    private void writeToDisk(Snapshot snap) {
        File f = new File(snapshotDir, sanitize(snap.label) + ".yml");
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("label",            snap.label);
        cfg.set("timestamp",        snap.timestampMs);
        cfg.set("tournamentActive", snap.tournamentActive);
        cfg.set("tournamentRound",  snap.tournamentRound);
        cfg.set("tournamentTotal",  snap.tournamentTotal);

        for (int i = 0; i < snap.players.size(); i++) {
            PlayerSnap ps = snap.players.get(i);
            String base = "players." + i;
            cfg.set(base + ".uuid",          ps.uuid);
            cfg.set(base + ".name",          ps.name);
            cfg.set(base + ".teamId",        ps.teamId);
            cfg.set(base + ".points",        ps.points);
            cfg.set(base + ".kills",         ps.kills);
            cfg.set(base + ".wins",          ps.wins);
            cfg.set(base + ".gamesPlayed",   ps.gamesPlayed);
            cfg.set(base + ".winStreak",     ps.winStreak);
            cfg.set(base + ".bestWinStreak", ps.bestWinStreak);
        }
        for (int i = 0; i < snap.teams.size(); i++) {
            TeamSnap ts = snap.teams.get(i);
            String base = "teams." + i;
            cfg.set(base + ".id",      ts.id);
            cfg.set(base + ".points",  ts.points);
            cfg.set(base + ".wins",    ts.wins);
            cfg.set(base + ".members", ts.memberUuids);
        }

        try { cfg.save(f); }
        catch (IOException e) {
            plugin.getLogger().warning("Failed to write snapshot to disk: " + e.getMessage());
        }
    }

    private void loadFromDisk() {
        File[] files = snapshotDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        int loaded = 0;
        for (File f : files) {
            if (loaded >= RING_BUFFER_SIZE) break;
            Snapshot s = loadFile(f);
            if (s != null) { ringBuffer.addLast(s); loaded++; }
        }
        if (loaded > 0) plugin.getLogger().info("Loaded " + loaded + " snapshot(s) from disk.");
    }

    private Snapshot loadSingleFromDisk(String label) {
        File f = new File(snapshotDir, sanitize(label) + ".yml");
        return f.exists() ? loadFile(f) : null;
    }

    private Snapshot loadFile(File f) {
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            Snapshot s = new Snapshot();
            s.label             = cfg.getString("label", f.getName().replace(".yml", ""));
            s.timestampMs       = cfg.getLong("timestamp");
            s.tournamentActive  = cfg.getBoolean("tournamentActive");
            s.tournamentRound   = cfg.getInt("tournamentRound");
            s.tournamentTotal   = cfg.getInt("tournamentTotal");

            var psSec = cfg.getConfigurationSection("players");
            if (psSec != null) {
                for (String idx : psSec.getKeys(false)) {
                    PlayerSnap ps = new PlayerSnap();
                    ps.uuid          = psSec.getString(idx + ".uuid");
                    ps.name          = psSec.getString(idx + ".name");
                    ps.teamId        = psSec.getString(idx + ".teamId");
                    ps.points        = psSec.getInt(idx + ".points");
                    ps.kills         = psSec.getInt(idx + ".kills");
                    ps.wins          = psSec.getInt(idx + ".wins");
                    ps.gamesPlayed   = psSec.getInt(idx + ".gamesPlayed");
                    ps.winStreak     = psSec.getInt(idx + ".winStreak");
                    ps.bestWinStreak = psSec.getInt(idx + ".bestWinStreak");
                    s.players.add(ps);
                }
            }
            var tsSec = cfg.getConfigurationSection("teams");
            if (tsSec != null) {
                for (String idx : tsSec.getKeys(false)) {
                    TeamSnap ts = new TeamSnap();
                    ts.id          = tsSec.getString(idx + ".id");
                    ts.points      = tsSec.getInt(idx + ".points");
                    ts.wins        = tsSec.getInt(idx + ".wins");
                    ts.memberUuids = tsSec.getStringList(idx + ".members");
                    s.teams.add(ts);
                }
            }
            return s;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load snapshot " + f.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String formatAge(long timestampMs) {
        long ageMs = System.currentTimeMillis() - timestampMs;
        long mins  = ageMs / 60000;
        if (mins < 1) return (ageMs / 1000) + "s";
        if (mins < 60) return mins + "m";
        return (mins / 60) + "h" + (mins % 60) + "m";
    }

    // ----------------------------------------------------------------
    // Data classes
    // ----------------------------------------------------------------

    public static class Snapshot {
        public String  label;
        public long    timestampMs;
        public boolean tournamentActive;
        public int     tournamentRound;
        public int     tournamentTotal;
        public List<PlayerSnap> players = new ArrayList<>();
        public List<TeamSnap>   teams   = new ArrayList<>();
    }

    public static class PlayerSnap {
        public String uuid;
        public String name;
        public String teamId;
        public int    points;
        public int    kills;
        public int    wins;
        public int    gamesPlayed;
        public int    winStreak;
        public int    bestWinStreak;
    }

    public static class TeamSnap {
        public String       id;
        public int          points;
        public int          wins;
        public List<String> memberUuids = new ArrayList<>();
    }
}
