package nl.kmc.core.setup;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * A game's setup descriptor, registered into {@link SetupService} by each game
 * plugin. The Setup Dashboard GUI in KMCCore reads these to render one unified
 * setup screen across all games — no more separate {@code /kmcarena} and
 * {@code /<game> set...} command trees.
 */
public interface GameSetup {

    /** Machine id, e.g. {@code "quake_craft"}. */
    String gameId();

    /** Human display name, e.g. {@code "QuakeCraft"}. */
    String displayName();

    /** Icon shown in the dashboard grid. */
    Material icon();

    /** True when the arena passes validation and the game can start. */
    boolean isReady();

    /**
     * The setup checklist for this game, evaluated fresh each time the GUI opens.
     *
     * @param viewer the admin viewing the dashboard (so steps like "add spawn at
     *               your location" can use their position)
     */
    List<SetupStep> steps(Player viewer);

    /**
     * The real validation problems (used by the Event Validation System).
     * Empty when the arena is fully configured. Defaults to empty.
     */
    default List<String> issues() { return List.of(); }
}
