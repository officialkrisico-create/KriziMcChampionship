package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredPointAudit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StatisticsRepository {

    /** Appends a point-award audit entry. */
    CompletableFuture<Void> recordPointAward(StoredPointAudit audit);

    /** Returns audit trail for a specific player, newest first. */
    CompletableFuture<List<StoredPointAudit>> findAuditByPlayer(UUID uuid, int limit);

    /** Returns all audit entries for a tournament round. */
    CompletableFuture<List<StoredPointAudit>> findAuditByRound(int round);

    /** Bulk-insert for end-of-game batch flush. */
    CompletableFuture<Void> recordPointAwardBatch(List<StoredPointAudit> audits);
}
