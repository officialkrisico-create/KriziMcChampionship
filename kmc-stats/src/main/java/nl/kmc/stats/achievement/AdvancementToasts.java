package nl.kmc.stats.achievement;

import nl.kmc.core.domain.AchievementDefinition;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Logger;

/**
 * Shows KMC achievements as real Minecraft advancements: a "KMC Achievements" tab in the advancement screen
 * (L) with one hub per group and every achievement below it, and a toast when one is earned. It replaces the
 * old achievement list command: you simply open your advancements.
 *
 * <p>Definitions are registered at runtime with {@code UnsafeValues#loadAdvancement}. Paper stores them in its
 * "bukkit" datapack, so they survive restarts, but registering is cheap and idempotent: an advancement that
 * already exists is skipped. A failure never breaks the plugin: {@link #show} returns false and the caller
 * falls back to a title. Every failure is logged with its reason, because Paper returns {@code null} instead of
 * throwing for a definition it does not accept.
 */
public final class AdvancementToasts {

    private static final Logger LOG = Logger.getLogger(AdvancementToasts.class.getName());

    /** The namespace/path prefix of this layout version (a new layout never collides with an old one). */
    private static final String BASE = "v2/";
    private static final NamespacedKey ROOT = new NamespacedKey("kmc", BASE + "root");

    /** What the tab needs to know about one achievement, independent of the achievement system behind it. */
    public record Entry(String id, String name, String description, String frame, String color,
                        Material icon, boolean hidden, String group) {}

    private final Set<String> groups = new LinkedHashSet<>();
    private final Set<String> knownIds = new HashSet<>();
    private boolean available;

    // ── Registration ──────────────────────────────────────────────────────────

    /** (Re)registers the tab for the V2 achievement definitions. */
    public void register(Collection<AchievementDefinition> defs) {
        List<Entry> entries = new ArrayList<>();
        for (AchievementDefinition d : defs) {
            String frame = switch (d.getRarity()) {
                case COMMON -> "task";
                case RARE -> "goal";
                default -> "challenge";
            };
            String color = switch (d.getRarity()) {
                case COMMON -> "gray";
                case RARE -> "aqua";
                case EPIC -> "light_purple";
                case LEGENDARY -> "gold";
            };
            entries.add(new Entry(d.getId(), d.getName(), d.getDescription(), frame, color, d.getIcon(),
                    d.isHidden(), d.getCategory() != null ? d.getCategory().name() : "OTHER"));
        }
        registerEntries(entries);
    }

    /** (Re)registers the root, one hub per group and one advancement per entry. */
    @SuppressWarnings("deprecation")
    public void registerEntries(Collection<Entry> entries) {
        available = false;
        groups.clear();
        knownIds.clear();
        int ok = 0, failed = 0;
        String firstError = null;
        try {
            if (Bukkit.getAdvancement(ROOT) == null && Bukkit.getUnsafe().loadAdvancement(ROOT, rootJson()) == null) {
                LOG.warning("[KMC/Achievements] The server did not accept the advancement tab root — "
                        + "achievements fall back to titles.");
                return;
            }

            // one hub per group, with the first entry's icon
            Map<String, Material> groupIcon = new LinkedHashMap<>();
            for (Entry e : entries) groupIcon.putIfAbsent(e.group(), e.icon());
            for (var g : groupIcon.entrySet()) {
                NamespacedKey key = groupKey(g.getKey());
                groups.add(g.getKey());
                if (Bukkit.getAdvancement(key) != null) continue;
                try {
                    if (Bukkit.getUnsafe().loadAdvancement(key, hubJson(g.getKey(), g.getValue())) == null) {
                        failed++;
                        if (firstError == null) firstError = "hub " + g.getKey() + " rejected";
                    }
                } catch (Throwable t) {
                    failed++;
                    if (firstError == null) firstError = "hub " + g.getKey() + ": " + t;
                }
            }

            for (Entry e : entries) {
                NamespacedKey key = keyFor(e.id());
                knownIds.add(e.id());
                if (Bukkit.getAdvancement(key) != null) { ok++; continue; }
                try {
                    if (Bukkit.getUnsafe().loadAdvancement(key, childJson(e)) != null) ok++;
                    else {
                        failed++;
                        if (firstError == null) firstError = e.id() + " rejected (icon " + iconId(e.icon()) + ")";
                    }
                } catch (Throwable t) {
                    failed++;
                    if (firstError == null) firstError = e.id() + ": " + t;
                }
            }
            available = ok > 0;
            LOG.info("[KMC/Achievements] Advancement tab: " + ok + " registered, " + failed + " failed"
                    + (firstError != null ? " (first problem: " + firstError + ")" : "") + ".");
        } catch (Throwable t) {
            LOG.warning("[KMC/Achievements] Custom advancements unavailable (" + t + ") — using titles instead.");
        }
    }

    public boolean isAvailable() { return available; }

    // ── Showing ───────────────────────────────────────────────────────────────

    /** Makes the tab visible for a player (root + hubs) and marks the given achievements as done. */
    public void syncPlayer(Player player, Collection<String> unlockedIds) {
        if (!available || player == null) return;
        try {
            award(player, Bukkit.getAdvancement(ROOT));
            for (String g : groups) award(player, Bukkit.getAdvancement(groupKey(g)));
            if (unlockedIds != null) {
                for (String id : unlockedIds) {
                    if (!knownIds.contains(id)) continue;
                    award(player, Bukkit.getAdvancement(keyFor(id)));
                }
            }
        } catch (Throwable ignored) { }
    }

    /** Grants the advancement so the client pops its toast. Returns false if that isn't possible. */
    public boolean show(Player player, AchievementDefinition def) {
        return def != null && showEntry(player, def.getId());
    }

    public boolean showEntry(Player player, String id) {
        if (!available || player == null || id == null) return false;
        try {
            Advancement adv = Bukkit.getAdvancement(keyFor(id));
            if (adv == null) return false;
            award(player, Bukkit.getAdvancement(ROOT));
            for (String g : groups) award(player, Bukkit.getAdvancement(groupKey(g)));
            award(player, adv);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void award(Player player, Advancement adv) {
        if (adv == null) return;
        AdvancementProgress pr = player.getAdvancementProgress(adv);
        if (!pr.isDone()) pr.awardCriteria("done");
    }

    /** Turns off vanilla advancement chat messages in every loaded world. */
    public static void silenceVanilla() {
        for (World w : Bukkit.getWorlds()) {
            try { w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false); } catch (Throwable ignored) { }
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    private static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    private static NamespacedKey keyFor(String id) { return new NamespacedKey("kmc", BASE + "ach/" + slug(id)); }

    private static NamespacedKey groupKey(String group) { return new NamespacedKey("kmc", BASE + "grp/" + slug(group)); }

    /** Only real items can be an advancement icon; anything else (a block-only material) becomes paper. */
    private static String iconId(Material m) {
        if (m == null || m.isAir() || !m.isItem()) return "minecraft:paper";
        return "minecraft:" + m.getKey().getKey();
    }

    private static String rootJson() {
        return "{\"display\":{\"icon\":{\"id\":\"minecraft:nether_star\"},"
                + "\"title\":{\"text\":\"KMC Achievements\",\"color\":\"gold\"},"
                + "\"description\":\"Jouw prestaties in het toernooi\","
                + "\"background\":\"minecraft:gui/advancements/backgrounds/stone\","
                + "\"frame\":\"task\",\"show_toast\":false,\"announce_to_chat\":false,\"hidden\":false},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String hubJson(String group, Material icon) {
        String name = pretty(group);
        return "{\"parent\":\"kmc:" + BASE + "root\",\"display\":{\"icon\":{\"id\":\"" + iconId(icon) + "\"},"
                + "\"title\":{\"text\":" + quote(name) + ",\"color\":\"yellow\"},"
                + "\"description\":" + quote(name) + ","
                + "\"frame\":\"task\",\"show_toast\":false,\"announce_to_chat\":false,\"hidden\":false},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String childJson(Entry e) {
        return "{\"parent\":\"kmc:" + BASE + "grp/" + slug(e.group()) + "\",\"display\":{\"icon\":{\"id\":\""
                + iconId(e.icon()) + "\"},"
                + "\"title\":{\"text\":" + quote(e.name()) + ",\"color\":\"" + e.color() + "\"},"
                + "\"description\":" + quote(e.description()) + ","
                + "\"frame\":\"" + e.frame() + "\",\"show_toast\":true,\"announce_to_chat\":false,"
                + "\"hidden\":" + e.hidden() + "},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? s : sb.toString();
    }

    private static String quote(String s) {
        if (s == null) s = "";
        // the definitions may carry legacy colour codes; an advancement title takes plain text + its own colour
        s = s.replaceAll("(?i)[§&][0-9a-fk-or]", "");
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
