package nl.kmc.kmccore.director;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;

import java.util.ArrayList;
import java.util.List;

/**
 * Time Budget System — estimates how long a tournament takes from the game lengths, the pauses and the
 * ceremonies. Pure arithmetic over a {@link Params} record (unit-tested); {@link #fromPlugin} fills the
 * record from config.yml / ceremonies.yml / camera routes.
 *
 * <p>Tunable in config.yml under {@code automation.time-budget}: per-game minutes live in
 * {@code games.list.<id>.estimated-minutes} (falls back to {@code default-game-minutes}).
 */
public final class TimeBudget {

    private TimeBudget() {}

    /** All the inputs of the estimate, in seconds unless noted. */
    public record Params(
            int games,               // regular games (without the finale)
            int gamesPerRound,
            int openingSeconds,      // opening presentation (all stages + the game line-up)
            int intermissionSeconds, // lobby time between games
            int votingSeconds,       // 0 when voting is off
            int prestartSeconds,
            int introSeconds,        // game-intro + arena flyover + tutorial + in-game countdown
            int playSeconds,         // the average game
            int postGameSeconds,     // winner cinematic + leaderboard chain
            int roundEndSeconds,     // round-end ceremony
            int midBreakSeconds,     // 0 = no mid-tournament break
            int readyChecks,         // how many team ready-checks are expected
            int readySeconds,        // average length of one ready check
            boolean finale,
            int finaleSeconds,       // finale intro + game
            int closingSeconds) {    // closing ceremony + winner ceremony + book hand-out
    }

    public record Line(String label, int seconds) {}

    public record Estimate(int totalSeconds, List<Line> lines, int rounds) {
        public String format() { return TimeBudget.format(totalSeconds); }
    }

    // ── Calculation ───────────────────────────────────────────────────────────

    public static Estimate compute(Params p) {
        int games = Math.max(0, p.games());
        int gpr = Math.max(1, p.gamesPerRound());
        int rounds = (int) Math.ceil(games / (double) gpr);

        List<Line> lines = new ArrayList<>();
        lines.add(new Line("Openingspresentatie", p.openingSeconds()));

        int perGameOverhead = p.intermissionSeconds() + p.votingSeconds() + p.prestartSeconds()
                + p.introSeconds() + p.postGameSeconds();
        lines.add(new Line("Games spelen (" + games + " × " + format(p.playSeconds()) + ")",
                games * p.playSeconds()));
        lines.add(new Line("Voorbereiding per game (pauze, stemmen, intro, uitslag: "
                + format(perGameOverhead) + " × " + games + ")", games * perGameOverhead));
        lines.add(new Line("Ronde-ceremonies (" + rounds + " rondes)", rounds * p.roundEndSeconds()));
        if (p.midBreakSeconds() > 0 && rounds > 1) lines.add(new Line("Halverwege-pauze", p.midBreakSeconds()));
        if (p.readyChecks() > 0) lines.add(new Line("Ready-checks (" + p.readyChecks() + ")",
                p.readyChecks() * p.readySeconds()));
        if (p.finale()) lines.add(new Line("Finale", p.finaleSeconds()));
        lines.add(new Line("Afsluiting (ceremonie, boek)", p.closingSeconds()));

        int total = 0;
        for (Line l : lines) total += l.seconds();
        return new Estimate(total, lines, rounds);
    }

    /** {@code 4500} → {@code "1u 15m"}, {@code 90} → {@code "1m 30s"}. */
    public static String format(int seconds) {
        seconds = Math.max(0, seconds);
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + "u " + m + "m";
        if (m > 0) return s == 0 ? m + "m" : m + "m " + s + "s";
        return s + "s";
    }

    // ── From the live configuration ───────────────────────────────────────────

    /** Builds the parameters for a tournament of {@code games} regular games from the live configuration. */
    public static Params fromPlugin(KMCCore plugin, int games, int gamesPerRound) {
        var cfg = plugin.getConfig();
        String tb = "automation.time-budget.";

        // Opening presentation: every stage, the line-up on top.
        int opening = 0;
        var cm = plugin.getCeremonyManager();
        for (String stage : List.of("opening", "how-it-works", "tournament-overview", "team-showcase", "game-lineup")) {
            int d = cm != null ? cm.getDuration(stage, 10) : 10;
            opening += Math.max(d, cfg.getInt(tb + "min-stage-seconds", 12));
        }
        List<KMCGame> enabled = plugin.getGameManager().getEnabledGames();
        int perCard = cm != null ? cm.getSecondsPerGame() : 6;
        opening += enabled.size() * Math.max(2, perCard);
        opening += cfg.getInt(tb + "opening-extra-seconds", 30);   // language screen + team showcase staging

        // Average game length over the games in the rotation.
        double defMin = cfg.getDouble(tb + "default-game-minutes", 7.0);
        double sumMin = 0;
        for (KMCGame g : enabled) sumMin += cfg.getDouble("games.list." + g.getId() + ".estimated-minutes", defMin);
        int play = (int) Math.round((enabled.isEmpty() ? defMin : sumMin / enabled.size()) * 60);

        boolean voting = cfg.getBoolean("games.voting-enabled", true);
        int midBreak = cfg.getBoolean("automation.mid-break.enabled", true)
                ? cfg.getInt("automation.mid-break.seconds", 300) : 0;

        String readyMode = cfg.getString("automation.ready-check.mode", "mid-break");
        int rounds = (int) Math.ceil(Math.max(0, games) / (double) Math.max(1, gamesPerRound));
        int readyChecks = switch (readyMode.toLowerCase()) {
            case "every-break" -> Math.max(0, games);
            case "off" -> 0;
            default -> midBreak > 0 && rounds > 1 ? 1 : 0;
        };
        boolean finale = cfg.getBoolean("automation.finale.enabled", true);
        if (finale && !readyMode.equalsIgnoreCase("off")) readyChecks++;

        int finaleSeconds = cfg.getInt("automation.finale.intro-seconds", 30)
                + (int) Math.round(cfg.getDouble("games.list.dodgebolt.estimated-minutes", 8.0) * 60);

        return new Params(
                Math.max(0, games), Math.max(1, gamesPerRound), opening,
                cfg.getInt("automation.intermission-seconds", 30),
                voting ? cfg.getInt("games.voting-duration", 30) : 0,
                cfg.getInt("automation.prestart-seconds", 10),
                cfg.getInt(tb + "game-intro-seconds", 45),
                play,
                cfg.getInt(tb + "post-game-seconds", 40),
                cfg.getInt(tb + "round-end-seconds", 15),
                midBreak, readyChecks, cfg.getInt(tb + "ready-seconds", 25),
                finale, finaleSeconds,
                cfg.getInt(tb + "closing-seconds", 75));
    }

    /** Convenience: the estimate for the tournament as configured right now. */
    public static Estimate forCurrent(KMCCore plugin, int games) {
        int gpr = plugin.getConfig().getInt("automation.games-per-round", 2);
        if (gpr <= 0) gpr = 2;
        return compute(fromPlugin(plugin, games, gpr));
    }
}
