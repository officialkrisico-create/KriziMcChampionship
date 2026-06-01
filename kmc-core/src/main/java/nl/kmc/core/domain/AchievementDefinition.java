package nl.kmc.core.domain;

import org.bukkit.Material;

/**
 * Immutable, data-driven description of a single achievement.
 *
 * <p>Definitions are loaded from YAML files under
 * {@code plugins/KMCCoreV2/achievements/} and registered in
 * {@code AchievementCatalog} at startup.
 *
 * <p>The evaluation logic for each {@link AchievementTrigger} lives entirely
 * inside {@code AchievementService} — this class is pure data.
 *
 * <pre>{@code
 * AchievementDefinition def = AchievementDefinition.builder("first_kill", "First Blood")
 *     .description("Get your first kill in any game")
 *     .category(AchievementCategory.PVP)
 *     .rarity(Rarity.COMMON)
 *     .trigger(AchievementTrigger.KILL_COUNT)
 *     .progressTarget(1)
 *     .build();
 * }</pre>
 */
public final class AchievementDefinition {

    public enum Rarity {
        COMMON   ("§7Common",    0xAAAAAA, false),
        RARE     ("§bRare",      0x55FFFF, true),
        EPIC     ("§5Epic",      0xAA00AA, true),
        LEGENDARY("§6§lLegendary", 0xFFAA00, true);

        private final String label;
        private final int    rgb;
        private final boolean broadcastOnUnlock;

        Rarity(String label, int rgb, boolean broadcastOnUnlock) {
            this.label            = label;
            this.rgb              = rgb;
            this.broadcastOnUnlock = broadcastOnUnlock;
        }
        public String  getLabel()            { return label; }
        public int     getRgb()              { return rgb; }
        public boolean isBroadcastOnUnlock() { return broadcastOnUnlock; }
    }

    // Core identity
    private final String              id;
    private final String              name;
    private final String              description;
    private final AchievementCategory category;
    private final Rarity              rarity;
    private final Material            icon;

    // Visibility
    private final boolean hidden;  // stays hidden until unlocked

    // Trigger & condition
    private final AchievementTrigger trigger;
    private final int                progressTarget; // 0 = one-shot boolean
    private final String             scopeGameId;   // null = any game
    private final String             objectiveType; // for GAME_OBJECTIVE trigger
    private final long               objectiveThreshold; // 0 = no threshold
    private final String             clutchType;    // ClutchMomentEvent.ClutchType name

    // Reward
    private final AchievementReward reward;

    private AchievementDefinition(Builder b) {
        this.id                 = b.id;
        this.name               = b.name;
        this.description        = b.description;
        this.category           = b.category;
        this.rarity             = b.rarity;
        this.icon               = b.icon;
        this.hidden             = b.hidden;
        this.trigger            = b.trigger;
        this.progressTarget     = b.progressTarget;
        this.scopeGameId        = b.scopeGameId;
        this.objectiveType      = b.objectiveType;
        this.objectiveThreshold = b.objectiveThreshold;
        this.clutchType         = b.clutchType;
        this.reward             = b.reward;
    }

    public static Builder builder(String id, String name) { return new Builder(id, name); }

    public String              getId()                 { return id; }
    public String              getName()               { return name; }
    public String              getDescription()        { return description; }
    public AchievementCategory getCategory()           { return category; }
    public Rarity              getRarity()             { return rarity; }
    public Material            getIcon()               { return icon; }
    public boolean             isHidden()              { return hidden; }
    public AchievementTrigger  getTrigger()            { return trigger; }
    public int                 getProgressTarget()     { return progressTarget; }
    public boolean             isProgressBased()       { return progressTarget > 1; }
    public String              getScopeGameId()        { return scopeGameId; }
    public String              getObjectiveType()      { return objectiveType; }
    public long                getObjectiveThreshold() { return objectiveThreshold; }
    public String              getClutchType()         { return clutchType; }
    public AchievementReward   getReward()             { return reward; }

    @Override public String toString() { return "AchievementDefinition{id='" + id + "', rarity=" + rarity + "}"; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private final String id;
        private final String name;
        private String              description        = "";
        private AchievementCategory category           = AchievementCategory.PROGRESSION;
        private Rarity              rarity             = Rarity.COMMON;
        private Material            icon               = Material.PAPER;
        private boolean             hidden             = false;
        private AchievementTrigger  trigger            = AchievementTrigger.MANUAL;
        private int                 progressTarget     = 1;
        private String              scopeGameId        = null;
        private String              objectiveType      = null;
        private long                objectiveThreshold = 0;
        private String              clutchType         = null;
        private AchievementReward   reward             = AchievementReward.none();

        private Builder(String id, String name) { this.id = id; this.name = name; }

        public Builder description(String v)        { description        = v;  return this; }
        public Builder category(AchievementCategory v){ category         = v;  return this; }
        public Builder rarity(Rarity v)             { rarity             = v;  return this; }
        public Builder icon(Material v)             { icon               = v;  return this; }
        public Builder hidden(boolean v)            { hidden             = v;  return this; }
        public Builder trigger(AchievementTrigger v){ trigger            = v;  return this; }
        public Builder progressTarget(int v)        { progressTarget     = v;  return this; }
        public Builder scopeGameId(String v)        { scopeGameId        = v;  return this; }
        public Builder objectiveType(String v)      { objectiveType      = v;  return this; }
        public Builder objectiveThreshold(long v)   { objectiveThreshold = v;  return this; }
        public Builder clutchType(String v)         { clutchType         = v;  return this; }
        public Builder reward(AchievementReward v)  { reward             = v;  return this; }

        public AchievementDefinition build() { return new AchievementDefinition(this); }
    }
}
