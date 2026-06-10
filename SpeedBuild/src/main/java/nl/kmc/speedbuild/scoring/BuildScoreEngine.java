package nl.kmc.speedbuild.scoring;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import nl.kmc.speedbuild.game.BuildDefinition;
import nl.kmc.speedbuild.game.BuildResult;
import nl.kmc.speedbuild.schematic.SchematicComparer;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Combines accuracy, time bonus, and block penalties into a final, fully
 * deterministic {@link BuildResult}. No subjective input anywhere.
 *
 * <pre>
 *   finalScore = accuracyFraction · accuracyPoints · weight
 *              + timeBonus
 *              − wrongBlocks · penaltyPerBlock        (floored at 0)
 * </pre>
 */
public final class BuildScoreEngine {

    private final TimeBonusCalculator timeBonus;
    private final double accuracyPoints;
    private final double penaltyPerBlock;

    public BuildScoreEngine(FileConfiguration cfg) {
        this.timeBonus       = new TimeBonusCalculator(cfg);
        this.accuracyPoints  = cfg.getDouble("scoring.accuracy-points", 100);
        this.penaltyPerBlock = cfg.getDouble("scoring.penalty-per-block", 2);
    }

    public BuildResult score(int buildIndex, BuildDefinition def, Clipboard schematic,
                             Location buildMin, long timeTakenMs) {
        SchematicComparer.Diff diff = SchematicComparer.compare(schematic, buildMin);

        double accuracyPercent = AccuracyCalculator.percent(diff);
        double bonus           = timeBonus.bonus(timeTakenMs, def.difficulty());
        double penalty         = diff.wrongBlocks() * penaltyPerBlock;
        double raw             = diff.accuracy() * accuracyPoints * def.weight() + bonus - penalty;
        double finalScore      = Math.max(0, raw);

        return new BuildResult(buildIndex, def.name(), accuracyPercent, timeTakenMs, bonus, penalty, finalScore);
    }

    public TimeBonusCalculator timeBonus() { return timeBonus; }
}
