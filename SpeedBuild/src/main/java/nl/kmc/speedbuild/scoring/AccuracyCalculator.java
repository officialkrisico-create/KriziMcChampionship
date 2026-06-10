package nl.kmc.speedbuild.scoring;

import nl.kmc.speedbuild.schematic.SchematicComparer;

/** Turns a block diff into an accuracy percentage (0..100). Pure + deterministic. */
public final class AccuracyCalculator {

    private AccuracyCalculator() {}

    public static double percent(SchematicComparer.Diff diff) {
        return Math.max(0, Math.min(100, diff.accuracy() * 100.0));
    }
}
