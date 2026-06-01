package nl.kmc.stats.clutch;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.stats.model.GameStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Fires when a player is the last remaining member of their team. */
public final class LastSurvivorDetector implements ClutchDetector {

    @Override
    public Optional<ClutchMomentEvent> evaluate(UUID uuid, String name, String gameId,
                                                GameStats stats, Map<UUID, GameStats> all, Context ctx) {
        if (ctx.playerTeamSize() < 2) return Optional.empty();
        if (ctx.playerTeamAliveCount() != 1) return Optional.empty();
        // Only fire once per-elimination event — checked externally via PlayerEliminatedEvent

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return Optional.empty();

        int eliminated = ctx.playerTeamSize() - 1;
        String desc = name + " is the last survivor! " + eliminated + " teammate(s) eliminated.";
        return Optional.of(new ClutchMomentEvent(
                player, ClutchMomentEvent.ClutchType.LAST_SURVIVOR, desc, gameId));
    }
}
