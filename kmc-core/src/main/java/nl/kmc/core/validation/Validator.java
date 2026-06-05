package nl.kmc.core.validation;

import org.bukkit.Material;

/**
 * A single quality-assurance check for one KMC system (a game, the tournament,
 * presentation, NPCs, achievements, the database, …). Any system can implement
 * this and register it with {@link ValidationManager} — the Event Validation
 * System discovers and runs them all automatically.
 */
public interface Validator {

    /** Stable id, e.g. {@code "tournament"}, {@code "presentation"}. */
    String id();

    /** Human display name shown in the Validation Center GUI. */
    String displayName();

    /** Icon for the GUI tile. */
    default Material icon() { return Material.PAPER; }

    /** Runs the checks and returns the report. Must not throw. */
    ValidationReport validate();
}
