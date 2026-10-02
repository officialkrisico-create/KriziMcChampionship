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

    /** Stages of the opening presentation, in order (each is a section of ceremonies.yml). */
    private static final List<String> OPENING_STAGES = List.of(
            "opening", "how-it-works", "tournament-overview", "team-showcase", "game-lineup");

    private static final Map<String, String> STAGE_LABELS = Map.of(
            "opening",             "Opening",
            "how-it-works",        "Spelregels",
            "tournament-overview", "Rondes & punten",
            "team-showcase",       "De teams",
            "game-lineup",         "De games");

    private static final BarColor[] STAGE_COLORS = {
            BarColor.YELLOW, BarColor.BLUE, BarColor.PINK, BarColor.GREEN, BarColor.PURPLE };

    // Opening-presentation bookkeeping: every scheduled step carries the generation it was
    // scheduled in, so stop()/skip simply bump the counter and anything still pending is a no-op.
    private int                  ceremonyGeneration;
    private boolean              ceremonyActive;
    private final List<BukkitTask> ceremonyTasks = new ArrayList<>();
    private BukkitTask           stageBarTask;

    public void start() {
        if (state != State.IDLE || ceremonyActive) return;
        gamesThisRound = 0;
        attemptedThisCycle.clear();
        createBossBar();
        // Opening presentation: opening → how it works → rounds & points → teams → games.
        // Each stage shows its title, reveals its chat lines one at a time (with a soft chime),
        // keeps a progress bar, and always leaves a read-pause before the next stage.
        ceremonyActive = true;
        final int gen = ++ceremonyGeneration;
        runCeremonyStage(gen, 0, () -> {
            ceremonyActive = false;
            cancelStageBar();
            enterIntermission();
        });
    }

    /** True while the opening presentation is still playing. */
    public boolean isCeremonyActive() { return ceremonyActive; }

    /** Skips the rest of the opening presentation and goes straight to the first intermission. */
    public boolean skipCeremony() {
        if (!ceremonyActive) return false;
        cancelCeremony();
        for (Player p : Bukkit.getOnlinePlayers()) p.clearTitle();
        broadcast("&6[KMC] &7Presentatie overgeslagen door een admin.");
        enterIntermission();
        return true;
    }

    private void cancelCeremony() {
        boolean wasActive = ceremonyActive;
        ceremonyGeneration++;
        ceremonyActive = false;
        ceremonyTasks.forEach(BukkitTask::cancel);
        ceremonyTasks.clear();
        cancelStageBar();
        if (wasActive) {
            var cm = plugin.getCinematicManager();
            if (cm != null) cm.stopAll();
        }
    }

    private void ceremonyLater(int gen, long delayTicks, Runnable r) {
        ceremonyTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gen == ceremonyGeneration) r.run();
        }, Math.max(0L, delayTicks)));
    }

    /**
     * Runs one stage of the opening presentation: plays its camera route (if any), shows its
     * title, reveals its lines spaced out, then waits at least the configured duration AND a
     * read buffer after the last line before moving to the next stage.
     */
    private void runCeremonyStage(int gen, int idx, Runnable done) {
        if (gen != ceremonyGeneration) return;
        if (idx >= OPENING_STAGES.size()) { done.run(); return; }

        String phase = OPENING_STAGES.get(idx);
        ceremonyTasks.clear();
        Runnable next = () -> runCeremonyStage(gen, idx + 1, done);

        playCinematic(phase, () -> {
            if (gen != ceremonyGeneration) return;
            var cm = plugin.getCeremonyManager();
            if (cm == null) { next.run(); return; }

            Map<String, String> ph = basePlaceholders();
            showCeremonyTitle(cm.getTitle(phase, ph), cm.getSubtitle(phase, ph));
            playStageSound(idx);

            long lineDelay = cm.getLineDelayTicks();
            long lastTick  = revealLines(gen, cm.getMessages(phase, ph), 0L, lineDelay);

            switch (phase) {
                case "tournament-overview" ->
                    lastTick = Math.max(lastTick, revealLines(gen, buildMultiplierLadderLines(),
                            lastTick + lineDelay, lineDelay));
                case "team-showcase" ->
                    lastTick = Math.max(lastTick, revealBlocks(gen, buildTeamShowcaseBlocks(),
                            lastTick + lineDelay, lineDelay));
                case "game-lineup" ->
                    lastTick = Math.max(lastTick, scheduleGameLineup(gen, cm, lastTick + lineDelay));
                default -> { }
            }

            long total = Math.max(cm.getDuration(phase, 8) * 20L, lastTick + cm.getReadBufferTicks());
            startStageBar(gen, idx, phase, total);
            ceremonyLater(gen, total, next);
        });
    }

    /** Reveals {@code lines} one by one starting at {@code startTick}; returns the tick of the last one. */
    private long revealLines(int gen, List<String> lines, long startTick, long delay) {
        if (lines.isEmpty()) return startTick;
        List<List<String>> blocks = new ArrayList<>();
        for (String l : lines) blocks.add(List.of(l));
        return revealBlocks(gen, blocks, startTick, delay);
    }

    /** Like {@link #revealLines} but every block (e.g. one team) appears at once, with one chime. */
    private long revealBlocks(int gen, List<List<String>> blocks, long startTick, long delay) {
        long tick = startTick;
        for (int i = 0; i < blocks.size(); i++) {
            List<String> block = blocks.get(i);
            tick = startTick + i * delay;
            ceremonyLater(gen, tick, () -> {
                block.forEach(Bukkit::broadcastMessage);
                if (block.stream().anyMatch(l -> !l.contains("§m"))) chime();
            });
        }
        return tick;
    }

    /** Spotlights each enabled game in turn: chat lines (name, description, goal) plus a title. */
    private long scheduleGameLineup(int gen, CeremonyManager cm, long startTick) {
        List<KMCGame> games = plugin.getGameManager().getEnabledGames();
        long per = cm.getSecondsPerGame() * 20L;
        for (int i = 0; i < games.size(); i++) {
            KMCGame g = games.get(i);
            String objective   = lookupObjective(g.getId());
            String description = lookupDescription(g.getId());
            int n = i + 1, total = games.size();
            ceremonyLater(gen, startTick + i * per, () -> {
                String name = g.getDisplayName();
                Bukkit.broadcastMessage(MessageUtil.color("  &a&l" + n + "/" + total + " &8» &e&l" + name));
                if (!description.isBlank())
                    Bukkit.broadcastMessage(MessageUtil.color("        &7" + description));
                if (!objective.isBlank())
                    Bukkit.broadcastMessage(MessageUtil.color("        &7Doel: &f" + objective));
                String sub = !objective.isBlank() ? objective : description;
                float pitch = 0.8f + 0.7f * n / total;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(MessageUtil.color("&e&l" + name), MessageUtil.color("&7" + shorten(sub, 60)),
                            5, (int) Math.max(20, per - 15), 10);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
                }
            });
        }
        return startTick + (long) Math.max(0, games.size() - 1) * per + per;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }

    private void chime() {
        for (Player p : Bukkit.getOnlinePlayers())
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 1.5f);
    }

    private void playStageSound(int idx) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (idx == 0) {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1f);
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1f);
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 0.8f + 0.1f * idx);
            }
        }
    }

    /** Boss bar shows which stage of the presentation this is and drains over the stage's length. */
    private void startStageBar(int gen, int idx, String phase, long totalTicks) {
        cancelStageBar();
        setBossBar("&6&lKMC &8| &e" + STAGE_LABELS.getOrDefault(phase, phase)
                        + " &8(&7" + (idx + 1) + "/" + OPENING_STAGES.size() + "&8)",
                STAGE_COLORS[idx % STAGE_COLORS.length], 1.0);
        final long[] elapsed = {0};
        stageBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (gen != ceremonyGeneration || bossBar == null) { cancelStageBar(); return; }
            elapsed[0] += 10;
            setBossBarProgress(1.0 - (double) elapsed[0] / Math.max(1, totalTicks));
        }, 10L, 10L);
    }

    private void cancelStageBar() {
        if (stageBarTask != null) { stageBarTask.cancel(); stageBarTask = null; }
    }

    /** Multiplier per round, from tournament.multipliers — shown as a compact ladder. */
    private List<String> buildMultiplierLadderLines() {
        var sec = plugin.getConfig().getConfigurationSection("tournament.multipliers");
        if (sec == null) return List.of();
        int rounds = plugin.getTournamentManager().getTotalRounds();
        List<String> parts = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            String key = String.valueOf(r);
            if (!sec.isSet(key)) continue;
            double m = sec.getDouble(key);
            String shown = m == Math.rint(m) ? String.valueOf((int) m) : String.valueOf(m);
            parts.add("&7R" + r + " &e" + shown + "x");
        }
        if (parts.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        out.add(MessageUtil.color("  &7Zo groeit de multiplier per ronde:"));
        for (int i = 0; i < parts.size(); i += 4) {
            out.add(MessageUtil.color("   " + String.join(" &8• ", parts.subList(i, Math.min(parts.size(), i + 4)))));
        }
        return out;
    }

    /** Broadcasts each line spaced out (used by the shorter mid-tournament ceremonies). */
    private long broadcastSpaced(List<String> lines) {
        var cm = plugin.getCeremonyManager();
        long delay = cm != null ? cm.getLineDelayTicks() : 50L;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Bukkit.broadcastMessage(line);
                if (!line.contains("§m")) chime();
            }, i * delay);
        }
        return (long) Math.max(0, lines.size() - 1) * delay;
    }

    public void stop() {
        cancelCeremony();
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

    private long lastGameEndMs = 0;

    public void onGameEnd(String winnerName) {
        if (state != State.GAME_ACTIVE && state != State.PAUSED) {
            if (state == State.IDLE) return;
        }
        // De-dupe: a game-end can be signalled from several paths in the same
        // moment (result-event bridge, stopGame, health monitor). Only handle
        // the first within a short window so the end ceremony/leaderboards
        // don't get broadcast twice.
        long now = System.currentTimeMillis();
        if (now - lastGameEndMs < 3000) return;
        lastGameEndMs = now;
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
        // Reveal Golden Hour (if this is the secretly-picked round) right as
        // the real game launches — the whole ceremony sequence (opening, team
        // showcase, intermission, voting, countdown) has already played by
        // now, so this can't collide with it.
        plugin.getTournamentManager().revealGoldenHourIfDue();
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

        // Finale cinematic (no-op if unconfigured), then the staged Winner
        // Ceremony 2.0 (reveal #3 → #2 → champions + MVP + records + fireworks).
        playCinematic("closing", () -> {
            ceremony("closing");
            broadcast("&6&l[KMC] &eHet toernooi is afgelopen! Winnaar: " + topTeam);
            nl.kmc.kmccore.tournament.WinnerCeremony.run(plugin, null);
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
    private List<List<String>> buildTeamShowcaseBlocks() {
        List<List<String>> out = new ArrayList<>();
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        if (teams.isEmpty()) {
            out.add(List.of(MessageUtil.color("  &7Geen teams ingesteld — gebruik &e/kmcrandomteams&7.")));
            return out;
        }
        for (var team : teams) {
            List<String> block = new ArrayList<>();
            var members = team.getMembers();
            block.add(MessageUtil.color(team.getColor() + "&l" + team.getDisplayName()
                    + " &7(" + members.size() + (members.size() == 1 ? " speler)" : " spelers)")));
            if (members.isEmpty()) {
                block.add(MessageUtil.color("   &8• (nog geen spelers)"));
            } else {
                List<String> names = new ArrayList<>();
                for (java.util.UUID id : members) {
                    var op = Bukkit.getOfflinePlayer(id);
                    String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
                    names.add((op.isOnline() ? "&a" : "&7") + name);
                }
                block.add(MessageUtil.color("   &8• " + String.join("&7, ", names)));
            }
            out.add(block);
        }
        return out;
    }

    private Map<String, String> basePlaceholders() {
        Map<String, String> m = new HashMap<>();
        m.put("tournament_name", plugin.getConfig().getString("tournament.name", "KMC Tournament"));
        m.put("round",       String.valueOf(plugin.getTournamentManager().getCurrentRound()));
        m.put("multiplier",  String.valueOf(plugin.getTournamentManager().getMultiplier()));
        m.put("team_count",  String.valueOf(plugin.getTeamManager().getTeamsSortedByPoints().size()));
        m.put("total_rounds",    String.valueOf(plugin.getTournamentManager().getTotalRounds()));
        m.put("games_per_round", String.valueOf(plugin.getConfig().getInt("automation.games-per-round", 3)));
        m.put("game_count",      String.valueOf(plugin.getGameManager().getEnabledGames().size()));
        return m;
    }

    private void showCeremonyTitle(String title, String subtitle) {
        boolean noTitle = (title == null || title.isBlank());
        boolean noSub   = (subtitle == null || subtitle.isBlank());
        if (noTitle && noSub) return;
        String t  = noTitle ? "" : title;
        String st = noSub   ? "" : subtitle;
        for (Player p : Bukkit.getOnlinePlayers()) p.sendTitle(t, st, 10, 70, 15);
    }

    /** Looks up a game's short description from the V2 registry, or "" if unavailable. */
    private String lookupDescription(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            var reg = v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class).get(gameId);
            if (reg.isPresent() && reg.get().getDescription() != null) return reg.get().getDescription();
        }
        return "";
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