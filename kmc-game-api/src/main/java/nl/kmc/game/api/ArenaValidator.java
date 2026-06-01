package nl.kmc.game.api;

/** Validates that an arena is fully configured before a game can start. */
public interface ArenaValidator {

    /**
     * Run all validation checks.
     * @return a result object — call {@link ValidationResult#isValid()} before starting.
     */
    ValidationResult validate();

    /** Human-readable name shown in /validate output. */
    String getGameName();
}
