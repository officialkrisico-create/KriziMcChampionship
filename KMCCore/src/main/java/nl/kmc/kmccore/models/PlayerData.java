package nl.kmc.kmccore.models;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores all persistent statistics for a single player.
 *
 * <p>NEW: deaths counter. Tracked per tournament; reset on soft reset
 * along with kills/points/wins. Used in the post-event signed book.
 */
public class PlayerData {

    private final UUID uuid;
    private String     name;
    private String     teamId;

    // ---- Basic stats -----------------------------------------------
    private int  points;
    private int  kills;
    private int  wins;
    private int  deaths;

    // ---- Extended / lifetime stats ---------------------------------
    private int  gamesPlayed;
    private int  totalPlayTimeMinutes;
    private int  winStreak;
    private int  bestWinStreak;

    private Map<String, Integer> winsPerGame = new HashMap<>();
    private transient long gameSessionStart = -1;

    private boolean teamChatEnabled;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ---- Points ----------------------------------------------------
    public int  getPoints()         { return points; }
    public void setPoints(int v)    { this.points = Math.max(0, v); }
    public void addPoints(int v)    { this.points = Math.max(0, this.points + v); }
    public void removePoints(int v) { this.points = Math.max(0, this.points - v); }

    // ---- Kills / Deaths --------------------------------------------
    public int  getKills()      { return kills; }
    public void setKills(int v) { this.kills = Math.max(0, v); }
    public void addKill()       { this.kills++; }

    public int  getDeaths()      { return deaths; }
    public void setDeaths(int v) { this.deaths = Math.max(0, v); }
    public void addDeath()       { this.deaths++; }

    // ---- Wins ------------------------------------------------------
    public int  getWins()       { return wins; }
    public void setWins(int v)  { this.wins = Math.max(0, v); }

    public void addWin(String gameId) {
        this.wins++;
        this.winStreak++;
        if (this.winStreak > this.bestWinStreak) this.bestWinStreak = this.winStreak;
        if (gameId != null && !gameId.isBlank()) winsPerGame.merge(gameId, 1, Integer::sum);
    }
    public void addWin() { addWin(null); }

    // ---- Streaks ---------------------------------------------------
    public void resetStreak()           { this.winStreak = 0; }
    public int  getWinStreak()          { return winStreak; }
    public void setWinStreak(int v)     { this.winStreak = Math.max(0, v); }
    public int  getBestWinStreak()      { return bestWinStreak; }
    public void setBestWinStreak(int v) { this.bestWinStreak = Math.max(0, v); }

    // ---- Games played / time ---------------------------------------
    public int  getGamesPlayed()               { return gamesPlayed; }
    public void setGamesPlayed(int v)          { this.gamesPlayed = Math.max(0, v); }
    public void incrementGamesPlayed()         { this.gamesPlayed++; }
    public int  getTotalPlayTimeMinutes()      { return totalPlayTimeMinutes; }
    public void setTotalPlayTimeMinutes(int v) { this.totalPlayTimeMinutes = Math.max(0, v); }
    public void addPlayTimeMinutes(int v)      { this.totalPlayTimeMinutes += Math.max(0, v); }

    public void startGameSession() { this.gameSessionStart = System.currentTimeMillis(); }
    public void endGameSession() {
        if (gameSessionStart < 0) return;
        addPlayTimeMinutes((int)((System.currentTimeMillis() - gameSessionStart) / 60_000));
        gameSessionStart = -1;
    }

    // ---- Per-game wins ---------------------------------------------
    public Map<String, Integer> getWinsPerGame()        { return winsPerGame; }
    public void setWinsPerGame(Map<String, Integer> m)  { this.winsPerGame = m != null ? m : new HashMap<>(); }
    public String getFavouriteGame() {
        return winsPerGame.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
    }

    // ---- Team / chat -----------------------------------------------
    public String  getTeamId()                   { return teamId; }
    public void    setTeamId(String id)          { this.teamId = id; }
    public boolean hasTeam()                     { return teamId != null && !teamId.isEmpty(); }
    public boolean isTeamChatEnabled()           { return teamChatEnabled; }
    public void    setTeamChatEnabled(boolean v) { this.teamChatEnabled = v; }
    public void    toggleTeamChat()              { this.teamChatEnabled = !this.teamChatEnabled; }

    // ---- Identity --------------------------------------------------
    public UUID   getUuid() { return uuid; }
    public String getName() { return name; }
    public void   setName(String n) { this.name = n; }

    @Override
    public String toString() {
        return "PlayerData{name=" + name + ", points=" + points + ", wins=" + wins + "}";
    }
}
