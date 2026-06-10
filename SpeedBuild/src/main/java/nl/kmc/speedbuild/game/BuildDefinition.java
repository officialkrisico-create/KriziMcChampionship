package nl.kmc.speedbuild.game;

/**
 * One of the 10 builds in the challenge. Immutable; loaded from config.
 *
 * @param id         stable identifier
 * @param name       display name shown to players
 * @param schematic  filename in the KMCCore schematics folder (e.g. {@code "sb_house.schem"})
 * @param difficulty 1-10, drives the par time
 * @param weight     score multiplier for this build (1.0 = normal)
 */
public record BuildDefinition(String id, String name, String schematic, int difficulty, double weight) {

    public BuildDefinition {
        difficulty = Math.max(1, Math.min(10, difficulty));
        if (weight <= 0) weight = 1.0;
    }
}
