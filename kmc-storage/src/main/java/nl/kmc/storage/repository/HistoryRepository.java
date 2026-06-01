package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredTournamentResult;
import nl.kmc.storage.model.StoredPlayerResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HistoryRepository {

    CompletableFuture<Void> saveTournamentResult(StoredTournamentResult result);

    CompletableFuture<Void> savePlayerResults(List<StoredPlayerResult> results);

    CompletableFuture<List<StoredTournamentResult>> findAllTournaments();

    CompletableFuture<List<StoredPlayerResult>> findPlayerHistory(UUID uuid);

    /** Returns the N most recent tournament results. */
    CompletableFuture<List<StoredTournamentResult>> findRecent(int limit);
}
