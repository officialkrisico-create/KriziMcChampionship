package nl.kmc.speedbuild.scoring;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Deterministic time bonus: finishing under par awards points proportional to
 * the seconds saved. Par scales with build difficulty so harder builds get
 * more time before the clock counts against them.
 */
public final class TimeBonusCalculator {

    private final double perSecond;
    private final double maxBonus;
    private final double parBase;
    private final double parPerDifficulty;

    public TimeBonusCalculator(FileConfiguration cfg) {
        this.perSecond        = cfg.getDouble("scoring.time-bonus-per-second", 3);
        this.maxBonus         = cfg.getDouble("scoring.max-time-bonus", 120);
        this.parBase          = cfg.getDouble("scoring.par-base-seconds", 60);
        this.parPerDifficulty = cfg.getDouble("scoring.par-per-difficulty", 20);
    }

    public double parSeconds(int difficulty) {
        return parBase + parPerDifficulty * difficulty;
    }

    /** @return time bonus (0..maxBonus); 0 if the player was slower than par. */
    public double bonus(long timeTakenMs, int difficulty) {
        double saved = parSeconds(difficulty) - timeTakenMs / 1000.0;
        if (saved <= 0) return 0;
        return Math.min(maxBonus, saved * perSecond);
    }
}
