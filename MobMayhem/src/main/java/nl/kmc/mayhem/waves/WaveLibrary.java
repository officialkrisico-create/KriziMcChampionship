package nl.kmc.mayhem.waves;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * Default wave progression. Configurable in config.yml — this is just
 * the fallback if no waves are defined there.
 *
 * <p>Difficulty curve based on the spec:
 *   Waves 1-3:  zombies, skeletons, spiders (basics)
 *   Waves 4-6:  creepers, husks, witches (mid-tier)
 *   Wave 7:     mini-boss (iron golem hostile)
 *   Waves 8-9:  pillagers, blazes (late-tier)
 *   Wave 10:    final boss (wither skeleton or ravager)
 */
public final class WaveLibrary {

    private WaveLibrary() {}

    public static List<WaveDefinition> defaultWaves() {
        List<WaveDefinition> waves = new ArrayList<>();

        waves.add(new WaveDefinition(1, "Easy Zombies",
                List.of(new WaveDefinition.SpawnEntry(EntityType.ZOMBIE, 6)),
                false, 60));

        waves.add(new WaveDefinition(2, "Skeleton Squad",
                List.of(new WaveDefinition.SpawnEntry(EntityType.SKELETON, 5),
                        new WaveDefinition.SpawnEntry(EntityType.ZOMBIE,   3)),
                false, 60));

        waves.add(new WaveDefinition(3, "Spider Nest",
                List.of(new WaveDefinition.SpawnEntry(EntityType.SPIDER,   8),
                        new WaveDefinition.SpawnEntry(EntityType.ZOMBIE,   4)),
                false, 75));

        waves.add(new WaveDefinition(4, "Creeper Wave",
                List.of(new WaveDefinition.SpawnEntry(EntityType.CREEPER,  6),
                        new WaveDefinition.SpawnEntry(EntityType.SKELETON, 4)),
                false, 75));

        waves.add(new WaveDefinition(5, "Witch Hour",
                List.of(new WaveDefinition.SpawnEntry(EntityType.WITCH,       3),
                        new WaveDefinition.SpawnEntry(EntityType.HUSK,        5),
                        new WaveDefinition.SpawnEntry(EntityType.CAVE_SPIDER, 4)),
                false, 90));

        waves.add(new WaveDefinition(6, "Endermen + Phantoms",
                List.of(new WaveDefinition.SpawnEntry(EntityType.ENDERMAN, 4),
                        new WaveDefinition.SpawnEntry(EntityType.PHANTOM,  3)),
                false, 90));

        // Mini-boss
        waves.add(new WaveDefinition(7, "★ Iron Golem Boss",
                List.of(new WaveDefinition.SpawnEntry(EntityType.IRON_GOLEM, 1),
                        new WaveDefinition.SpawnEntry(EntityType.ZOMBIE,     6)),
                true, 120));

        waves.add(new WaveDefinition(8, "Pillager Raid",
                List.of(new WaveDefinition.SpawnEntry(EntityType.PILLAGER, 6),
                        new WaveDefinition.SpawnEntry(EntityType.VINDICATOR, 2)),
                false, 90));

        waves.add(new WaveDefinition(9, "Blaze Inferno",
                List.of(new WaveDefinition.SpawnEntry(EntityType.BLAZE,        5),
                        new WaveDefinition.SpawnEntry(EntityType.MAGMA_CUBE,   4)),
                false, 90));

        // Final boss
        waves.add(new WaveDefinition(10, "★★ Ravager Final Boss",
                List.of(new WaveDefinition.SpawnEntry(EntityType.RAVAGER,         1),
                        new WaveDefinition.SpawnEntry(EntityType.WITHER_SKELETON, 4),
                        new WaveDefinition.SpawnEntry(EntityType.PILLAGER,        4)),
                true, 180));

        return waves;
    }

    public static int defaultPointsForKill(EntityType type, boolean wasBoss) {
        if (wasBoss) {
            return switch (type) {
                case IRON_GOLEM, RAVAGER, ENDER_DRAGON, WITHER -> 50;
                default -> 20;
            };
        }
        return switch (type) {
            case ZOMBIE, SKELETON, SPIDER, HUSK -> 1;
            case CAVE_SPIDER, STRAY              -> 2;
            case CREEPER                          -> 3;
            case WITCH, ENDERMAN                 -> 4;
            case BLAZE, PILLAGER, VINDICATOR     -> 5;
            case PHANTOM, MAGMA_CUBE             -> 3;
            case WITHER_SKELETON                 -> 8;
            default                               -> 2;
        };
    }
}
