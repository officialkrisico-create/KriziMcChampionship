package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredTeam;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface TeamRepository {

    CompletableFuture<Optional<StoredTeam>> findById(String teamId);

    CompletableFuture<List<StoredTeam>> findAll();

    /** Ordered by points descending — used for live standings. */
    CompletableFuture<List<StoredTeam>> findStandings();

    CompletableFuture<Void> save(StoredTeam team);

    CompletableFuture<Void> saveAll(List<StoredTeam> teams);

    CompletableFuture<Void> softResetAll();

    CompletableFuture<Void> hardResetAll();
}
