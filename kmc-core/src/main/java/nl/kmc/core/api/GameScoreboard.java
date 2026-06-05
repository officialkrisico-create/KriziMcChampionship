package nl.kmc.core.api;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Per-game sidebar content. While a game owns the scoreboard lock, KMCCore
 * paints these lines onto each player's existing board (so KMC team colours /
 * nametags are preserved) instead of freezing the lobby sidebar.
 *
 * <p>Both methods are called per-player every scoreboard tick, so they may
 * return live, viewer-specific content (e.g. the viewer's own kills first).
 */
public interface GameScoreboard {

    /** Sidebar title (supports legacy {@code &} colour codes). */
    String title(Player viewer);

    /** Sidebar lines, top to bottom. Max 15 are shown; supports {@code &} codes. */
    List<String> lines(Player viewer);
}
