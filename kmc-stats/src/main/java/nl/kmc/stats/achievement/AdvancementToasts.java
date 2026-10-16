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
 * Shows KMC achievements as real Minecraft advancements: one tab per category in the advancement screen (L),
 * each with its own background, a section per rarity and every achievement below it, and a toast when one is
 * earned. You simply open your advancements.
 *
 * <p>Definitions are registered at runtime with {@code UnsafeValues#loadAdvancement}. Paper stores them in its
 * "bukkit" datapack, so they survive restarts, but registering is cheap and idempotent: an advancement that
 * already exists is skipped. Advancements of an older KMC layout are removed from that datapack (gone after
 * the next restart). A failure never breaks the plugin: {@link #show} returns false and the caller falls back
 * to a title. Every failure is logged with its reason, because Paper returns {@code null} instead of throwing
 * for a definition it does not accept.
 */
public final class AdvancementToasts {

    private static final Logger LOG = Logger.getLogger(AdvancementToasts.class.getName());

    /** The namespace/path prefix of this layout version (a new layout never collides with an old one). */
    private static final String BASE = "v3/";

    /** Backgrounds of the vanilla advancement tabs; the categories take them in turn. */
    private static final String[] BACKGROUNDS = {"stone", "adventure", "nether", "end", "husbandry"};

    /** What the tab needs to know about one achievement, independent of the achievement system behind it. */
    public record Entry(String id, String name, String description, String frame, String color,
                        Material icon, boolean hidden, String group, String rarity) {}

    private final Set<String> tabs = new LinkedHashSet<>();
    private final Set<String> hubs = new LinkedHashSet<>();       // "<group>/<rarity>"
    private final Set<String> knownIds = new HashSet<>();
    private boolean available;

    // ── Registration ──────────────────────────────────────────────────────────

    /** (Re)registers the tabs for the V2 achievement definitions. */
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
                    d.isHidden(), d.getCategory() != null ? d.getCategory().name() : "OTHER",
                    d.getRarity().name()));
        }
        registerEntries(entries);
    }

    /** (Re)registers one tab per group, a hub per rarity inside it and one advancement per entry. */
    @SuppressWarnings("deprecation")
    public void registerEntries(Collection<Entry> entries) {
        available = false;
        tabs.clear();
        hubs.clear();
        knownIds.clear();
        int ok = 0, failed = 0;
        String firstError = null;
        try {
            removeOldLayouts();

            // group -> rarity -> entries (stable order)
            Map<String, Map<String, List<Entry>>> tree = new LinkedHashMap<>();
            for (Entry e : entries) {
                tree.computeIfAbsent(e.group(), g -> new LinkedHashMap<>())
                        .computeIfAbsent(rarityOf(e), r -> new ArrayList<>()).add(e);
            }

            int tabNo = 0;
            for (var g : tree.entrySet()) {
                String group = g.getKey();
                int total = g.getValue().values().stream().mapToInt(List::size).sum();
                Entry first = g.getValue().values().iterator().next().get(0);
                Material tabIcon = pickIcon(g.getValue(), first.icon());
                String bg = BACKGROUNDS[tabNo++ % BACKGROUNDS.length];

                NamespacedKey tabKey = tabKey(group);
                tabs.add(group);
                if (Bukkit.getAdvancement(tabKey) == null) {
                    try {
                        if (Bukkit.getUnsafe().loadAdvancement(tabKey, tabJson(group, tabIcon, bg, total)) == null) {
                            failed++;
                            if (firstError == null) firstError = "tab " + group + " rejected";
                            continue;
                        }
                    } catch (Throwable t) {
                        failed++;
                        if (firstError == null) firstError = "tab " + group + ": " + t;
                        continue;
                    }
                }

                // sections by rarity, easiest first
                for (String rarity : List.of("COMMON", "RARE", "EPIC", "LEGENDARY")) {
                    List<Entry> list = g.getValue().get(rarity);
                    if (list == null || list.isEmpty()) continue;
                    boolean ownHub = !rarity.equalsIgnoreCase(group);       // V1: the tab already is the rarity
                    NamespacedKey parent = tabKey;
                    if (ownHub) {
                        NamespacedKey hubKey = hubKey(group, rarity);
                        hubs.add(group + "/" + rarity);
                        parent = hubKey;
                        if (Bukkit.getAdvancement(hubKey) == null) {
                            try {
                                if (Bukkit.getUnsafe().loadAdvancement(hubKey,
                                        hubJson(tabKey, rarity, list.size())) == null) {
                                    failed++;
                                    if (firstError == null) firstError = "hub " + group + "/" + rarity + " rejected";
                                    continue;
                                }
                            } catch (Throwable t) {
                                failed++;
                                if (firstError == null) firstError = "hub " + group + "/" + rarity + ": " + t;
                                continue;
                            }
                        }
                    }
                    list.sort(Comparator.comparing(e -> e.name() == null ? "" : e.name()));
                    for (Entry e : list) {
                        NamespacedKey key = keyFor(e.id());
                        knownIds.add(e.id());
                        if (Bukkit.getAdvancement(key) != null) { ok++; continue; }
                        try {
                            if (Bukkit.getUnsafe().loadAdvancement(key, childJson(parent, e)) != null) ok++;
                            else {
                                failed++;
                                if (firstError == null) firstError = e.id() + " rejected (icon " + iconId(e.icon()) + ")";
                            }
                        } catch (Throwable t) {
                            failed++;
                            if (firstError == null) firstError = e.id() + ": " + t;
                        }
                    }
                }
            }
            available = ok > 0;
            LOG.info("[KMC/Achievements] Advancement tabs: " + tabs.size() + " tab(s), " + ok + " registered, "
                    + failed + " failed" + (firstError != null ? " (first problem: " + firstError + ")" : "") + ".");
        } catch (Throwable t) {
            LOG.warning("[KMC/Achievements] Custom advancements unavailable (" + t + ") — using titles instead.");
        }
    }

    public boolean isAvailable() { return available; }

    /** Takes the advancements of earlier KMC layouts out of the datapack (effective after a restart). */
    @SuppressWarnings("deprecation")
    private void removeOldLayouts() {
        try {
            List<NamespacedKey> old = new ArrayList<>();
            for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
                NamespacedKey k = it.next().getKey();
                if ("kmc".equals(k.getNamespace()) && !k.getKey().startsWith(BASE)) old.add(k);
            }
            int n = 0;
            for (NamespacedKey k : old) { try { if (Bukkit.getUnsafe().removeAdvancement(k)) n++; } catch (Throwable ignored) { } }
            if (n > 0) LOG.info("[KMC/Achievements] Removed " + n + " advancement(s) of an older layout (gone after a restart).");
        } catch (Throwable ignored) { }
    }

    // ── Showing ───────────────────────────────────────────────────────────────

    /** Makes the tabs visible for a player (roots + hubs) and marks the given achievements as done. */
    public void syncPlayer(Player player, Collection<String> unlockedIds) {
        if (!available || player == null) return;
        try {
            awardStructure(player);
            if (unlockedIds != null) {
                for (String id : unlockedIds) {
                    if (!knownIds.contains(id)) continue;
                    award(player, Bukkit.getAdvancement(keyFor(id)));
                }
            }
        } catch (Throwable ignored) { }
    }

    private void awardStructure(Player player) {
        for (String g : tabs) award(player, Bukkit.getAdvancement(tabKey(g)));
        for (String h : hubs) {
            int slash = h.indexOf('/');
            award(player, Bukkit.getAdvancement(hubKey(h.substring(0, slash), h.substring(slash + 1))));
        }
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
            awardStructure(player);
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

    private static NamespacedKey tabKey(String group) { return new NamespacedKey("kmc", BASE + "tab/" + slug(group)); }

    private static NamespacedKey hubKey(String group, String rarity) {
        return new NamespacedKey("kmc", BASE + "hub/" + slug(group) + "/" + slug(rarity));
    }

    private static String rarityOf(Entry e) {
        String r = e.rarity() == null ? "" : e.rarity().toUpperCase(Locale.ROOT);
        return switch (r) { case "COMMON", "RARE", "EPIC", "LEGENDARY" -> r; default -> "COMMON"; };
    }

    /** The most impressive entry's icon stands for the tab. */
    private static Material pickIcon(Map<String, List<Entry>> byRarity, Material fallback) {
        for (String r : List.of("LEGENDARY", "EPIC", "RARE", "COMMON")) {
            List<Entry> l = byRarity.get(r);
            if (l != null && !l.isEmpty() && l.get(0).icon() != null) return l.get(0).icon();
        }
        return fallback;
    }

    /** Only real items can be an advancement icon; anything else (a block-only material) becomes paper. */
    private static String iconId(Material m) {
        if (m == null || m.isAir() || !m.isItem()) return "minecraft:paper";
        return "minecraft:" + m.getKey().getKey();
    }

    private static String rarityLabel(String rarity) {
        return switch (rarity) {
            case "RARE" -> "Zeldzaam";
            case "EPIC" -> "Episch";
            case "LEGENDARY" -> "Legendarisch";
            default -> "Gewoon";
        };
    }

    private static String rarityColor(String rarity) {
        return switch (rarity) {
            case "RARE" -> "aqua";
            case "EPIC" -> "light_purple";
            case "LEGENDARY" -> "gold";
            default -> "gray";
        };
    }

    private static String rarityIcon(String rarity) {
        return switch (rarity) {
            case "RARE" -> "minecraft:diamond";
            case "EPIC" -> "minecraft:amethyst_shard";
            case "LEGENDARY" -> "minecraft:nether_star";
            default -> "minecraft:iron_ingot";
        };
    }

    private static String tabJson(String group, Material icon, String background, int count) {
        return "{\"display\":{\"icon\":{\"id\":\"" + iconId(icon) + "\"},"
                + "\"title\":{\"text\":" + quote(pretty(group)) + ",\"color\":\"gold\",\"bold\":true},"
                + "\"description\":{\"text\":" + quote(count + " prestaties") + ",\"color\":\"gray\"},"
                + "\"background\":\"minecraft:gui/advancements/backgrounds/" + background + "\","
                + "\"frame\":\"goal\",\"show_toast\":false,\"announce_to_chat\":false,\"hidden\":false},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String hubJson(NamespacedKey tab, String rarity, int count) {
        return "{\"parent\":\"" + tab + "\",\"display\":{\"icon\":{\"id\":\"" + rarityIcon(rarity) + "\"},"
                + "\"title\":{\"text\":" + quote(rarityLabel(rarity)) + ",\"color\":\"" + rarityColor(rarity) + "\",\"bold\":true},"
                + "\"description\":{\"text\":" + quote(count + (count == 1 ? " prestatie" : " prestaties")) + ",\"color\":\"gray\"},"
                + "\"frame\":\"task\",\"show_toast\":false,\"announce_to_chat\":false,\"hidden\":false},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String childJson(NamespacedKey parent, Entry e) {
        String rarity = rarityOf(e);
        StringBuilder desc = new StringBuilder("[{\"text\":" + quote(e.description()) + ",\"color\":\"white\"},"
                + "{\"text\":\"\\n\"},{\"text\":" + quote(rarityLabel(rarity)) + ",\"color\":\"" + rarityColor(rarity)
                + "\",\"italic\":true}");
        if (e.hidden()) desc.append(",{\"text\":\"  ·  Geheim\",\"color\":\"dark_gray\"}");
        desc.append("]");
        return "{\"parent\":\"" + parent + "\",\"display\":{\"icon\":{\"id\":\"" + iconId(e.icon()) + "\"},"
                + "\"title\":{\"text\":" + quote(e.name()) + ",\"color\":\"" + e.color() + "\"},"
                + "\"description\":" + desc + ","
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
