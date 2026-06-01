package nl.kmc.tournament.recovery;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Periodically triggers {@link TournamentRecoveryEngine#capture()} while a
 * tournament is active, ensuring a recent snapshot is always available.
 */
public final class RecoveryScheduler {

    private static final Logger LOG = Logger.getLogger(RecoveryScheduler.class.getName());

    private final JavaPlugin               plugin;
    private final TournamentRecoveryEngine recovery;

    private int taskId = -1;

    public RecoveryScheduler(JavaPlugin plugin, TournamentRecoveryEngine recovery) {
        this.plugin   = plugin;
        this.recovery = recovery;
    }

    /**
     * Start the periodic snapshot task.
     *
     * @param intervalSeconds how often to capture (config key: {@code recovery.snapshot-interval})
     */
    public void start(int intervalSeconds) {
        if (taskId != -1) stop();
        long ticks = intervalSeconds * 20L;
        taskId = plugin.getServer().getScheduler()
                .scheduleSyncRepeatingTask(plugin, () -> {
                    recovery.capture();
                    LOG.fine("[KMC/Recovery] Periodic snapshot captured.");
                }, ticks, ticks);
        LOG.info("[KMC/Recovery] Snapshot scheduler started — every " + intervalSeconds + "s.");
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
            LOG.info("[KMC/Recovery] Snapshot scheduler stopped.");
        }
    }

    public boolean isRunning() { return taskId != -1; }
}
