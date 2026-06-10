package nl.kmc.speedbuild.game;

/**
 * Deterministic scoring outcome for a single build.
 *
 * @param buildIndex     0-based stage index
 * @param buildName      display name of the build
 * @param accuracyPercent 0..100 block-match percentage
 * @param timeTakenMs    wall-clock build time in milliseconds
 * @param bonusPoints    time bonus awarded
 * @param penaltyPoints  penalty for missing/incorrect blocks
 * @param finalScore     final (non-negative) score for this build
 */
public record BuildResult(int buildIndex, String buildName, double accuracyPercent,
                          long timeTakenMs, double bonusPoints, double penaltyPoints,
                          double finalScore) {

    public double timeTakenSeconds() { return timeTakenMs / 1000.0; }
}
