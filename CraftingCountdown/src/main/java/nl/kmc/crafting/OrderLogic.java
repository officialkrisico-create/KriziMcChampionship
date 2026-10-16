package nl.kmc.crafting;

import java.util.*;

/**
 * The rules of Crafting Countdown that do not need a server: which orders can be generated, what they are
 * worth, the speed and streak bonuses, how points are split over the players who delivered, and the stock
 * of a pile. Items are plain ids (e.g. {@code OAK_LOG}), so all of it can be unit-tested.
 */
public final class OrderLogic {

    private OrderLogic() { }

    /**
     * One orderable item. {@code tier}: 1 common ... 3 rare; {@code value}: points per item.
     * {@code requires}: the raw materials the item is crafted from (they must come from the piles); empty
     * means the item itself is picked up from a pile.
     */
    public record PoolItem(String item, int tier, int min, int max, double value, Set<String> requires) {
        public PoolItem(String item, int tier, int min, int max, double value) {
            this(item, tier, min, max, value, Set.of());
        }
    }

    /** Orders get harder once a team has delivered {@code fromOrder} orders. */
    public record Difficulty(int fromOrder, int lines, int maxTier, double scale) {}

    /** One line of an order: this item, this many. */
    public record Line(String item, int required) {}

    // ── Order generation ──────────────────────────────────────────────────────

    /** The row of the table that applies after {@code completed} orders (the highest {@code fromOrder} reached). */
    public static Difficulty difficultyFor(List<Difficulty> table, int completed) {
        Difficulty best = null;
        for (Difficulty d : table) {
            if (d.fromOrder() <= completed && (best == null || d.fromOrder() >= best.fromOrder())) best = d;
        }
        return best != null ? best : new Difficulty(0, 2, 1, 1.0);
    }

    /**
     * Pool entries that may be ordered: every raw material (or the item itself when it has no recipe) can be
     * picked up from a placed pile, and the tier is not above the allowed one.
     */
    public static List<PoolItem> candidates(List<PoolItem> pool, Set<String> obtainable, int maxTier) {
        List<PoolItem> out = new ArrayList<>();
        for (PoolItem p : pool) {
            Set<String> need = p.requires().isEmpty() ? Set.of(p.item()) : p.requires();
            if (obtainable.containsAll(need) && p.tier() <= maxTier) out.add(p);
        }
        return out;
    }

    /**
     * An order of {@code d.lines()} different items (fewer when the arena offers fewer). It always holds one
     * item of the highest allowed tier that is available, so an order gets more valuable as the tier grows.
     * Never asks for an item that cannot be obtained, and never for more than {@code maxQty} of one item.
     */
    public static List<Line> generate(List<PoolItem> pool, Set<String> obtainable, Difficulty d, int maxQty, Random rnd) {
        List<PoolItem> cand = candidates(pool, obtainable, Math.max(1, d.maxTier()));
        if (cand.isEmpty()) return List.of();
        Collections.shuffle(cand, rnd);
        int n = Math.max(1, Math.min(d.lines(), cand.size()));
        List<PoolItem> chosen = new ArrayList<>(cand.subList(0, n));

        int top = 0;
        for (PoolItem p : cand) top = Math.max(top, p.tier());
        final int topTier = top;
        boolean hasTop = chosen.stream().anyMatch(p -> p.tier() == topTier);
        if (!hasTop) {
            List<PoolItem> tops = new ArrayList<>();
            for (PoolItem p : cand) if (p.tier() == topTier && !chosen.contains(p)) tops.add(p);
            if (!tops.isEmpty()) chosen.set(0, tops.get(rnd.nextInt(tops.size())));
        }

        List<Line> lines = new ArrayList<>();
        for (PoolItem p : chosen) {
            int lo = Math.max(1, Math.min(p.min(), p.max())), hi = Math.max(lo, Math.max(p.min(), p.max()));
            int base = lo + rnd.nextInt(hi - lo + 1);
            int qty = (int) Math.round(base * Math.max(0.1, d.scale()));
            lines.add(new Line(p.item(), Math.max(1, Math.min(Math.max(1, maxQty), qty))));
        }
        return lines;
    }

    // ── Points ────────────────────────────────────────────────────────────────

    /** What the items of an order are worth: sum of quantity × item value × the global multiplier. */
    public static int itemPoints(List<Line> lines, Map<String, PoolItem> byItem, double perValue) {
        double sum = 0;
        for (Line l : lines) {
            PoolItem p = byItem.get(l.item());
            sum += l.required() * (p != null ? p.value() : 1.0);
        }
        return (int) Math.round(sum * perValue);
    }

    /** Full bonus at or below par time, fading to nothing at twice par. */
    public static int speedBonus(long elapsedTicks, long parTicks, int maxBonus) {
        if (maxBonus <= 0 || parTicks <= 0) return 0;
        if (elapsedTicks <= parTicks) return maxBonus;
        if (elapsedTicks >= parTicks * 2) return 0;
        double left = 1.0 - (elapsedTicks - parTicks) / (double) parTicks;
        return (int) Math.round(maxBonus * left);
    }

    /** From the second delivery in a row: {@code perStep} for every extra order in the streak, up to {@code cap}. */
    public static int streakBonus(int streak, int perStep, int cap) {
        if (streak < 2 || perStep <= 0) return 0;
        return Math.min(Math.max(0, cap), (streak - 1) * perStep);
    }

    /**
     * Splits {@code total} over the keys in proportion to their weights (largest remainder, so the parts add
     * up to exactly {@code total}). Keys with weight 0 get nothing.
     */
    public static <K> Map<K, Integer> split(int total, Map<K, Integer> weights) {
        Map<K, Integer> out = new LinkedHashMap<>();
        long sum = 0;
        for (int w : weights.values()) sum += Math.max(0, w);
        if (total <= 0 || sum <= 0) return out;
        List<K> keys = new ArrayList<>();
        Map<K, Double> frac = new HashMap<>();
        int given = 0;
        for (var e : weights.entrySet()) {
            int w = Math.max(0, e.getValue());
            if (w == 0) continue;
            double exact = total * (w / (double) sum);
            int part = (int) Math.floor(exact);
            out.put(e.getKey(), part);
            frac.put(e.getKey(), exact - part);
            keys.add(e.getKey());
            given += part;
        }
        keys.sort((a, b) -> {
            int c = Double.compare(frac.get(b), frac.get(a));
            return c != 0 ? c : Integer.compare(weights.get(b), weights.get(a));
        });
        for (int i = 0; given < total && !keys.isEmpty(); i++, given++) {
            K k = keys.get(i % keys.size());
            out.merge(k, 1, Integer::sum);
        }
        out.values().removeIf(v -> v <= 0);
        return out;
    }

    // ── Carrying ──────────────────────────────────────────────────────────────

    /** How many more items fit in the carry limit. */
    public static int carryRoom(int carried, int max) { return Math.max(0, max - Math.max(0, carried)); }

    // ── Pile stock ────────────────────────────────────────────────────────────

    /** The stock of one item in one pile: it empties by taking and refills by restocking. */
    public static final class PileStock {
        private final int max;
        private int amount;

        public PileStock(int max) {
            this.max = Math.max(1, max);
            this.amount = this.max;
        }

        public int amount() { return amount; }
        public int max() { return max; }

        /** @return how many items were really taken (never more than the stock). */
        public int take(int want) {
            int got = Math.max(0, Math.min(want, amount));
            amount -= got;
            return got;
        }

        /** Puts items back (a refused hand-over, a restock); never above the maximum. */
        public void add(int n) { amount = Math.min(max, amount + Math.max(0, n)); }
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    /** {@code OAK_LOG} becomes {@code Oak Log}. */
    public static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
