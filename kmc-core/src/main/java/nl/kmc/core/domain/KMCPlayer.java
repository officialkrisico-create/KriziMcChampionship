package nl.kmc.core.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Live mutable state for a tournament participant. */
public final class KMCPlayer {

    private final UUID uuid;
    private String name;
    private String teamId;

    // Tournament-scoped (soft reset between events)
    private int points;
    private int kills;
    private int deaths;
    private int wins;
    private int gamesPlayed;

    // Lifetime
    private long playTimeMinutes;
    private int  winStreak;
    private int  bestWinStreak;
    private final Map<String, Integer> winsPerGame = new HashMap<>();

    // Transient session flags
    private boolean teamChatEnabled  = false;
    private long    sessionJoinTime  = 0;

    public KMCPlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID   getUuid()            { return uuid; }
    public String getName()            { return name; }
    public String getTeamId()          { return teamId; }
    public int    getPoints()          { return points; }
    public int    getKills()           { return kills; }
    public int    getDeaths()          { return deaths; }
    public int    getWins()            { return wins; }
    public int    getGamesPlayed()     { return gamesPlayed; }
    public long   getPlayTimeMinutes() { return playTimeMinutes; }
    public int    getWinStreak()       { return winStreak; }
    public int    getBestWinStreak()   { return bestWinStreak; }
    public Map<String, Integer> getWinsPerGame() { return Map.copyOf(winsPerGame); }
    public boolean isTeamChatEnabled() { return teamChatEnabled; }
    public long   getSessionJoinTime() { return sessionJoinTime; }

    // ── Setters / mutators ────────────────────────────────────────────────────

    public void setName(String name)             { this.name = name; }
    public void setTeamId(String teamId)         { this.teamId = teamId; }
    public void setPoints(int points)            { this.points = Math.max(0, points); }
    public void setTeamChatEnabled(boolean v)    { this.teamChatEnabled = v; }
    public void setSessionJoinTime(long millis)  { this.sessionJoinTime = millis; }
    public void setPlayTimeMinutes(long v)       { this.playTimeMinutes = v; }
    public void setWinStreak(int v)              { this.winStreak = v; }
    public void setBestWinStreak(int v)          { this.bestWinStreak = v; }

    // Bulk setters — used when hydrating from another store (e.g. the V1 adapter).
    public void setKills(int v)       { this.kills = Math.max(0, v); }
    public void setDeaths(int v)      { this.deaths = Math.max(0, v); }
    public void setWins(int v)        { this.wins = Math.max(0, v); }
    public void setGamesPlayed(int v) { this.gamesPlayed = Math.max(0, v); }
    public void putWinsPerGame(String gameId, int wins) {
        if (gameId != null) this.winsPerGame.put(gameId, wins);
    }

    public void addPoints(int amount)    { this.points = Math.max(0, this.points + amount); }
    public void addKill()                { this.kills++; }
    public void addDeath()               { this.deaths++; }
    public void addWin(String gameId) {
        this.wins++;
        this.winStreak++;
        if (winStreak > bestWinStreak) bestWinStreak = winStreak;
        winsPerGame.merge(gameId, 1, Integer::sum);
    }
    public void recordGamePlayed()       { this.gamesPlayed++; }
    public void resetWinStreak()         { this.winStreak = 0; }

    public void softReset() {
        points = 0; kills = 0; deaths = 0; wins = 0; gamesPlayed = 0;
    }

    public void accumulateSessionTime() {
        if (sessionJoinTime > 0) {
            long elapsed = (System.currentTimeMillis() - sessionJoinTime) / 60_000;
            playTimeMinutes += elapsed;
            sessionJoinTime = 0;
        }
    }

    @Override public String toString() { return "KMCPlayer{uuid=" + uuid + ", name='" + name + "'}"; }
}
