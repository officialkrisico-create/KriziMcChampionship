package nl.kmc.kmccore.scoring;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.scoring.PointsLedger.Entry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * One file per tournament edition ({@code plugins/KMCCore/history/edition-<n>.yml}): final standings,
 * teams with members, every player's totals, the games that were played with their winners, the awards
 * and the crowd's feedback. Written when a tournament ends (before anything is reset) and read back by
 * {@link SeasonRanking} for the season / all-time ranking and the per-player history.
 */
public final class EditionArchive {

    // ── Read model ────────────────────────────────────────────────────────────

    public record TeamRow(String id, String name, int points, int rank, List<MemberRow> members) {}
    public record MemberRow(UUID uuid, String name, int points) {}
    public record PlayerRow(UUID uuid, String name, String teamId, String teamName, int points, int kills, int wins, int rank) {}
    public record GameRow(int seq, String gameId, int round, String topPlayer, int topPoints, String topTeam) {}
    public record AwardRow(String id, boolean team, String name, String teamId, double value) {}

    public record Edition(int number, String tournamentName, long date, int rounds, String championTeamId,
                          String championName, List<TeamRow> teams, List<PlayerRow> players,
                          List<GameRow> games, List<AwardRow> awards, double feedbackAverage, int feedbackVotes) {}

    private EditionArchive() {}

    public static File dir(KMCCore plugin) {
        File d = new File(plugin.getDataFolder(), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(KMCCore plugin, int edition) {
        return new File(dir(plugin), "edition-" + edition + ".yml");
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Captures the finished tournament. Must run BEFORE points are reset.
     *
     * @param awards computed awards (see {@link AwardsEngine})
     */
    public static void write(KMCCore plugin, int edition, List<AwardsEngine.Award> awards) {
        YamlConfiguration y = new YamlConfiguration();
        var tm = plugin.getTournamentManager();
        List<KMCTeam> standings = tm.getFinalStandings();
        KMCTeam champ = standings.isEmpty() ? null : standings.get(0);

        y.set("edition", edition);
        y.set("name", plugin.getConfig().getString("tournament.name", "KMC"));
        y.set("date", System.currentTimeMillis());
        y.set("rounds", tm.getTotalRounds());
        y.set("champion.id", champ != null ? champ.getId() : null);
        y.set("champion.name", champ != null ? champ.getDisplayName() : null);

        int rank = 1;
        for (KMCTeam t : standings) {
            String base = "teams." + (rank - 1);
            y.set(base + ".id", t.getId());
            y.set(base + ".name", t.getDisplayName());
            y.set(base + ".points", t.getPoints());
            y.set(base + ".rank", rank);
            int m = 0;
            for (UUID u : t.getMembers()) {
                PlayerData pd = plugin.getPlayerDataManager().get(u);
                String mb = base + ".members." + m++;
                y.set(mb + ".uuid", u.toString());
                y.set(mb + ".name", pd != null ? pd.getName() : name(u));
                y.set(mb + ".points", pd != null ? pd.getPoints() : 0);
            }
            rank++;
        }

        List<PlayerData> players = plugin.getPlayerDataManager().getLeaderboard();
        for (int i = 0; i < players.size(); i++) {
            PlayerData pd = players.get(i);
            KMCTeam t = plugin.getTeamManager().getTeamByPlayer(pd.getUuid());
            String base = "players." + i;
            y.set(base + ".uuid", pd.getUuid().toString());
            y.set(base + ".name", pd.getName());
            y.set(base + ".team", t != null ? t.getId() : pd.getTeamId());
            y.set(base + ".team-name", t != null ? t.getDisplayName() : null);
            y.set(base + ".points", pd.getPoints());
            y.set(base + ".kills", pd.getKills());
            y.set(base + ".wins", pd.getWins());
            y.set(base + ".rank", i + 1);
        }

        var ledger = plugin.getPointsLedger();
        int gi = 0;
        for (PointsLedger.GameInfo g : ledger.games()) {
            List<Entry> es = ledger.forGame(g.seq());
            if (es.isEmpty()) continue;
            String base = "games." + gi++;
            y.set(base + ".seq", g.seq());
            y.set(base + ".id", g.gameId());
            y.set(base + ".round", g.round());
            var byPlayer = PointsLedger.byPlayer(es);
            if (!byPlayer.isEmpty()) {
                var top = byPlayer.entrySet().iterator().next();
                y.set(base + ".top-player", name(plugin, top.getKey()));
                y.set(base + ".top-points", top.getValue());
            }
            var byTeam = PointsLedger.byTeam(es);
            if (!byTeam.isEmpty()) y.set(base + ".top-team", byTeam.keySet().iterator().next());
        }

        int ai = 0;
        for (AwardsEngine.Award a : awards) {
            String base = "awards." + ai++;
            y.set(base + ".id", a.id());
            y.set(base + ".team", a.teamAward());
            y.set(base + ".name", a.name());
            y.set(base + ".team-id", a.teamId());
            y.set(base + ".value", a.value());
        }

        try { y.save(file(plugin, edition)); }
        catch (IOException e) { plugin.getLogger().warning("Could not write edition archive: " + e.getMessage()); }
    }

    /** Adds one crowd rating (1-5) and optional comment to the archive of this edition. */
    public static void addFeedback(KMCCore plugin, int edition, UUID voter, int stars, String comment) {
        File f = file(plugin, edition);
        YamlConfiguration y = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        y.set("feedback.votes." + voter, stars);
        if (comment != null && !comment.isBlank()) y.set("feedback.comments." + voter, comment);
        try { y.save(f); }
        catch (IOException e) { plugin.getLogger().warning("Could not save feedback: " + e.getMessage()); }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public static List<Edition> readAll(KMCCore plugin) {
        List<Edition> out = new ArrayList<>();
        File[] files = dir(plugin).listFiles((d, n) -> n.startsWith("edition-") && n.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            try { out.add(read(YamlConfiguration.loadConfiguration(f))); }
            catch (Exception e) { plugin.getLogger().warning("Skipping unreadable archive " + f.getName() + ": " + e); }
        }
        out.sort(Comparator.comparingInt(Edition::number));
        return out;
    }

    public static Edition read(YamlConfiguration y) {
        List<TeamRow> teams = new ArrayList<>();
        ConfigurationSection ts = y.getConfigurationSection("teams");
        if (ts != null) for (String k : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(k);
            List<MemberRow> members = new ArrayList<>();
            ConfigurationSection ms = t.getConfigurationSection("members");
            if (ms != null) for (String mk : ms.getKeys(false)) {
                ConfigurationSection m = ms.getConfigurationSection(mk);
                try { members.add(new MemberRow(UUID.fromString(m.getString("uuid")), m.getString("name", "?"), m.getInt("points"))); }
                catch (Exception ignored) { }
            }
            teams.add(new TeamRow(t.getString("id"), t.getString("name", "?"), t.getInt("points"), t.getInt("rank"), members));
        }
        List<PlayerRow> players = new ArrayList<>();
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) for (String k : ps.getKeys(false)) {
            ConfigurationSection p = ps.getConfigurationSection(k);
            try {
                players.add(new PlayerRow(UUID.fromString(p.getString("uuid")), p.getString("name", "?"),
                        p.getString("team"), p.getString("team-name"), p.getInt("points"), p.getInt("kills"),
                        p.getInt("wins"), p.getInt("rank")));
            } catch (Exception ignored) { }
        }
        List<GameRow> games = new ArrayList<>();
        ConfigurationSection gs = y.getConfigurationSection("games");
        if (gs != null) for (String k : gs.getKeys(false)) {
            ConfigurationSection g = gs.getConfigurationSection(k);
            games.add(new GameRow(g.getInt("seq"), g.getString("id"), g.getInt("round"),
                    g.getString("top-player"), g.getInt("top-points"), g.getString("top-team")));
        }
        List<AwardRow> awards = new ArrayList<>();
        ConfigurationSection as = y.getConfigurationSection("awards");
        if (as != null) for (String k : as.getKeys(false)) {
            ConfigurationSection a = as.getConfigurationSection(k);
            awards.add(new AwardRow(a.getString("id"), a.getBoolean("team"), a.getString("name"),
                    a.getString("team-id"), a.getDouble("value")));
        }
        double sum = 0; int votes = 0;
        ConfigurationSection fv = y.getConfigurationSection("feedback.votes");
        if (fv != null) for (String k : fv.getKeys(false)) { sum += fv.getInt(k); votes++; }
        return new Edition(y.getInt("edition"), y.getString("name", "KMC"), y.getLong("date"), y.getInt("rounds"),
                y.getString("champion.id"), y.getString("champion.name"), teams, players, games, awards,
                votes == 0 ? 0 : sum / votes, votes);
    }

    private static String name(UUID u) {
        var op = org.bukkit.Bukkit.getOfflinePlayer(u);
        return op.getName() != null ? op.getName() : u.toString().substring(0, 8);
    }

    private static String name(KMCCore plugin, UUID u) {
        PlayerData pd = plugin.getPlayerDataManager().get(u);
        return pd != null && pd.getName() != null ? pd.getName() : name(u);
    }
}
