package nl.kmc.storage.cache;

import nl.kmc.storage.model.StoredTeam;
import nl.kmc.storage.repository.TeamRepository;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Write-through cache wrapper for teams. Team data changes infrequently — full in-memory is safe. */
public final class CachedTeamRepository implements TeamRepository {

    private final TeamRepository delegate;
    private final Map<String, StoredTeam> cache = new ConcurrentHashMap<>();

    public CachedTeamRepository(TeamRepository delegate) {
        this.delegate = delegate;
    }

    public CompletableFuture<Void> warmUp() {
        return delegate.findAll().thenAccept(list -> list.forEach(t -> cache.put(t.id, t)));
    }

    public void put(StoredTeam team) { cache.put(team.id, team); }

    @Override
    public CompletableFuture<Optional<StoredTeam>> findById(String teamId) {
        StoredTeam cached = cache.get(teamId);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return delegate.findById(teamId).thenApply(opt -> {
            opt.ifPresent(t -> cache.put(t.id, t));
            return opt;
        });
    }

    @Override
    public CompletableFuture<List<StoredTeam>> findAll() {
        if (!cache.isEmpty()) return CompletableFuture.completedFuture(new ArrayList<>(cache.values()));
        return delegate.findAll().thenApply(list -> {
            list.forEach(t -> cache.put(t.id, t));
            return list;
        });
    }

    @Override
    public CompletableFuture<List<StoredTeam>> findStandings() {
        List<StoredTeam> sorted = new ArrayList<>(cache.values());
        sorted.sort(Comparator.comparingInt((StoredTeam t) -> t.points).reversed());
        return CompletableFuture.completedFuture(sorted);
    }

    @Override
    public CompletableFuture<Void> save(StoredTeam team) {
        cache.put(team.id, team);
        return delegate.save(team);
    }

    @Override
    public CompletableFuture<Void> saveAll(List<StoredTeam> teams) {
        teams.forEach(t -> cache.put(t.id, t));
        return delegate.saveAll(teams);
    }

    @Override
    public CompletableFuture<Void> softResetAll() {
        cache.values().forEach(t -> { t.points = 0; t.wins = 0; });
        return delegate.softResetAll();
    }

    @Override
    public CompletableFuture<Void> hardResetAll() {
        cache.clear();
        return delegate.hardResetAll();
    }
}
