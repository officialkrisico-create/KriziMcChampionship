package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
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

    public AutomationManager(KMCCore plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Control
    // ----------------------------------------------------------------

    public void start() {
        if (state != State.IDLE) return;
        gamesThisRound = 0;
        attemptedThisCycle.clear();
        createBossBar();
        enterIntermission();
    }

    public void stop() {
        cancelTick();
        hideBossBar();
        state = State.IDLE;
    }

    public void onGameEnd(String winnerName) {
        if (state != State.GAME_ACTIVE && state != State.PAUSED) {
            if (state == State.IDLE) return;
        }
        gamesThisRound++;
        attemptedThisCycle.clear();

        int gamesPerRound = plugin.getConfig().getInt("automation.games-per-round", 3);
        if (gamesThisRound >= gamesPerRound) {
            gamesThisRound = 0;
            if (!plugin.getTournamentManager().nextRound()) {
                endTournament(winnerName);
                return;
            }
        }

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
        if (!plugin.getArenaManager().isGameReady(game.getId())) {
            attemptedThisCycle.add(game.getId());
            String reason = plugin.getArenaManager().getReadinessReason(game.getId());
            broadcast("&e[KMC] &7Game &e" + game.getDisplayName()
                    + " &7is niet geconfigureerd (" + reason + "). Overslaan...");

            // Try another game — avoid already-attempted ones
            KMCGame fallback = pickNextReadyGame();
            if (fallback == null) {
                broadcast("&c[KMC] Geen enkele game is correct geconfigureerd! Automatisering en toernooi gestopt.");
                broadcast("&7Configureer arenas via &e/kmcarena &7en probeer opnieuw.");
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
                launchGame(game);
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
            if (plugin.getArenaManager().isGameReady(candidate.getId())) {
                return candidate;
            }
            attemptedThisCycle.add(candidate.getId());
        }
        return null;
    }

    private void launchGame(KMCGame game) {
        state = State.GAME_ACTIVE;
        attemptedThisCycle.clear();
        hideBossBar();
        plugin.getGameManager().startGame(game.getId());

        createBossBar();
        setBossBar("&2▶ &a" + game.getDisplayName() + " &2◀  Ronde "
                        + plugin.getTournamentManager().getCurrentRound()
                        + "  ×" + plugin.getTournamentManager().getMultiplier(),
                BarColor.GREEN, 1.0);
        setBossBarProgress(1.0);
    }

    private void endTournament(String lastWinner) {
        stop();
        plugin.getTournamentManager().endTournament();

        String topTeam = plugin.getTeamManager().getTeamsSortedByPoints().stream()
                .findFirst().map(t -> t.getColor() + t.getDisplayName())
                .orElse("Onbekend");

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(MessageUtil.color("&6&lToernooi Afgelopen!"),
                    MessageUtil.color("&eWinnaar: " + topTeam), 10, 100, 30);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        broadcast("&6&l[KMC] &eHet toernooi is afgelopen! Winnaar: " + topTeam);
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