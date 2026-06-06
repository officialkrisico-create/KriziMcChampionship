package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tournament automation engine.
 *
 * <p>FIX — auto-skip unconfigured games: {@link #enterPreStart(KMCGame)}
 * now checks arena readiness. If the next game has no arena configured,
 * it's skipped and the engine picks another. Prevents teleporting
 * players to null locations or pasting missing schematics.
 */
public class AutomationManager {

    public enum State { IDLE, GAME_ACTIVE, INTERMISSION, VOTING, PRE_START, PAUSED }

    private final KMCCore plugin;

    private State      state            = State.IDLE;
    private State      stateBeforePause = State.IDLE;
    private int        gamesThisRound   = 0;
    private int        countdownSeconds = 0;

    private BukkitTask tickTask;
    private BossBar    bossBar;

    /** Games already attempted this cycle (avoid infinite skip loops). */
    private final Set<String> attemptedThisCycle = new HashSet<>();

    // Repetition tracking — how many times the current game plays back-to-back.
    private String currentRepGameId  = null;
    private int    currentRepetition = 0;
    private int    maxRepetitions    = 1;

    // Scheduled auto-start (clock-time start of the whole ceremony flow).
    private BukkitTask scheduledStartTask;
    private long       scheduledStartAtMs;

    public AutomationManager(KMCCore plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Control
    // ----------------------------------------------------------------

    public void start() {
        if (state != State.IDLE) return;
        gamesThisRound = 0;
        attemptedThisCycle.clear();
        createBossBar();
        // Opening presentation sequence. Each STAGE runs in turn with a real gap
        // between them (its ceremonies.yml duration), and each stage's chat lines
        // are revealed one at a time — no more wall-of-text dumped at once.
        ceremonyStage("opening", () ->
            ceremonyStage("team-showcase", () ->
                ceremonyStage("tournament-overview", this::enterIntermission)));
    }

    /**
     * Runs one presentation stage: plays its camera route (if any), then reveals
     * the stage's chat lines spaced out + shows its title, then waits the stage's
     * configured duration before running {@code after}.
     */
    private void ceremonyStage(String phase, Runnable after) {
        playCinematic(phase, () -> {
            var cm = plugin.getCeremonyManager();
            if (cm == null) { after.run(); return; }
            Map<String, String> ph = basePlaceholders();
            showCeremonyTitle(cm.getTitle(phase, ph), cm.getSubtitle(phase, ph));
            List<String> messages = new ArrayList<>(cm.getMessages(phase, ph));
            if (phase.equals("team-showcase")) messages.addAll(buildTeamShowcaseLines());
            long lastLineTick = broadcastSpaced(messages);
            int durSec = cm.getDuration(phase, 8);
            long delay = Math.max(durSec * 20L, lastLineTick + 30L);
            Bukkit.getScheduler().runTaskLater(plugin, after, delay);
        });
    }

    /** Ticks between consecutive ceremony chat lines. */
    private static final long LINE_DELAY_TICKS = 30L; // ~1.5s

    /**
     * Broadcasts each line spaced {@value #LINE_DELAY_TICKS} ticks apart.
     * Returns the tick offset of the LAST line (0 if empty).
     */
    private long broadcastSpaced(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.broadcastMessage(line), i * LINE_DELAY_TICKS);
        }
        return (long) Math.max(0, lines.size() - 1) * LINE_DELAY_TICKS;
    }

    public void stop() {
        cancelTick();
        hideBossBar();
        state = State.IDLE;
    }

    // ----------------------------------------------------------------
    // Scheduled auto-start (the whole ceremony flow at a clock time)
    // ----------------------------------------------------------------

    /**
     * Schedules the full tournament (ceremonies + automation) to begin after
     * {@code delayTicks}. Replaces any existing schedule. Broadcasts reminders
     * at 5 min / 1 min / 10 s before start.
     */
    public void scheduleStart(long delayTicks) {
        cancelScheduledStart();
        if (delayTicks <= 0) { fireScheduledStart(); return; }
        scheduledStartAtMs = System.currentTimeMillis() + delayTicks * 50L;

        // Reminder broadcasts before the start.
        long[] remindersBeforeTicks = { 6000L, 1200L, 200L }; // 5 min, 1 min, 10 s
        for (long before : remindersBeforeTicks) {
            long at = delayTicks - before;
            if (at <= 0) continue;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                long secsLeft = Math.max(1, (scheduledStartAtMs - System.currentTimeMillis()) / 1000);
                broadcast("&6[KMC] &eToernooi start over &6" + formatDuration(secsLeft) + "&e!");
            }, at);
        }

        scheduledStartTask = Bukkit.getScheduler().runTaskLater(plugin, this::fireScheduledStart, delayTicks);
    }

    private void fireScheduledStart() {
        scheduledStartTask = null;
        scheduledStartAtMs = 0;
        if (state != State.IDLE) return; // already running
        if (!plugin.getTournamentManager().isActive()) plugin.getTournamentManager().start();
        start();
    }

    public void cancelScheduledStart() {
        if (scheduledStartTask != null) { scheduledStartTask.cancel(); scheduledStartTask = null; }
        scheduledStartAtMs = 0;
    }

    public boolean hasScheduledStart()    { return scheduledStartTask != null; }
    public long    scheduledStartInMs()   { return scheduledStartTask != null ? Math.max(0, scheduledStartAtMs - System.currentTimeMillis()) : -1; }

    private static String formatDuration(long seconds) {
        if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

    public void onGameEnd(String winnerName) {
        if (state != State.GAME_ACTIVE && state != State.PAUSED) {
            if (state == State.IDLE) return;
        }
        // Winner ceremony cinematic for the game that just finished, then continue.
        // (Active game is already cleared by stopGame, so use the tracked rep id.)
        String winnerRoute = currentRepGameId != null ? "winner-" + currentRepGameId : "winner";
        playCinematic(winnerRoute, () -> continueGameEnd(winnerName));
    }

    /** Runs the post-game flow after the winner cinematic finishes. */
    private void continueGameEnd(String winnerName) {
        ceremony("game-end");

        // ── Repetitions: replay the same game until maxRepetitions reached ──
        if (currentRepGameId != null && currentRepetition < maxRepetitions) {
            currentRepetition++;
            KMCGame same = plugin.getGameManager().getGame(currentRepGameId);
            int done = currentRepetition - 1;
            broadcast("&6[KMC] &e" + (same != null ? same.getDisplayName() : currentRepGameId)
                    + " &7— spel &e" + done + "&7/&e" + maxRepetitions
                    + " &7klaar. Volgende start zo...");
            plugin.getArenaManager().teleportAllToLobby();

            int interSec = plugin.getConfig().getInt("automation.repetition-intermission-seconds", 20);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (same != null) enterPreStart(same);   // re-runs intro + arena + countdown + launch
                else proceedAfterGame(winnerName);
            }, interSec * 20L);
            return;
        }

        // All repetitions done — clear tracking and advance the rotation.
        currentRepGameId  = null;
        currentRepetition = 0;
        maxRepetitions    = 1;
        proceedAfterGame(winnerName);
    }

    /** Advances the round counter and moves to the next game / round / finale. */
    private void proceedAfterGame(String winnerName) {
        gamesThisRound++;
        attemptedThisCycle.clear();

        int gamesPerRound = plugin.getConfig().getInt("automation.games-per-round", 3);
        boolean roundComplete = false;
        if (gamesThisRound >= gamesPerRound) {
            gamesThisRound = 0;
            roundComplete  = true;
            if (!plugin.getTournamentManager().nextRound()) {
                endTournament(winnerName);
                return;
            }
        }

        if (roundComplete) ceremony("round-end");

        plugin.getArenaManager().teleportAllToLobby();
        postGameLeaderboardChain();
        createBossBar();
        enterIntermission();
    }

    public void pause() {
        if (state == State.PAUSED || state == State.IDLE) return;
        stateBeforePause = state;
        state = State.PAUSED;
        cancelTick();
        hideBossBar();
        broadcast("&6[KMC] &eAutomatisering gepauzeerd door een admin.");
    }

    public void resume() {
        if (state != State.PAUSED) return;
        state = stateBeforePause;
        createBossBar();
        switch (state) {
            case INTERMISSION -> enterIntermission();
            case VOTING       -> enterVoting();
            case PRE_START    -> enterPreStart(plugin.getGameManager().getNextGame());
            default           -> {}
        }
        broadcast("&6[KMC] &aAutomatisering hervat.");
    }

    // ----------------------------------------------------------------
    // State machine
    // ----------------------------------------------------------------

    private void enterIntermission() {
        state            = State.INTERMISSION;
        countdownSeconds = plugin.getConfig().getInt("automation.intermission-seconds", 30);

        boolean votingEnabled = plugin.getConfig().getBoolean("games.voting-enabled", true);
        String label = votingEnabled
                ? "Volgende game wordt gekozen over"
                : "Volgende game start over";

        setBossBar(label + " " + countdownSeconds + "s", BarColor.YELLOW, 1.0);
        broadcast("&6[KMC] &eTussenpauze! Volgende game start over &6"
                + countdownSeconds + " &eseconden.");

        startTick(() -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("automation.intermission-seconds", 30);
            setBossBarProgress(progress);
            playTickSound(countdownSeconds);
            bossBar.setTitle(MessageUtil.color("&eTussenpauze: &6" + countdownSeconds + "s"));

            if (countdownSeconds <= 0) {
                cancelTick();
                if (plugin.getConfig().getBoolean("games.voting-enabled", true)) {
                    enterVoting();
                } else {
                    KMCGame next = plugin.getGameManager().randomNextGame();
                    enterPreStart(next);
                }
            }
        });
    }

    private void enterVoting() {
        state            = State.VOTING;
        countdownSeconds = plugin.getConfig().getInt("games.voting-duration", 30);

        setBossBar("Stem voor de volgende game!", BarColor.BLUE, 1.0);
        plugin.getGameManager().startVote();

        startTick(() -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("games.voting-duration", 30);
            setBossBarProgress(progress);
            bossBar.setTitle(MessageUtil.color("&bStemmen sluiten over: &e" + countdownSeconds + "s"));
            playTickSound(countdownSeconds);

            if (countdownSeconds <= 0) {
                cancelTick();
                plugin.getGameManager().endVote();
                KMCGame next = plugin.getGameManager().getNextGame();
                if (next == null) next = plugin.getGameManager().randomNextGame();
                enterPreStart(next);
            }
        });
    }

    /**
     * Pre-start countdown. If the picked game isn't configured (no arena),
     * auto-skip and pick another.
     */
    private void enterPreStart(KMCGame game) {
        if (game == null) {
            broadcast("&c[KMC] Geen game beschikbaar! Automatisering en toernooi gestopt.");
            stop();
            stopTournamentCleanly();
            return;
        }

        // AUTO-SKIP check — game must be ready
        if (!isGameReadyUnified(game.getId())) {
            attemptedThisCycle.add(game.getId());
            String reason = readinessReasonUnified(game.getId());
            broadcast("&e[KMC] &7Game &e" + game.getDisplayName()
                    + " &7is niet geconfigureerd (" + reason + "). Overslaan...");

            // Try another game — avoid already-attempted ones
            KMCGame fallback = pickNextReadyGame();
            if (fallback == null) {
                broadcast("&c[KMC] Geen enkele game is correct geconfigureerd! Automatisering en toernooi gestopt.");
                broadcast("&7Controleer de setup via &e/kmcsetup &7of &e/kmcvalidate &7en probeer opnieuw.");
                stop();
                stopTournamentCleanly();
                return;
            }
            enterPreStart(fallback);  // recurse with a ready game
            return;
        }

        state            = State.PRE_START;
        countdownSeconds = plugin.getConfig().getInt("automation.prestart-seconds", 10);

        setBossBar("&a" + game.getDisplayName() + " start over " + countdownSeconds + "s",
                BarColor.GREEN, 1.0);
        broadcast("&6[KMC] &a" + game.getDisplayName() + " &estart over &6"
                + countdownSeconds + " &eseconden!");

        startTick(() -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("automation.prestart-seconds", 10);
            setBossBarProgress(progress);
            bossBar.setTitle(MessageUtil.color(
                    "&a" + game.getDisplayName() + " &estart over &6" + countdownSeconds + "s"));

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(MessageUtil.color("&a" + game.getDisplayName()),
                            MessageUtil.color("&eStart over &6" + countdownSeconds + "s"),
                            0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f,
                            0.5f + (5 - countdownSeconds) * 0.1f);
                }
            }

            if (countdownSeconds <= 0) {
                cancelTick();
                // Game showcase ceremony text, then game-intro + arena flyover
                // cinematics, then launch. Cinematics/ceremony are no-ops if
                // unconfigured, so the game launches immediately in that case.
                ceremonyGame("game-intro", game);
                playCinematicChain(
                        List.of("game-intro-" + game.getId(), "arena-" + game.getId()),
                        () -> launchGame(game));
            }
        });
    }

    /**
     * Picks the next playable game, excluding ones we've already tried
     * this cycle and ones not configured.
     */
    private KMCGame pickNextReadyGame() {
        for (KMCGame candidate : plugin.getGameManager().getAvailableGames()) {
            if (attemptedThisCycle.contains(candidate.getId())) continue;
            if (isGameReadyUnified(candidate.getId())) {
                return candidate;
            }
            attemptedThisCycle.add(candidate.getId());
        }
        return null;
    }

    /**
     * Unified game-readiness check — the SINGLE source of truth, shared with
     * {@code /kmcsetup} and {@code /kmcvalidate}. Prefers the game's own
     * registered {@link nl.kmc.core.setup.GameSetup} (so games that manage
     * their own arena, like QuakeCraft via {@code /qc}, report correctly) and
     * only falls back to the KMCCore {@code /kmcarena} check for games without
     * a registered setup.
     */
    private boolean isGameReadyUnified(String gameId) {
        var setup = lookupSetup(gameId);
        if (setup != null) {
            try { return setup.isReady(); } catch (Throwable ignored) {}
        }
        return plugin.getArenaManager().isGameReady(gameId);
    }

    private String readinessReasonUnified(String gameId) {
        var setup = lookupSetup(gameId);
        if (setup != null) {
            try {
                var issues = setup.issues();
                return (issues == null || issues.isEmpty()) ? "klaar" : String.join(", ", issues);
            } catch (Throwable ignored) {}
        }
        return plugin.getArenaManager().getReadinessReason(gameId);
    }

    private nl.kmc.core.setup.GameSetup lookupSetup(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            try {
                var svc = v2.getContainer().get(nl.kmc.core.setup.SetupService.class);
                if (svc != null) return svc.get(gameId).orElse(null);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void launchGame(KMCGame game) {
        // Initialise repetition tracking the first time a NEW game launches.
        // Re-launches of the same game (for repetitions) keep the counter.
        if (!game.getId().equals(currentRepGameId)) {
            currentRepGameId  = game.getId();
            currentRepetition = 1;
            maxRepetitions    = Math.max(1, plugin.getConfig().getInt(
                    "games.list." + game.getId() + ".repetitions", 1));
        }

        state = State.GAME_ACTIVE;
        attemptedThisCycle.clear();
        // Hide the automation bossbar entirely while the game runs — the game
        // shows its OWN bossbar + scoreboard, so we don't stack a second one.
        hideBossBar();
        plugin.getGameManager().startGame(game.getId());

        if (maxRepetitions > 1) {
            broadcast("&6[KMC] &e" + game.getDisplayName() + " &7— spel &e"
                    + currentRepetition + "&7/&e" + maxRepetitions);
        }
    }

    private void endTournament(String lastWinner) {
        stop();
        plugin.getTournamentManager().endTournament();

        String topTeam = plugin.getTeamManager().getTeamsSortedByPoints().stream()
                .findFirst().map(t -> t.getColor() + t.getDisplayName())
                .orElse("Onbekend");

        // Finale cinematic (no-op if unconfigured), then closing ceremony + winner.
        playCinematic("closing", () -> {
            ceremony("closing");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(MessageUtil.color("&6&lToernooi Afgelopen!"),
                        MessageUtil.color("&eWinnaar: " + topTeam), 10, 100, 30);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            broadcast("&6&l[KMC] &eHet toernooi is afgelopen! Winnaar: " + topTeam);
        });
    }

    // ----------------------------------------------------------------
    // Post-game leaderboard chain (unchanged)
    // ----------------------------------------------------------------

    private void postGameLeaderboardChain() {
        String gameName = "Laatste Game";
        KMCGame game = plugin.getGameManager().getActiveGame();
        if (game == null) {
            var played = plugin.getGameManager().getPlayedGamesThisTournament();
            if (!played.isEmpty()) {
                String lastId = played.stream().reduce((a, b) -> b).orElse(null);
                if (lastId != null && plugin.getGameManager().getGame(lastId) != null) {
                    gameName = plugin.getGameManager().getGame(lastId).getDisplayName();
                }
            }
        } else {
            gameName = game.getDisplayName();
        }

        final String finalGameName = gameName;

        Bukkit.getScheduler().runTaskLater(plugin, () -> broadcastTopPlayers(finalGameName), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> broadcastGameTeamLeaderboard(finalGameName), 200L);
        Bukkit.getScheduler().runTaskLater(plugin, this::broadcastOverallTeamLeaderboard, 400L);
    }

    private void broadcastTopPlayers(String gameName) {
        broadcast("&6═══════════════════════════════════");
        broadcast("&e&lTop Spelers &7— " + gameName);
        broadcast("&6═══════════════════════════════════");
        List<PlayerData> top = plugin.getPlayerDataManager().getLeaderboard().stream().limit(5).toList();
        if (top.isEmpty()) broadcast("&7Geen data beschikbaar.");
        else for (int i = 0; i < top.size(); i++) {
            PlayerData pd = top.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " &f" + pd.getName() + " &8- &e" + pd.getPoints() + " punten");
        }
        broadcast("&6═══════════════════════════════════");
    }

    private void broadcastGameTeamLeaderboard(String gameName) {
        broadcast("&6═══════════════════════════════════");
        broadcast("&e&lTop Teams &7— " + gameName);
        broadcast("&6═══════════════════════════════════");
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints().stream().limit(5).toList();
        if (teams.isEmpty()) broadcast("&7Geen teams actief.");
        else for (int i = 0; i < teams.size(); i++) {
            KMCTeam t = teams.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints() + " punten");
        }
        broadcast("&6═══════════════════════════════════");
    }

    private void broadcastOverallTeamLeaderboard() {
        broadcast("&6═══════════════════════════════════");
        broadcast("&d&l🏆 TOTAAL TOERNOOI KLASSEMENT 🏆");
        broadcast("&6═══════════════════════════════════");
        List<KMCTeam> all = plugin.getTeamManager().getTeamsSortedByPoints();
        if (all.isEmpty()) broadcast("&7Geen teams geregistreerd.");
        else for (int i = 0; i < all.size(); i++) {
            KMCTeam t = all.get(i);
            String medal = i == 0 ? "&6#1" : i == 1 ? "&7#2" : i == 2 ? "&c#3" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints());
        }
        broadcast("&6═══════════════════════════════════");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void startTick(Runnable onTick) {
        cancelTick();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, onTick, 20L, 20L);
    }
    private void cancelTick() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
    }

    private void createBossBar() {
        if (bossBar != null) hideBossBar();
        bossBar = Bukkit.createBossBar("KMC", BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        bossBar.setVisible(true);
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        bossBar.setVisible(false);
        bossBar.removeAll();
        bossBar = null;
    }

    private void setBossBar(String title, BarColor color, double progress) {
        if (bossBar == null) return;
        bossBar.setTitle(MessageUtil.color(title));
        bossBar.setColor(color);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    private void setBossBarProgress(double progress) {
        if (bossBar == null) return;
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    public void addPlayerToBossBar(Player player) {
        if (bossBar != null) bossBar.addPlayer(player);
    }

    private void playTickSound(int secondsLeft) {
        if (secondsLeft != 10 && secondsLeft != 5 && secondsLeft > 3) return;
        if (secondsLeft <= 0) return;
        Sound s = secondsLeft <= 3 ? Sound.BLOCK_NOTE_BLOCK_BASS : Sound.BLOCK_NOTE_BLOCK_HAT;
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), s, 0.8f, 1f);
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(MessageUtil.color(msg));
    }

    // ----------------------------------------------------------------
    // Cinematic presentation hooks
    // ----------------------------------------------------------------

    /**
     * Plays a single camera route for all players, then runs {@code after}.
     * If the route is missing/empty (or CinematicManager unavailable), runs
     * {@code after} immediately — the tournament continues safely.
     */
    private void playCinematic(String routeId, Runnable after) {
        var cm = plugin.getCinematicManager();
        if (cm != null && cm.routeExists(routeId)) {
            cm.playRouteForAll(routeId, after);
        } else {
            after.run();
        }
    }

    /**
     * Plays a list of camera routes back-to-back, then runs {@code after}.
     * Any route not configured is skipped. Used for multi-stage sequences
     * like the opening ceremony (opening → team-showcase → tournament-overview).
     */
    private void playCinematicChain(List<String> routeIds, Runnable after) {
        if (routeIds == null || routeIds.isEmpty()) { after.run(); return; }
        playCinematic(routeIds.get(0),
                () -> playCinematicChain(routeIds.subList(1, routeIds.size()), after));
    }

    /**
     * Broadcasts the ceremony text (chat lines + title/subtitle) for a phase,
     * driven by {@code ceremonies.yml}. No-op if CeremonyManager is unavailable.
     */
    private void ceremony(String phase) {
        var cm = plugin.getCeremonyManager();
        if (cm == null) return;
        Map<String, String> ph = basePlaceholders();
        showCeremonyTitle(cm.getTitle(phase, ph), cm.getSubtitle(phase, ph));
        broadcastSpaced(cm.getMessages(phase, ph)); // reveal lines one at a time
    }

    /** Like {@link #ceremony(String)} but adds game_name / game_objective placeholders. */
    private void ceremonyGame(String phase, KMCGame game) {
        var cm = plugin.getCeremonyManager();
        if (cm == null) return;
        Map<String, String> ph = basePlaceholders();
        ph.put("game_name", game.getDisplayName());
        ph.put("game_objective", lookupObjective(game.getId()));
        cm.getMessages(phase, ph).forEach(Bukkit::broadcastMessage);
        showCeremonyTitle(cm.getTitle(phase, ph), cm.getSubtitle(phase, ph));
    }

    /**
     * Auto-generates the "competing teams" roster for the team-showcase
     * ceremony: each team in standings order with its players (online players
     * highlighted). Pulled live from the TeamManager so it always matches the
     * actual teams + members assigned for the event.
     */
    private List<String> buildTeamShowcaseLines() {
        List<String> out = new ArrayList<>();
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        if (teams.isEmpty()) {
            out.add(MessageUtil.color("  &7Geen teams ingesteld — gebruik &e/kmcrandomteams&7."));
            return out;
        }
        for (var team : teams) {
            var members = team.getMembers();
            out.add(MessageUtil.color(team.getColor() + "&l" + team.getDisplayName()
                    + " &7(" + members.size() + (members.size() == 1 ? " speler)" : " spelers)")));
            if (members.isEmpty()) { out.add(MessageUtil.color("   &8• (nog geen spelers)")); continue; }
            List<String> names = new ArrayList<>();
            for (java.util.UUID id : members) {
                var op = Bukkit.getOfflinePlayer(id);
                String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
                names.add((op.isOnline() ? "&a" : "&7") + name);
            }
            out.add(MessageUtil.color("   &8• " + String.join("&7, ", names)));
        }
        return out;
    }

    private Map<String, String> basePlaceholders() {
        Map<String, String> m = new HashMap<>();
        m.put("tournament_name", plugin.getConfig().getString("tournament.name", "KMC Tournament"));
        m.put("round",       String.valueOf(plugin.getTournamentManager().getCurrentRound()));
        m.put("multiplier",  String.valueOf(plugin.getTournamentManager().getMultiplier()));
        m.put("team_count",  String.valueOf(plugin.getTeamManager().getTeamsSortedByPoints().size()));
        return m;
    }

    private void showCeremonyTitle(String title, String subtitle) {
        boolean noTitle = (title == null || title.isBlank());
        boolean noSub   = (subtitle == null || subtitle.isBlank());
        if (noTitle && noSub) return;
        String t  = noTitle ? "" : title;
        String st = noSub   ? "" : subtitle;
        for (Player p : Bukkit.getOnlinePlayers()) p.sendTitle(t, st, 5, 60, 10);
    }

    /** Looks up a game's objective text from the V2 registry, or "" if unavailable. */
    private String lookupObjective(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            var reg = v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class).get(gameId);
            if (reg.isPresent()) return reg.get().getObjective();
        }
        return "";
    }

    /**
     * Cleanly ends the tournament with a full closing ceremony — picks a
     * winner (whoever has the most points, even if zero), resets all
     * stats, and broadcasts the standard "TOERNOOI AFGELOPEN" announcement.
     * Used when automation halts due to misconfigured games.
     */
    private void stopTournamentCleanly() {
        try {
            if (plugin.getTournamentManager() != null
                    && plugin.getTournamentManager().isActive()) {
                plugin.getTournamentManager().endTournament();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to end tournament cleanly: " + t.getMessage());
        }
    }

    public State   getState()            { return state; }
    public boolean isRunning()           { return state != State.IDLE && state != State.PAUSED; }
    public int     getCountdownSeconds() { return countdownSeconds; }
}