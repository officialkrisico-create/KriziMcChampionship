package nl.kmc.tournament.voting;

import nl.kmc.core.domain.GameRegistration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages a single voting session: opens the poll, accepts player votes,
 * and delivers the result to the AutomationEngine callback when time is up
 * or when {@link #forceEnd()} is called.
 */
public final class VotingEngine {

    private static final Logger LOG = Logger.getLogger(VotingEngine.class.getName());

    private final JavaPlugin plugin;

    /** Currently active session, null when no vote is in progress. */
    private volatile VoteSession   session;
    private volatile int           taskId = -1;
    private volatile Consumer<Optional<GameRegistration>> pendingCallback;

    public VotingEngine(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start a vote. Only one vote may be active at a time.
     *
     * @param candidates  games to vote on
     * @param durationSec seconds before the vote auto-closes
     * @param callback    receives the winning registration (or empty on no candidates)
     */
    public void startVote(List<GameRegistration> candidates, int durationSec,
                          Consumer<Optional<GameRegistration>> callback) {
        if (session != null && session.isActive()) {
            LOG.warning("[KMC/Voting] startVote() called while a vote is already active — ending old vote first.");
            endVote();
        }

        session          = new VoteSession(candidates);
        pendingCallback  = callback;

        broadcastVoteOpen(candidates, durationSec);

        taskId = plugin.getServer().getScheduler()
                .scheduleSyncDelayedTask(plugin, this::endVote, durationSec * 20L);
    }

    /**
     * Accept a vote from a player. Returns true if the vote was recorded,
     * false if no active session or invalid gameId.
     */
    public boolean castVote(UUID voter, String gameId) {
        if (session == null || !session.isActive()) return false;
        boolean recorded = session.castVote(voter, gameId);
        if (recorded) {
            Player p = Bukkit.getPlayer(voter);
            if (p != null) {
                p.sendMessage("§a§l[KMC] §aYour vote for §e" + gameId + " §ahas been recorded!");
            }
        }
        return recorded;
    }

    /** Force-closes the current vote immediately (called on phase skip). */
    public void forceEnd() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        endVote();
    }

    /** Whether a vote is currently running. */
    public boolean isActive() {
        return session != null && session.isActive();
    }

    /** Read-only view of the current session, or null if no vote is active. */
    public VoteSession getSession() { return session; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void endVote() {
        if (session == null) return;
        session.close();

        Optional<GameRegistration> winner = VoteResultProcessor.resolve(session);

        broadcastResult(winner);

        Consumer<Optional<GameRegistration>> cb = pendingCallback;
        session         = null;
        pendingCallback = null;
        taskId          = -1;

        if (cb != null) cb.accept(winner);
    }

    private void broadcastVoteOpen(List<GameRegistration> candidates, int durationSec) {
        plugin.getServer().broadcastMessage("");
        plugin.getServer().broadcastMessage("§8§m                              ");
        plugin.getServer().broadcastMessage("  §6§l▸ VOTE FOR THE NEXT GAME ◂");
        plugin.getServer().broadcastMessage("§8§m                              ");
        plugin.getServer().broadcastMessage("§7You have §e" + durationSec + " seconds§7 to cast your vote.");
        plugin.getServer().broadcastMessage("§7Type §e/kmc vote <game>§7 to vote.");
        plugin.getServer().broadcastMessage("");
        int slot = 1;
        for (GameRegistration r : candidates) {
            plugin.getServer().broadcastMessage(
                    "§7  " + slot++ + ". §e" + r.getDisplayName()
                    + " §8— §7/kmc vote " + r.getId());
        }
        plugin.getServer().broadcastMessage("");
    }

    private void broadcastResult(Optional<GameRegistration> winner) {
        plugin.getServer().broadcastMessage("");
        plugin.getServer().broadcastMessage("§8§m                              ");
        if (winner.isPresent()) {
            GameRegistration w = winner.get();
            plugin.getServer().broadcastMessage(
                    "  §a§l▸ VOTE RESULT: §e§l" + w.getDisplayName().toUpperCase() + " §a§lwon! ◂");
        } else {
            plugin.getServer().broadcastMessage("  §c§l▸ No votes cast — game selected randomly. ◂");
        }
        plugin.getServer().broadcastMessage("§8§m                              ");
        plugin.getServer().broadcastMessage("");
    }
}
