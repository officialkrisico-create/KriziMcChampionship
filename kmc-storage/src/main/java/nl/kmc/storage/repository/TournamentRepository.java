package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredTournamentState;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface TournamentRepository {

    CompletableFuture<Optional<StoredTournamentState>> load();

    CompletableFuture<Void> save(StoredTournamentState state);

    CompletableFuture<Void> clear();
}
