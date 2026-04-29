package nl.kmc.kmccore.util;

import nl.kmc.kmccore.KMCCore;

import java.util.Collection;
import java.util.UUID;

/**
 * Helper for the "living while someone dies" bonus pattern shared by
 * SurvivalGames, SkyWars, Spleef, MobMayhem, and TNTTag.
 *
 * <p>Pattern: every time a player dies, ALL still-alive participants
 * each receive a flat bonus (default 5 points). The dying player gets
 * nothing. Configurable per-game so each game can tune the value.
 *
 * <p>Usage (called from the death/elimination handler):
 * <pre>
 * SurvivorBonusHelper.award(plugin, stillAliveUuids, 5);
 * </pre>
 */
public final class SurvivorBonusHelper {

    private SurvivorBonusHelper() {}

    /**
     * Awards {@code amount} points to every UUID in {@code stillAlive}.
     * Skips no-ops (null/zero/empty). Goes through KMCApi.givePoints so
     * the round multiplier applies normally.
     */
    public static void award(KMCCore plugin, Collection<UUID> stillAlive, int amount) {
        if (plugin == null || stillAlive == null || stillAlive.isEmpty() || amount <= 0) return;
        for (UUID uuid : stillAlive) {
            if (uuid == null) continue;
            plugin.getApi().givePoints(uuid, amount);
        }
    }
}
