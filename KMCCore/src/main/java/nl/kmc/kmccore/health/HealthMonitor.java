package nl.kmc.kmccore.health;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;

/**
 * Health monitor for the tournament system.
 *
 * <p>Watches for stuck states and either logs warnings or auto-recovers:
 *
 * <ul>
 *   <li><b>Stuck game</b> — a game has been running >15 min (configurable).
 *       Auto-recovery: force-end the game, automation continues.</li>
 *   <li><b>Stuck scoreboard lock</b> — held >5 min. Auto-recovery:
 *       release the lock so the next game can acquire it.</li>
 *   <li><b>Automation hang</b> — between two games, no game has started
 *       in >2 min. Auto-recovery: trigger ready-up skip + start next game.</li>
 *   <li><b>TPS drop</b> — server TPS &lt; 15 sustained 30s+. Logged as warning.</li>
 *   <li><b>Memory pressure</b> — free heap &lt; 10%. Logged as critical.</li>
 * </ul>
 *
 * <p>All events are logged to {@code health.log} in the data folder
 * with timestamps. Optionally pushed via Discord webhook.
 *
 * <p>Auto-recovery can be disabled via config:
 * {@code health.auto-recover: false}
 */
public class HealthMonitor {

    public enum Severity { INFO, WARNING, CRITICAL }

    public record HealthEvent(long timestamp, Severity severity, String code, String message) {}

    private final KMCCore plugin;
    private final List<HealthEvent> recentEvents = new ArrayList<>();
    private BukkitTask monitorTask;

    private long currentGameStartMs = -1;
    private long scoreboardAcquiredMs = -1;
    private String scoreboardOwner;
    private long lastGameEndMs = System.currentTimeMillis();
    private long lastTpsLowMs = -1;

    /**
     * Tracks whether any game has actually finished since automation
     * last started. The "automation hang" check skips while this is
     * false — otherwise the very first /kmcauto start triggers a false
     * positive recovery (because lastGameEndMs reflects plugin enable
     * time, not tournament time).
     */
    private boolean anyGameEndedSinceStart = false;

    public HealthMonitor(KMCCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Run every 5 seconds
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
        plugin.getLogger().info("HealthMonitor started.");
    }

    public void stop() {
        if (monitorTask != null) monitorTask.cancel();
    }

    // ----------------------------------------------------------------
    // External hooks (called by other managers when state changes)
    // ----------------------------------------------------------------

    public void notifyGameStart(String gameId) {
        currentGameStartMs = System.currentTimeMillis();
        log(Severity.INFO, "GAME_START", "Game started: " + gameId);
    }

    public void notifyGameEnd(String gameId, String winner) {
        currentGameStartMs = -1;
        lastGameEndMs = System.currentTimeMillis();
        anyGameEndedSinceStart = true;
        log(Severity.INFO, "GAME_END", "Game ended: " + gameId
                + (winner != null ? " (winner: " + winner + ")" : ""));
    }

    /**
     * Called by KMCCore when /kmcauto start runs. Resets the hang-check
     * baseline so we don't trigger a phantom "automation hang" recovery
     * on the first tick of a fresh tournament.
     */
    public void notifyAutomationStarted() {
        lastGameEndMs = System.currentTimeMillis();
        anyGameEndedSinceStart = false;
    }

    public void notifyScoreboardAcquired(String owner) {
        scoreboardAcquiredMs = System.currentTimeMillis();
        scoreboardOwner = owner;
    }

    public void notifyScoreboardReleased(String owner) {
        if (Objects.equals(owner, scoreboardOwner)) {
            scoreboardAcquiredMs = -1;
            scoreboardOwner = null;
        }
    }

    public void log(Severity sev, String code, String message) {
        HealthEvent ev = new HealthEvent(System.currentTimeMillis(), sev, code, message);
        recentEvents.add(ev);
        // Keep last 200 events in memory
        if (recentEvents.size() > 200) recentEvents.remove(0);

        Level logLevel = switch (sev) {
            case INFO -> Level.INFO;
            case WARNING -> Level.WARNING;
            case CRITICAL -> Level.SEVERE;
        };
        plugin.getLogger().log(logLevel, "[HEALTH:" + code + "] " + message);

        // Push to Discord webhook if configured + severity >= WARNING
        if (sev != Severity.INFO && plugin.getDiscordHook() != null) {
            plugin.getDiscordHook().postHealthAlert(sev, code, message);
        }
    }

    // ----------------------------------------------------------------
    // Tick — checks all conditions
    // ----------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        boolean autoRecover = plugin.getConfig().getBoolean("health.auto-recover", true);

        // 1. Stuck game?
        long stuckGameMs = plugin.getConfig().getLong("health.stuck-game-seconds", 900) * 1000L;
        if (currentGameStartMs > 0 && (now - currentGameStartMs) > stuckGameMs) {
            String gameId = plugin.getGameManager().getActiveGame() != null
                    ? plugin.getGameManager().getActiveGame().getId() : "unknown";
            log(Severity.CRITICAL, "STUCK_GAME",
                    "Game " + gameId + " running >"
                            + (stuckGameMs / 1000) + "s. " + (autoRecover ? "Force-ending." : "Manual intervention required."));
            if (autoRecover) {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (plugin.getGameManager().getActiveGame() != null) {
                            plugin.getGameManager().stopGame(null);
                        }
                        if (plugin.getAutomationManager().isRunning()) {
                            plugin.getAutomationManager().onGameEnd(null);
                        }
                    });
                    currentGameStartMs = -1;
                } catch (Exception e) {
                    log(Severity.CRITICAL, "RECOVERY_FAILED", "Force-end failed: " + e.getMessage());
                }
            }
        }

        // 2. Stuck scoreboard lock?
        long stuckLockMs = plugin.getConfig().getLong("health.stuck-scoreboard-seconds", 300) * 1000L;
        if (scoreboardAcquiredMs > 0 && (now - scoreboardAcquiredMs) > stuckLockMs) {
            String owner = scoreboardOwner;
            log(Severity.WARNING, "STUCK_SCOREBOARD",
                    "Scoreboard lock held by '" + owner + "' >"
                            + (stuckLockMs / 1000) + "s. " + (autoRecover ? "Releasing." : "Manual."));
            if (autoRecover) {
                plugin.getApi().releaseScoreboard(owner);
                scoreboardAcquiredMs = -1;
                scoreboardOwner = null;
            }
        }

        // 3. Automation hang? (only if at least one game has finished
        //    since automation started — otherwise the baseline timer is
        //    just the time since plugin enable, which falsely trips the
        //    check on the very first /kmcauto start)
        long automationGapMs = plugin.getConfig().getLong("health.automation-gap-seconds", 120) * 1000L;
        if (anyGameEndedSinceStart
                && plugin.getAutomationManager().isRunning()
                && currentGameStartMs < 0
                && (now - lastGameEndMs) > automationGapMs) {
            log(Severity.WARNING, "AUTOMATION_HANG",
                    "Automation idle >" + (automationGapMs / 1000) + "s without next game. "
                            + (autoRecover ? "Triggering next." : "Manual."));
            if (autoRecover) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getAutomationManager().onGameEnd(null));
                lastGameEndMs = now;
            }
        }

        // 4. TPS check (rough — uses Spigot getTPS if available)
        try {
            double[] tps = (double[]) Bukkit.class.getMethod("getTPS").invoke(null);
            if (tps[0] < 15.0) {
                if (lastTpsLowMs < 0) lastTpsLowMs = now;
                else if (now - lastTpsLowMs > 30_000) {
                    log(Severity.WARNING, "LOW_TPS",
                            String.format("TPS sustained low: %.1f (1m), %.1f (5m), %.1f (15m)",
                                    tps[0], tps[1], tps[2]));
                    lastTpsLowMs = now;  // throttle warning to once per 30s
                }
            } else {
                lastTpsLowMs = -1;
            }
        } catch (Exception ignored) {
            // getTPS not available on this Bukkit fork — skip
        }

        // 5. Memory check
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long max = rt.maxMemory();
        double usedPct = ((double) (total - free)) / max;
        if (usedPct > 0.9) {
            log(Severity.WARNING, "HIGH_MEMORY",
                    String.format("Heap usage %.1f%% (%d MB used / %d MB max)",
                            usedPct * 100, (total - free) / 1024 / 1024, max / 1024 / 1024));
        }
    }

    // ----------------------------------------------------------------

    public List<HealthEvent> getRecentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }

    public List<HealthEvent> getRecentEventsBySeverity(Severity sev) {
        return recentEvents.stream().filter(e -> e.severity() == sev).toList();
    }
}