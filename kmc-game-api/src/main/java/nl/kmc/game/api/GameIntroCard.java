package nl.kmc.game.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Presentation metadata shown before every game starts.
 * Each game plugin registers one in its onEnable().
 *
 * <pre>{@code
 * GameIntroCard.builder("skywars", "SkyWars")
 *     .objective("Be the last player standing")
 *     .addScoringLine("+50 pts  — Kill")
 *     .addScoringLine("+500 pts — 1st Place")
 *     .addScoringLine("+5 pts   — Survival bonus per minute")
 *     .build();
 * }</pre>
 */
public final class GameIntroCard {

    private final String       gameId;
    private final String       displayName;
    private final String       objective;
    private final List<String> scoringLines;
    private final List<String> ruleLines;

    private GameIntroCard(Builder b) {
        this.gameId      = b.gameId;
        this.displayName = b.displayName;
        this.objective   = b.objective;
        this.scoringLines = List.copyOf(b.scoringLines);
        this.ruleLines    = List.copyOf(b.ruleLines);
    }

    public String       getGameId()      { return gameId; }
    public String       getDisplayName() { return displayName; }
    public String       getObjective()   { return objective; }
    public List<String> getScoringLines(){ return scoringLines; }
    public List<String> getRuleLines()   { return ruleLines; }

    public static Builder builder(String gameId, String displayName) {
        return new Builder(gameId, displayName);
    }

    public static final class Builder {
        private final String gameId;
        private final String displayName;
        private String       objective    = "";
        private final List<String> scoringLines = new ArrayList<>();
        private final List<String> ruleLines    = new ArrayList<>();

        private Builder(String gameId, String displayName) {
            this.gameId = gameId; this.displayName = displayName;
        }

        public Builder objective(String obj)        { this.objective = obj;         return this; }
        public Builder addScoringLine(String line)  { scoringLines.add(line);       return this; }
        public Builder addRuleLine(String line)     { ruleLines.add(line);          return this; }
        public GameIntroCard build()                { return new GameIntroCard(this); }
    }
}
