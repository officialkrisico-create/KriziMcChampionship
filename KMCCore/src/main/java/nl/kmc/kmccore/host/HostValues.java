package nl.kmc.kmccore.host;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.scoring.AwardsEngine;
import nl.kmc.kmccore.scoring.EditionArchive;
import nl.kmc.kmccore.tournament.EventEndSequence;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fills the {@code {placeholders}} of the host dialogues from the live KMC systems: the team manager, the
 * points manager, the game registry, the archive of finished editions and the awards of the last tournament.
 * A value that does not exist yet is simply left out of the map, and the dialogue line that needs it is
 * skipped — nothing is invented.
 */
public final class HostValues {

    private HostValues() { }

    /** A team's name in its colour, ready for the middle of a sentence. */
    public static String colored(KMCTeam t) {
        return t == null ? "" : t.getColor() + t.getDisplayName() + "§f";
    }

    public static Optional<GameRegistration> registration(String gameId) {
        if (gameId == null) return Optional.empty();
        try {
            var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
            if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
                return v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class).get(gameId);
            }
        } catch (Throwable ignored) { }
        return Optional.empty();
    }

    private static final String[] NUMBER_WORDS = {"nul", "één", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht",
            "negen", "tien", "elf", "twaalf"};

    /** A small number in Dutch words (four → "vier"); larger ones stay digits. */
    public static String seatsWord(int n) {
        return n >= 1 && n < NUMBER_WORDS.length ? NUMBER_WORDS[n] : String.valueOf(n);
    }

    static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Values that depend on the whole tournament, not on a player or game. */
    public static Map<String, String> tournament(KMCCore plugin, HostContent content) {
        Map<String, String> m = new HashMap<>();
        m.put("host", HostText.strip(plugin.getConfig().getString("host.npc.name", "KMC Host")));
        m.put("tournament_name", plugin.getConfig().getString("tournament.name", "KMC"));
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        m.put("team_count", teams.isEmpty() ? "" : String.valueOf(teams.size()));
        // the seats on the rescue plane = the size of a team: only the winning team flies home
        int seats = plugin.getTeamManager().getMaxPlayersPerTeam();
        if (seats > 0) {
            m.put("seats", String.valueOf(seats));
            m.put("seats_word", seatsWord(seats));
            m.put("seats_cap", capitalize(seatsWord(seats)));
        }
        var tm = plugin.getTournamentManager();
        m.put("round", String.valueOf(tm.getCurrentRound()));
        m.put("rounds", tm.getTotalRounds() > 0 ? String.valueOf(tm.getTotalRounds()) : "");
        var auto = plugin.getAutomationManager();
        if (auto != null) {
            m.put("games_played", String.valueOf(auto.getGamesPlayedTotal()));
            int total = auto.getTotalGames();
            m.put("games_total", total > 0 ? String.valueOf(total) : "");
        }
        // standings: only when somebody has points (otherwise there is no leader to speak of)
        boolean anyPoints = teams.stream().anyMatch(t -> t.getPoints() > 0);
        if (anyPoints) {
            m.put("leaderboard_leader", colored(teams.get(0)));
            m.put("leader_points", String.valueOf(teams.get(0).getPoints()));
            for (int i = 0; i < 3 && i < teams.size(); i++) m.put("top_team_" + (i + 1), colored(teams.get(i)));
            if (teams.size() >= 2) {
                m.put("second", colored(teams.get(1)));
                m.put("gap", String.valueOf(teams.get(0).getPoints() - teams.get(1).getPoints()));
            }
        }
        String champ = tm.getChampionTeamId();
        if (champ != null) {
            KMCTeam t = plugin.getTeamManager().getTeam(champ);
            if (t != null) m.put("winner", colored(t));
        }
        m.putAll(content.editionValues());
        awardValues(m);
        return m;
    }

    /** Values from the archive of finished editions (cached by HostContent: the archive is read from disk). */
    static Map<String, String> editions(KMCCore plugin) {
        Map<String, String> m = new HashMap<>();
        try {
            var editions = EditionArchive.readAll(plugin);
            if (editions.isEmpty()) return m;
            m.put("editions", String.valueOf(editions.size()));
            var last = editions.get(editions.size() - 1);
            m.put("last_edition", String.valueOf(last.number()));
            if (last.championName() != null && !last.championName().isBlank()) m.put("last_champion", last.championName());
            Map<String, Integer> titles = new HashMap<>();
            for (var e : editions) {
                if (e.championName() != null && !e.championName().isBlank()) titles.merge(e.championName(), 1, Integer::sum);
            }
            String best = null;
            int bestN = 0;
            for (var en : titles.entrySet()) {
                if (en.getValue() > bestN) { bestN = en.getValue(); best = en.getKey(); }
            }
            if (best != null) {
                m.put("titles_leader", best);
                m.put("titles_count", String.valueOf(bestN));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Host: could not read the edition archive: " + t);
        }
        return m;
    }

    private static void awardValues(Map<String, String> m) {
        List<AwardsEngine.Award> awards;
        try { awards = EventEndSequence.lastAwards(); }
        catch (Throwable t) { return; }
        for (var a : awards) {
            if (a.name() == null || a.name().isBlank()) continue;
            switch (a.id()) {
                case "mvp" -> m.put("award_mvp", a.name());
                case "killer" -> m.put("award_killer", a.name());
                case "winner" -> m.put("award_winner", a.name());
                case "teammate" -> m.put("award_teammate", a.name());
                case "team_comeback" -> m.put("award_comeback", a.name());
                case "team_wins" -> m.put("award_team_wins", a.name());
                default -> { }
            }
        }
    }

    /** Adds the player and team values for {@code player} (either may be null). */
    public static void player(KMCCore plugin, Map<String, String> m, Player player, KMCTeam team) {
        KMCTeam t = team;
        if (player != null) {
            m.put("player", player.getName());
            var data = plugin.getPlayerDataManager().get(player.getUniqueId());
            if (data != null) m.put("player_score", String.valueOf(data.getPoints()));
            if (t == null) t = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        }
        if (t != null) team(plugin, m, t);
    }

    public static void team(KMCCore plugin, Map<String, String> m, KMCTeam t) {
        m.put("team", t.getDisplayName());
        m.put("team_color", t.getColor() == null ? "§f" : t.getColor().toString());
        m.put("team_score", String.valueOf(t.getPoints()));
        var sorted = plugin.getTeamManager().getTeamsSortedByPoints();
        boolean anyPoints = sorted.stream().anyMatch(x -> x.getPoints() > 0);
        int idx = sorted.indexOf(t);
        if (anyPoints && idx >= 0) m.put("leaderboard_position", String.valueOf(idx + 1));
        String slogan = plugin.getHost() != null ? plugin.getHost().slogan(t.getId()) : "";
        if (!slogan.isBlank()) m.put("team_slogan", slogan);
        List<String> names = new ArrayList<>();
        for (UUID id : t.getMembers()) {
            var op = Bukkit.getOfflinePlayer(id);
            if (op.getName() != null) names.add(op.getName());
        }
        if (!names.isEmpty()) m.put("team_members", String.join(", ", names));
    }

    public static void game(Map<String, String> m, KMCGame game, HostContent content) {
        if (game == null) return;
        gameById(m, game.getId(), game.getDisplayName(), content);
    }

    public static void gameById(Map<String, String> m, String gameId, String displayName, HostContent content) {
        m.put("game", displayName != null ? displayName : gameId);
        var reg = registration(gameId);
        reg.ifPresent(r -> {
            if (r.getDescription() != null && !r.getDescription().isBlank()) m.put("game_description", HostText.strip(r.getDescription()));
            if (r.getObjective() != null && !r.getObjective().isBlank()) m.put("game_objective", HostText.strip(r.getObjective()));
        });
        if (!m.containsKey("game_description")) {
            String d = content.gameString(gameId, "description");
            if (!d.isBlank()) m.put("game_description", HostText.strip(d));
        }
        if (!m.containsKey("game_objective")) {
            String o = content.gameString(gameId, "objective");
            if (!o.isBlank()) m.put("game_objective", HostText.strip(o));
        }
        String duration = content.gameString(gameId, "duration");
        if (!duration.isBlank()) m.put("game_time", duration);
    }

    public static ConfigurationSection section(ConfigurationSection parent, String path) {
        return parent == null ? null : parent.getConfigurationSection(path);
    }
}
