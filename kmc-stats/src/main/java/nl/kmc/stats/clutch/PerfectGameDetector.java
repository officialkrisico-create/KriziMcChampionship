package nl.kmc.stats.clutch;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.stats.model.GameStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Fires when a player wins without dying. Only fires at game end when won=true. */
public final class PerfectGameDetector implements ClutchDetector {

    @Override
    public Optional<ClutchMomentEvent> evaluate(UUID uuid, String name, String gameId,
                                                GameStats stats, Map<UUID, GameStats> all, Context ctx) {
        if (!stats.won || stats.deaths > 0) return Optional.empty();

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return Optional.empty();

        String desc = name + " won without dying — Perfect Game!";
        return Optional.of(new ClutchMomentEvent(
                player, ClutchMomentEvent.ClutchType.PERFECT_GAME, desc, gameId));
    }
}
