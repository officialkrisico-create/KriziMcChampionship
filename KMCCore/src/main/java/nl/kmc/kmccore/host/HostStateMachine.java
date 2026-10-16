package nl.kmc.kmccore.host;

/**
 * The transitions of the host, without any Bukkit dependency so they can be tested. Every transition is
 * idempotent: calling {@link #startSession()} twice for the same start reports {@code false} the second time,
 * so a duplicated callback can never produce a second speech or a second set of tasks.
 *
 * <p>{@link #generation()} changes with every transition; anything scheduled earlier compares it and
 * quietly does nothing when it is stale.
 */
public final class HostStateMachine {

    private boolean enabled = true;
    private boolean session;
    private boolean finishing;
    private boolean finishNext;
    private int generation;

    public HostState state() {
        if (!enabled) return HostState.DISABLED;
        if (finishing) return HostState.FINISHING;
        if (session) return HostState.ACTIVE;
        return HostState.IDLE;
    }

    public int generation() { return generation; }

    public boolean isEnabled() { return enabled; }

    /** Automatic speech is only allowed while a session runs or the end sequence is being presented. */
    public boolean canSpeakAutomatically() {
        HostState s = state();
        return s == HostState.ACTIVE || s == HostState.FINISHING;
    }

    /** @return true when the state changed (false when it already was that way). */
    public boolean setEnabled(boolean value) {
        if (enabled == value) return false;
        enabled = value;
        generation++;
        return true;
    }

    /** KMCAuto started. @return false when the host is disabled or this session was already started. */
    public boolean startSession() {
        if (!enabled || session) return false;
        finishing = false;                 // a new session replaces the end sequence of the previous one
        session = true;
        generation++;
        return true;
    }

    /** The coming {@link #stopSession()} is the natural end of the tournament: finish with the end sequence. */
    public void finishOnNextStop() {
        if (session) finishNext = true;
    }

    /**
     * KMCAuto ended. After {@link #finishOnNextStop()} the host moves on to FINISHING, otherwise (admin
     * stop, cancelled) it goes straight back to IDLE.
     *
     * @return true when something changed
     */
    public boolean stopSession() {
        boolean wasSession = session;
        boolean wasFinishing = finishing;
        if (finishNext && session) {
            session = false;
            finishing = true;
            finishNext = false;
            generation++;
            return true;
        }
        session = false;
        finishing = false;
        finishNext = false;
        if (wasSession || wasFinishing) generation++;
        return wasSession || wasFinishing;
    }

    /** The end sequence is over (or was cancelled). */
    public boolean endFinishing() {
        if (!finishing) return false;
        finishing = false;
        generation++;
        return true;
    }
}
