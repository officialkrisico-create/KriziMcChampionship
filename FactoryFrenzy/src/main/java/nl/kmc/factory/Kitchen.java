package nl.kmc.factory;

import java.util.*;

/**
 * The kitchen without a server (Overcooked style): what a player can do with the thing he is holding and the
 * station he clicks. A player carries at most one item, and every click on a station is resolved here into a
 * new hand, a message key and a sound, so the rules can be tested without Minecraft.
 *
 * <p>Stations: crates (endless ingredients), chopping boards (click to chop), pots and pans (cook, and burn
 * when left alone), counters (a table and the place where a plate is assembled), the plate rack, the sink (dirty
 * plates are washed into clean ones), the serving hatch and the bin.
 */
public final class Kitchen {

    private Kitchen() { }

    public enum Kind { CRATE, BOARD, POT, COUNTER, SINK, RACK, SERVE, TRASH }

    /** A cooking step: {@code station} (pot or pan) takes {@code in} and makes {@code out} after {@code ticks}. */
    public record CookRecipe(String station, Map<String, Integer> in, String out, int ticks, boolean onPlate) {
        public int total() { int n = 0; for (int v : in.values()) n += v; return n; }
    }

    /** A dish put together on a counter from loose parts (an item multiset). */
    public record Assembly(String id, Map<String, Integer> parts) { }

    public record Config(Map<String, String> chop, List<CookRecipe> cook, List<Assembly> assemblies,
                         int chopClicks, int washClicks, int burnTicks, int startPlates, int dirtyDelayTicks) {
        public boolean isPlateable(String item) {
            for (CookRecipe r : cook) if (r.onPlate() && r.out().equals(item)) return true;
            return false;
        }
    }

    /** What a click did. {@code hand} is the new hand (null = empty); {@code serve} is a dish offered at the hatch. */
    public record Result(String hand, String msg, String sound, boolean changed, String serve) {
        static Result none(String hand) { return new Result(hand, null, null, false, null); }
        static Result of(String hand, String msg, String sound) { return new Result(hand, msg, sound, true, null); }
        static Result no(String hand, String msg) { return new Result(hand, msg, "no", false, null); }
    }

    /** What one team shares: clean plates in the rack and dirty ones waiting at the sink. */
    public static final class Team {
        public int plates;
        public int dirty;
        public int washProgress;
        public Team(int plates) { this.plates = plates; }
    }

    /** One station and what is on or in it. */
    public static final class Station {
        public final Kind kind;
        public final String id;
        public final String item;                // crate: what it hands out; pot: "pot" or "pan"
        // board
        public String board;                     // the item on the board
        public int chops;
        // counter
        public String loose;                     // a single loose item
        public boolean plate;                    // a plate stands on the counter
        public final List<String> parts = new ArrayList<>();
        public String dish;                      // a finished dish on the plate
        // pot / pan
        public final List<String> contents = new ArrayList<>();
        public CookRecipe cooking;
        public int cookLeft;
        public String ready;                     // finished food that waits to be taken
        public int burnLeft;
        public boolean burnt;

        public Station(Kind kind, String id, String item) { this.kind = kind; this.id = id; this.item = item; }

        /** True while the pot or pan has food that is cooking (or about to burn): the label shows a timer. */
        public boolean busy() { return cooking != null || ready != null || burnt; }
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    public static boolean isDish(String item) { return item != null && item.startsWith("dish_"); }

    public static String dishId(String item) { return item.substring(5); }

    public static Result interact(Config cfg, Team team, Station s, String hand) {
        return switch (s.kind) {
            case CRATE -> hand == null ? Result.of(s.item, "took", "pickup") : Result.no(hand, "hands-full");
            case BOARD -> board(cfg, s, hand);
            case POT -> pot(cfg, s, hand);
            case COUNTER -> counter(cfg, s, hand);
            case SINK -> sink(cfg, team, s, hand);
            case RACK -> rack(team, hand);
            case SERVE -> isDish(hand) ? new Result(hand, null, null, false, dishId(hand)) : Result.no(hand, "serve-dish");
            case TRASH -> trash(team, hand);
        };
    }

    private static Result board(Config cfg, Station s, String hand) {
        if (hand != null) {
            if (s.board != null) return Result.no(hand, "board-busy");
            if (!cfg.chop().containsKey(hand)) return Result.no(hand, "cannot-chop");
            s.board = hand;
            s.chops = 0;
            return Result.of(null, "placed", "place");
        }
        if (s.board == null) return Result.none(null);
        if (cfg.chop().containsValue(s.board) && !cfg.chop().containsKey(s.board)) {
            String taken = s.board;                         // already chopped: pick it up
            s.board = null;
            s.chops = 0;
            return Result.of(taken, "took", "pickup");
        }
        s.chops++;
        if (s.chops >= Math.max(1, cfg.chopClicks())) {
            s.board = cfg.chop().get(s.board);
            s.chops = 0;
            return Result.of(null, "chopped", "chopped");
        }
        return Result.of(null, "chop", "chop");
    }

    private static Result pot(Config cfg, Station s, String hand) {
        if (s.burnt) {
            if (hand != null) return Result.no(hand, "burnt");
            s.burnt = false;
            s.ready = null;
            s.contents.clear();
            return Result.of(null, "cleaned", "pickup");
        }
        if (hand != null) {
            if (s.ready != null && "plate".equals(hand) && cfg.isPlateable(s.ready)) {
                String dish = "dish_" + s.ready;
                s.ready = null;
                s.burnLeft = 0;
                return Result.of(dish, "plated", "pickup");
            }
            if (s.cooking != null || s.ready != null) return Result.no(hand, "pot-busy");
            if (!accepts(cfg, s, hand)) return Result.no(hand, "pot-refuses");
            s.contents.add(hand);
            CookRecipe r = complete(cfg, s);
            if (r != null) {
                s.cooking = r;
                s.cookLeft = r.ticks();
                s.contents.clear();
                return Result.of(null, "cooking", "cook");
            }
            return Result.of(null, "added", "place");
        }
        if (s.ready != null && !cfg.isPlateable(s.ready)) {
            String out = s.ready;
            s.ready = null;
            s.burnLeft = 0;
            return Result.of(out, "took", "pickup");
        }
        if (s.ready != null) return Result.no(null, "needs-plate");
        if (s.cooking == null && !s.contents.isEmpty()) {
            String back = s.contents.remove(s.contents.size() - 1);
            return Result.of(back, "took", "pickup");
        }
        return Result.none(null);
    }

    /** Does a recipe of this station still want {@code item} given what is in it already? */
    private static boolean accepts(Config cfg, Station s, String item) {
        for (CookRecipe r : cfg.cook()) {
            if (!r.station().equals(s.item)) continue;
            Map<String, Integer> have = count(s.contents);
            have.merge(item, 1, Integer::sum);
            boolean fits = true;
            for (var e : have.entrySet()) if (e.getValue() > r.in().getOrDefault(e.getKey(), 0)) { fits = false; break; }
            if (fits) return true;
        }
        return false;
    }

    private static CookRecipe complete(Config cfg, Station s) {
        Map<String, Integer> have = count(s.contents);
        for (CookRecipe r : cfg.cook()) if (r.station().equals(s.item) && r.in().equals(have)) return r;
        return null;
    }

    private static Map<String, Integer> count(List<String> items) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String i : items) m.merge(i, 1, Integer::sum);
        return m;
    }

    /** Advances cooking and burning by one tick. @return true when the station's look changed. */
    public static boolean tick(Config cfg, Station s) {
        if (s.kind != Kind.POT) return false;
        if (s.cooking != null) {
            if (--s.cookLeft <= 0) {
                s.ready = s.cooking.out();
                s.cooking = null;
                s.burnLeft = cfg.burnTicks();
                return true;
            }
            return s.cookLeft % 20 == 0;
        }
        if (s.ready != null && cfg.burnTicks() > 0) {
            if (--s.burnLeft <= 0) {
                s.burnt = true;
                s.ready = null;
                return true;
            }
            return s.burnLeft % 20 == 0;
        }
        return false;
    }

    private static Result counter(Config cfg, Station s, String hand) {
        if (hand == null) {
            if (s.dish != null) {
                String d = "dish_" + s.dish;
                s.dish = null;
                s.plate = false;
                s.parts.clear();
                return Result.of(d, "took", "pickup");
            }
            if (s.loose != null) { String l = s.loose; s.loose = null; return Result.of(l, "took", "pickup"); }
            if (s.plate && s.parts.isEmpty()) { s.plate = false; return Result.of("plate", "took", "pickup"); }
            if (s.plate) return Result.no(null, "plate-has-parts");
            return Result.none(null);
        }
        if ("plate".equals(hand)) {
            if (s.plate || s.loose != null || s.dish != null) return Result.no(hand, "counter-busy");
            s.plate = true;
            return Result.of(null, "placed", "place");
        }
        if (isDish(hand)) {
            if (s.plate || s.loose != null || s.dish != null) return Result.no(hand, "counter-busy");
            s.plate = true;
            s.dish = dishId(hand);
            return Result.of(null, "placed", "place");
        }
        if (s.plate && s.dish == null) {
            List<String> next = new ArrayList<>(s.parts);
            next.add(hand);
            Assembly done = null;
            boolean fits = false;
            Map<String, Integer> have = count(next);
            for (Assembly a : cfg.assemblies()) {
                boolean ok = true;
                for (var e : have.entrySet()) if (e.getValue() > a.parts().getOrDefault(e.getKey(), 0)) { ok = false; break; }
                if (ok) fits = true;
                if (a.parts().equals(have)) done = a;
            }
            if (!fits) return Result.no(hand, "not-on-plate");
            s.parts.add(hand);
            if (done != null) {
                s.dish = done.id();
                s.parts.clear();
                return Result.of(null, "dish-ready", "ready");
            }
            return Result.of(null, "added", "place");
        }
        if (s.plate || s.loose != null || s.dish != null) return Result.no(hand, "counter-busy");
        s.loose = hand;
        return Result.of(null, "placed", "place");
    }

    private static Result sink(Config cfg, Team t, Station s, String hand) {
        if ("dirty_plate".equals(hand)) { t.dirty++; return Result.of(null, "placed", "place"); }
        if (hand != null) return Result.no(hand, "sink-only-plates");
        if (t.dirty <= 0) return Result.no(null, "nothing-to-wash");
        t.washProgress++;
        if (t.washProgress >= Math.max(1, cfg.washClicks())) {
            t.washProgress = 0;
            t.dirty--;
            t.plates++;
            return Result.of(null, "washed", "washed");
        }
        return Result.of(null, "wash", "wash");
    }

    private static Result rack(Team t, String hand) {
        if (hand == null) {
            if (t.plates <= 0) return Result.no(null, "no-plates");
            t.plates--;
            return Result.of("plate", "took", "pickup");
        }
        if ("plate".equals(hand)) { t.plates++; return Result.of(null, "placed", "place"); }
        return Result.no(hand, "rack-only-plates");
    }

    private static Result trash(Team t, String hand) {
        if (hand == null) return Result.none(null);
        if (isDish(hand)) t.dirty++;                    // the plate comes back dirty, the food is lost
        else if ("plate".equals(hand)) t.dirty++;
        return Result.of(null, "binned", "bin");
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Everything wrong with a kitchen: an item nobody can make, a dish no team can serve, stations that are
     * missing. {@code crates} are the items the crates hand out, {@code placed} the station kinds and
     * pot/pan types that exist in the layout.
     */
    public static List<String> validate(Config cfg, Set<String> crates, Set<Kind> kinds, Set<String> potTypes,
                                        Collection<String> orderDishes) {
        List<String> p = new ArrayList<>();
        for (Kind k : new Kind[]{Kind.RACK, Kind.SERVE, Kind.COUNTER, Kind.SINK})
            if (!kinds.contains(k)) p.add("geen " + k.name().toLowerCase(Locale.ROOT) + " in de layout");
        if (!kinds.contains(Kind.CRATE)) p.add("geen kratten in de layout");
        if (cfg.startPlates() < 1) p.add("start-plates moet minimaal 1 zijn");

        Set<String> can = new HashSet<>(crates);
        for (var e : cfg.chop().entrySet()) if (can.contains(e.getKey())) can.add(e.getValue());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CookRecipe r : cfg.cook()) {
                if (!potTypes.contains(r.station())) continue;
                if (can.containsAll(r.in().keySet()) && can.add(r.out())) grew = true;
            }
            for (var e : cfg.chop().entrySet()) if (can.contains(e.getKey()) && can.add(e.getValue())) grew = true;
        }
        Set<String> makeable = new HashSet<>();
        for (Assembly a : cfg.assemblies()) if (can.containsAll(a.parts().keySet())) makeable.add(a.id());
        for (CookRecipe r : cfg.cook()) if (r.onPlate() && can.contains(r.out())) makeable.add(r.out());
        for (String d : orderDishes) if (!makeable.contains(d)) p.add("gerecht '" + d + "' is niet te maken met de kratten, planken, potten en pannen in de layout");
        for (CookRecipe r : cfg.cook()) {
            if (r.ticks() < 1) p.add("kookrecept " + r.out() + ": duur moet minimaal 1 tick zijn");
            if (r.in().isEmpty()) p.add("kookrecept " + r.out() + ": geen invoer");
        }
        for (Assembly a : cfg.assemblies()) if (a.parts().isEmpty()) p.add("gerecht " + a.id() + ": geen onderdelen");
        return p;
    }
}
