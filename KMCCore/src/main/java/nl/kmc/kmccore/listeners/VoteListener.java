package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.event.Listener;

/**
 * VoteListener is intentionally empty in this version.
 *
 * Votes are now handled via the /kmcvote command triggered by
 * clickable chat buttons (ClickableVoteMessage). Plain number
 * interception in chat is no longer needed and caused the
 * countdown conflict with AutomationManager.
 *
 * Kept as a class so the registration in KMCCore.java compiles.
 */
public class VoteListener implements Listener {
    public VoteListener(KMCCore plugin) {}
}
