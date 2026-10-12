package nl.kmc.crafting;

import org.bukkit.Material;

import java.util.*;

/** One task: deliver {@code amount} of {@code item}; the supply chest gets {@code resources}. */
public record Recipe(String id, Material item, int amount, Difficulty difficulty, Map<Material, Integer> resources) {

    public enum Difficulty { EASY, MEDIUM, HARD }

    /**
     * Parses recipes from raw config maps; invalid entries (unknown item or resource, no resources,
     * amount below 1) are skipped and described in {@code problems}.
     */
    public static List<Recipe> parse(List<?> raw, List<String> problems) {
        List<Recipe> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) { problems.add("ongeldig recept: " + o); continue; }
            String id = String.valueOf(m.get("id"));
            Material item = Material.matchMaterial(String.valueOf(m.get("item")));
            if (item == null || item.name().startsWith("LEGACY_")) { problems.add(id + ": onbekend item " + m.get("item")); continue; }
            int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
            if (amount < 1) { problems.add(id + ": amount moet minimaal 1 zijn"); continue; }
            Difficulty diff;
            try { diff = Difficulty.valueOf(String.valueOf(m.get("difficulty")).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { problems.add(id + ": onbekende moeilijkheid " + m.get("difficulty")); continue; }
            Map<Material, Integer> res = new LinkedHashMap<>();
            boolean bad = false;
            if (m.get("resources") instanceof Map<?, ?> rm) {
                for (var e : rm.entrySet()) {
                    Material rmat = Material.matchMaterial(String.valueOf(e.getKey()));
                    int n = e.getValue() instanceof Number num ? num.intValue() : 0;
                    if (rmat == null || rmat.name().startsWith("LEGACY_") || n < 1) {
                        problems.add(id + ": ongeldige grondstof " + e.getKey() + "=" + e.getValue());
                        bad = true;
                        break;
                    }
                    res.merge(rmat, n, Integer::sum);
                }
            }
            if (bad) continue;
            if (res.isEmpty()) { problems.add(id + ": geen grondstoffen — niet uitvoerbaar"); continue; }
            out.add(new Recipe(id, item, amount, diff, res));
        }
        return out;
    }

    /**
     * Chooses the recipe for a round: the wanted difficulty and not used before; when that pool is
     * exhausted, another unused one (or a repeat when {@code allowRepeat}).
     */
    public static Recipe pick(List<Recipe> pool, Difficulty want, Set<String> used, boolean allowRepeat, Random rnd) {
        List<Recipe> fresh = new ArrayList<>();
        for (Recipe r : pool) if (r.difficulty() == want && !used.contains(r.id())) fresh.add(r);
        if (fresh.isEmpty()) {
            if (allowRepeat) {
                for (Recipe r : pool) if (r.difficulty() == want) fresh.add(r);
            } else {
                for (Recipe r : pool) if (!used.contains(r.id())) fresh.add(r);
            }
        }
        if (fresh.isEmpty()) fresh.addAll(pool);     // everything used: repeat rather than crash
        return fresh.isEmpty() ? null : fresh.get(rnd.nextInt(fresh.size()));
    }

    public static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
