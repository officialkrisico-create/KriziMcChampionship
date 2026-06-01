package nl.kmc.storage.cache;

import nl.kmc.storage.model.StoredPlayer;
import nl.kmc.storage.repository.PlayerRepository;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Write-through cache wrapper over any PlayerRepository.
 * Reads are served from the in-memory map; writes go through to the delegate
 * and update the cache on success.
 */
public final class CachedPlayerRepository implements PlayerRepository {

    private final PlayerRepository delegate;
    private final Map<UUID, StoredPlayer> cache = new ConcurrentHashMap<>();

    public CachedPlayerRepository(PlayerRepository delegate) {
        this.delegate = delegate;
    }

    /** Warms the cache from the database. Call once at startup. */
    public CompletableFuture<Void> warmUp() {
        return delegate.findAll().thenAccept(list -> list.forEach(p -> cache.put(p.uuid, p)));
    }

    public void put(StoredPlayer player) {
        cache.put(player.uuid, player);
    }

    public void evict(UUID uuid) {
        cache.remove(uuid);
    }

    public int size() { return cache.size(); }

    @Override
    public CompletableFuture<Optional<StoredPlayer>> findById(UUID uuid) {
        StoredPlayer cached = cache.get(uuid);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return delegate.findById(uuid).thenApply(opt -> {
            opt.ifPresent(p -> cache.put(p.uuid, p));
            return opt;
        });
    }

    @Override
    public CompletableFuture<List<StoredPlayer>> findAll() {
        if (!cache.isEmpty()) return CompletableFuture.completedFuture(new ArrayList<>(cache.values()));
        return delegate.findAll().thenApply(list -> {
            list.forEach(p -> cache.put(p.uuid, p));
            return list;
        });
    }

    @Override
    public CompletableFuture<List<StoredPlayer>> findLeaderboard(int limit) {
        // Always from delegate for accuracy; leaderboard is a heavy read, not hot-path
        return delegate.findLeaderboard(limit);
    }

    @Override
    public CompletableFuture<Void> save(StoredPlayer player) {
        cache.put(player.uuid, player);
        return delegate.save(player);
    }

    @Override
    public CompletableFuture<Void> saveAll(List<StoredPlayer> players) {
        players.forEach(p -> cache.put(p.uuid, p));
        return delegate.saveAll(players);
    }

    @Override
    public CompletableFuture<Void> softReset(UUID uuid) {
        StoredPlayer cached = cache.get(uuid);
        if (cached != null) {
            cached.points = 0; cached.kills = 0; cached.deaths = 0;
            cached.wins = 0;   cached.gamesPlayed = 0;
        }
        return delegate.softReset(uuid);
    }

    @Override
    public CompletableFuture<Void> softResetAll() {
        cache.values().forEach(p -> {
            p.points = 0; p.kills = 0; p.deaths = 0; p.wins = 0; p.gamesPlayed = 0;
        });
        return delegate.softResetAll();
    }

    @Override
    public CompletableFuture<Void> hardReset(UUID uuid) {
        cache.remove(uuid);
        return delegate.hardReset(uuid);
    }

    @Override
    public CompletableFuture<Void> hardResetAll() {
        cache.clear();
        return delegate.hardResetAll();
    }
}
