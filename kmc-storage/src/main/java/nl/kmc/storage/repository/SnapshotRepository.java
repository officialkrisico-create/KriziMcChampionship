package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface SnapshotRepository {

    CompletableFuture<Void> save(StoredSnapshot snapshot);

    CompletableFuture<Optional<StoredSnapshot>> findByLabel(String label);

    CompletableFuture<Optional<StoredSnapshot>> findLatest();

    CompletableFuture<List<StoredSnapshot>> findAll();

    CompletableFuture<Void> delete(String label);

    /** Removes snapshots beyond the configured ring-buffer capacity. */
    CompletableFuture<Void> pruneOldest(int keepCount);
}
