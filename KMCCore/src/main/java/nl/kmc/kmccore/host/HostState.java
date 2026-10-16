package nl.kmc.kmccore.host;

/**
 * The state of the NPC host. It follows the existing automation lifecycle ({@code /kmcauto start} … end) and
 * never starts or changes the tournament itself.
 */
public enum HostState {
    /** Switched off by an admin ({@code host.enabled: false} or {@code /kmchost disable}). */
    DISABLED,
    /** Available, but no KMCAuto session is running: the host stays silent. */
    IDLE,
    /** A KMCAuto session is running: the host may react. */
    ACTIVE,
    /** The tournament is over and the end sequence (podium, awards) is being presented. */
    FINISHING
}
