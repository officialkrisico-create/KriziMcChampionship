package nl.kmc.quake.util;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.entity.Player;

/**
 * Centralized team-relationship check. <b>Every</b> weapon, gadget and ability
 * routes friendly-fire decisions through here, so team protection is global and
 * future weapons get it for free — no per-weapon friendly-fire logic.
 */
public final class TeamUtil {

    private TeamUtil() {}

    /**
     * @return true if the two players must NOT be able to hurt each other
     *         (same KMC team), unless {@code game.friendly-fire} is enabled.
     *         A player is always "teammates" with themselves.
     */
    public static boolean areTeammates(QuakeCraftPlugin plugin, Player a, Player b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (plugin.getConfig().getBoolean("game.friendly-fire", false)) return false;

        var ta = plugin.getKmcCore().getTeamManager().getTeamByPlayer(a.getUniqueId());
        var tb = plugin.getKmcCore().getTeamManager().getTeamByPlayer(b.getUniqueId());
        return ta != null && tb != null && ta.getId().equals(tb.getId());
    }

    /** Convenience: true if {@code target} is a valid enemy of {@code shooter}. */
    public static boolean isEnemy(QuakeCraftPlugin plugin, Player shooter, Player target) {
        return target != null && !target.equals(shooter) && !areTeammates(plugin, shooter, target);
    }
}
