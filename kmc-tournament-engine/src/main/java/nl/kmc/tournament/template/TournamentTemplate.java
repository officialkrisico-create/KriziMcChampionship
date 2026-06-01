package nl.kmc.tournament.template;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Serialisable configuration snapshot for a repeatable tournament format.
 * Templates are saved/loaded as YAML files in the {@code templates/} folder.
 */
public final class TournamentTemplate {

    private String       id;
    private String       displayName;
    private String       description;
    private int          totalRounds;
    private List<String> gameRotation;           // ordered game IDs per round
    private Map<Integer, Double> roundMultipliers; // round → multiplier
    private boolean      votingEnabled;
    private int          votingDurationSeconds;
    private Instant      createdAt;
    private Instant      updatedAt;

    private TournamentTemplate() {}

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String              getId()                   { return id; }
    public String              getDisplayName()          { return displayName; }
    public String              getDescription()          { return description; }
    public int                 getTotalRounds()          { return totalRounds; }
    public List<String>        getGameRotation()         { return gameRotation; }
    public Map<Integer, Double> getRoundMultipliers()    { return roundMultipliers; }
    public boolean             isVotingEnabled()         { return votingEnabled; }
    public int                 getVotingDurationSeconds(){ return votingDurationSeconds; }
    public Instant             getCreatedAt()            { return createdAt; }
    public Instant             getUpdatedAt()            { return updatedAt; }

    @Override public String toString() {
        return "TournamentTemplate{id='" + id + "' rounds=" + totalRounds + "}";
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private final String id;
        private final String displayName;
        private String              description          = "";
        private int                 totalRounds          = 5;
        private List<String>        gameRotation         = List.of();
        private Map<Integer, Double> roundMultipliers    = Map.of(1,1.0,2,2.0,3,3.0,4,4.0,5,5.0);
        private boolean             votingEnabled        = true;
        private int                 votingDurationSeconds = 30;

        private Builder(String id, String displayName) {
            this.id = id; this.displayName = displayName;
        }

        public Builder description(String d)                          { this.description = d;                   return this; }
        public Builder totalRounds(int n)                             { this.totalRounds = n;                   return this; }
        public Builder gameRotation(List<String> games)               { this.gameRotation = List.copyOf(games); return this; }
        public Builder roundMultipliers(Map<Integer, Double> mult)    { this.roundMultipliers = Map.copyOf(mult); return this; }
        public Builder votingEnabled(boolean v)                       { this.votingEnabled = v;                 return this; }
        public Builder votingDurationSeconds(int s)                   { this.votingDurationSeconds = s;         return this; }

        public TournamentTemplate build() {
            TournamentTemplate t = new TournamentTemplate();
            t.id                    = id;
            t.displayName           = displayName;
            t.description           = description;
            t.totalRounds           = totalRounds;
            t.gameRotation          = List.copyOf(gameRotation);
            t.roundMultipliers      = Map.copyOf(roundMultipliers);
            t.votingEnabled         = votingEnabled;
            t.votingDurationSeconds = votingDurationSeconds;
            t.createdAt             = Instant.now();
            t.updatedAt             = Instant.now();
            return t;
        }
    }
}
