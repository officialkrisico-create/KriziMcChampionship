package nl.kmc.bingo.objectives;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

/**
 * One bingo square. Every objective implements this.
 *
 * <p>Lifecycle:
 *   1. {link onProgress int} called by listeners when relevant
 *      events happen (item picked up, mob killed, etc.)
 *   2. When progress reaches the target, the objective is marked
 *      complete via the manager and the listener stops feeding it.
 *
 * <p>Each objective has a stable {@link #getId()} that identifies
 * the type+target pair (e.g. "collect:emerald:4"). This is used by
 * the listener to route events.
 */
public interface BingoObjective {

    /** Unique identifier (used for routing events). */
    String getId();

    /** Display name shown on the GUI item lore. */
    Component getDisplayName();

    /** Item that represents this objective in the GUI grid. */
    Material getDisplayIcon();

    /** Required progress to complete. For "collect 4 emeralds" → 4. */
    int getTargetAmount();

    /** Type tag used by listeners for routing — see {@link ObjectiveType}. */
    ObjectiveType getType();

    /**
     * Match key used by the listener to decide if this objective
     * cares about the event. For "collect emerald" → "EMERALD".
     * For "kill creeper" → "CREEPER". May be null for objectives
     * that don't need a tag (e.g. "find diamond" handled bespoke).
     */
    String getMatchKey();
}
