package nl.kmc.stats.clutch;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.stats.model.GameStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects when a player achieves a kill while outnumbered (≥3 enemies alive, only 1 ally or solo).
 * Evaluates after every kill — the kill context is embedded in currentStats.kills delta.
 */
public final class OutnumberedVictoryDetector implements ClutchDetector {

    private final int minEnemies;

    public OutnumberedVictoryDetector(int minEnemies) { this.minEnemies = minEnemies; }

    @Override
    public Optional<ClutchMomentEvent> evaluate(UUID uuid, String name, String gameId,
                                                GameStats stats, Map<UUID, GameStats> all, Context ctx) {
        // Only fires if team-survival context: lone survivor of team
        if (ctx.playerTeamAliveCount() > 1) return Optional.empty();

        long livingEnemies = all.values().stream()
                .filter(gs -> !gs.playerUuid.equals(uuid) && !gs.teamId.equals(stats.teamId))
                .count();

        if (livingEnemies < minEnemies) return Optional.empty();

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return Optional.empty();

        String desc = "1v" + livingEnemies + " — last survivor of their team!";
        return Optional.of(new ClutchMomentEvent(
                player, ClutchMomentEvent.ClutchType.OUTNUMBERED_VICTORY, desc, gameId));
    }
}
