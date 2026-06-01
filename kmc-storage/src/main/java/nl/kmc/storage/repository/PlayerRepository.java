package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerRepository {

    CompletableFuture<Optional<StoredPlayer>> findById(UUID uuid);

    CompletableFuture<List<StoredPlayer>> findAll();

    /** Returns top {@code limit} players ordered by lifetime points descending. */
    CompletableFuture<List<StoredPlayer>> findLeaderboard(int limit);

    CompletableFuture<Void> save(StoredPlayer player);

    CompletableFuture<Void> saveAll(List<StoredPlayer> players);

    /** Resets tournament-scoped stats (points, kills, wins, deaths, gamesPlayed) to 0. */
    CompletableFuture<Void> softReset(UUID uuid);

    CompletableFuture<Void> softResetAll();

    /** Wipes all stats including lifetime records. */
    CompletableFuture<Void> hardReset(UUID uuid);

    CompletableFuture<Void> hardResetAll();
}
