package nl.kmc.core.service;

import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.storage.StorageModule;
import nl.kmc.storage.model.StoredPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class PlayerService {

    private static final Logger LOG = Logger.getLogger(PlayerService.class.getName());

    private final StorageModule storage;
    private final Map<UUID, KMCPlayer> cache = new ConcurrentHashMap<>();

    public PlayerService(StorageModule storage) {
        this.storage = storage;
    }

    public void warmUp() {
        storage.players().findAll().thenAccept(list -> {
            for (StoredPlayer sp : list) cache.put(sp.uuid, toDomain(sp));
        }).join();
        LOG.info("[KMC/Players] Loaded " + cache.size() + " player records.");
    }

    public KMCPlayer getOrCreate(UUID uuid, String name) {
        return cache.computeIfAbsent(uuid, id -> {
            // Try DB first
            return storage.players().findById(id)
                    .thenApply(opt -> opt.map(this::toDomain)
                            .orElseGet(() -> {
                                KMCPlayer p = new KMCPlayer(id, name);
                                persist(p);
                                return p;
                            }))
                    .join();
        });
    }

    public Optional<KMCPlayer> get(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    public List<KMCPlayer> getAllPlayers() {
        return List.copyOf(cache.values());
    }

    public List<KMCPlayer> getLeaderboard(int limit) {
        return cache.values().stream()
                .sorted(Comparator.comparingInt(KMCPlayer::getPoints).reversed())
                .limit(limit)
                .toList();
    }

    public void persist(KMCPlayer p) {
        storage.players().save(toStored(p));
    }

    public void saveAll() {
        List<StoredPlayer> list = cache.values().stream().map(this::toStored).toList();
        storage.players().saveAll(list);
        LOG.info("[KMC/Players] Saved " + list.size() + " player records.");
    }

    public void softResetAll() {
        cache.values().forEach(KMCPlayer::softReset);
        storage.players().softResetAll();
    }

    // ── Domain ↔ Storage mapping ──────────────────────────────────────────────

    private KMCPlayer toDomain(StoredPlayer sp) {
        KMCPlayer p = new KMCPlayer(sp.uuid, sp.name);
        p.setTeamId(sp.teamId);
        p.setPoints(sp.points);
        // kills/deaths/wins/gamesPlayed are set via reflection-free setters below
        p.addPoints(0); // ensure non-negative
        // manually set immutable fields via package-private setters
        setField(p, sp);
        p.setPlayTimeMinutes(sp.playTimeMinutes);
        p.setWinStreak(sp.winStreak);
        p.setBestWinStreak(sp.bestWinStreak);
        sp.winsPerGame.forEach((gId, wins) -> {
            for (int i = 0; i < wins; i++) p.addWin(gId);
        });
        return p;
    }

    /** Bridges the gap where KMCPlayer doesn't have bulk-set for kills/deaths/etc.
     *  Uses the incremental adders, which is safe since we start from 0. */
    private void setField(KMCPlayer p, StoredPlayer sp) {
        p.setPoints(sp.points);
        for (int i = 0; i < sp.kills;       i++) p.addKill();
        for (int i = 0; i < sp.deaths;      i++) p.addDeath();
        for (int i = 0; i < sp.gamesPlayed; i++) p.recordGamePlayed();
        // wins are set in the winsPerGame loop in toDomain()
    }

    private StoredPlayer toStored(KMCPlayer p) {
        StoredPlayer sp = new StoredPlayer(p.getUuid(), p.getName());
        sp.teamId          = p.getTeamId();
        sp.points          = p.getPoints();
        sp.kills           = p.getKills();
        sp.deaths          = p.getDeaths();
        sp.wins            = p.getWins();
        sp.gamesPlayed     = p.getGamesPlayed();
        sp.playTimeMinutes = p.getPlayTimeMinutes();
        sp.winStreak       = p.getWinStreak();
        sp.bestWinStreak   = p.getBestWinStreak();
        sp.winsPerGame     = new HashMap<>(p.getWinsPerGame());
        return sp;
    }
}
