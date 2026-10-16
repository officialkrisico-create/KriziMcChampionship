package nl.kmc.factory;

import java.util.*;

/**
 * The factory without a server: item and recipe definitions, validation of the production graph, the machine
 * state machine, resource stations and order progress. All quantities are exact integers: an input is
 * consumed once, an output is created once.
 */
public final class FactoryModel {

    private FactoryModel() { }

    // ── Definitions ───────────────────────────────────────────────────────────

    /** In, out and duration of one production step. */
    public record Recipe(String id, Map<String, Integer> in, Map<String, Integer> out, int ticks) {}

    public record MachineType(String id, int queueSize, int outputCap, List<Recipe> recipes) {
        public Set<String> inputItems() {
            Set<String> s = new LinkedHashSet<>();
            for (Recipe r : recipes) s.addAll(r.in().keySet());
            return s;
        }
    }

    public enum Difficulty { SIMPLE, MEDIUM, COMPLEX }

    public record OrderDef(String id, String name, Difficulty difficulty, Map<String, Integer> items, int reward,
                           int deadlineSeconds, int bonusPercent) {
        public int totalItems() { int n = 0; for (int v : items.values()) n += v; return n; }
    }

    // ── Validation of the production graph ────────────────────────────────────

    /**
     * Everything wrong with a factory definition. {@code raw} are the items the resource stations hand out,
     * {@code placedTypes} the machine types that exist in the layout. An item is producible when it is raw or
     * some recipe of a placed machine can make it from producible items (the closure is computed, so a cycle
     * that never starts from raw materials does not count).
     */
    public static List<String> validate(Set<String> knownItems, Set<String> raw, Map<String, MachineType> types,
                                        Set<String> placedTypes, List<OrderDef> orders) {
        List<String> p = new ArrayList<>();
        for (MachineType t : types.values()) {
            if (t.recipes().isEmpty()) p.add("machine-type " + t.id() + ": geen recepten");
            if (t.queueSize() < 1) p.add("machine-type " + t.id() + ": queue moet minimaal 1 zijn");
            for (Recipe r : t.recipes()) {
                if (r.in().isEmpty() || r.out().isEmpty()) p.add("recept " + r.id() + ": input en output mogen niet leeg zijn");
                if (r.ticks() < 1) p.add("recept " + r.id() + ": duur moet minimaal 1 tick zijn");
                for (String i : r.in().keySet()) if (!knownItems.contains(i)) p.add("recept " + r.id() + ": onbekend item " + i);
                for (String o : r.out().keySet()) if (!knownItems.contains(o)) p.add("recept " + r.id() + ": onbekend item " + o);
                for (int v : r.in().values()) if (v < 1) p.add("recept " + r.id() + ": hoeveelheden moeten minimaal 1 zijn");
                for (int v : r.out().values()) if (v < 1) p.add("recept " + r.id() + ": hoeveelheden moeten minimaal 1 zijn");
                // a recipe must not make more value than it eats: no item from nothing
                int in = 0, out = 0;
                for (int v : r.in().values()) in += v;
                for (int v : r.out().values()) out += v;
                if (out > in * 4) p.add("recept " + r.id() + ": maakt veel meer dan het verbruikt (" + out + " uit " + in + ")");
            }
        }
        Set<String> can = producible(raw, types, placedTypes);
        for (OrderDef o : orders) {
            if (o.items().isEmpty()) p.add("order " + o.id() + ": geen items");
            if (o.reward() < 0) p.add("order " + o.id() + ": negatieve beloning");
            for (var e : o.items().entrySet()) {
                if (!knownItems.contains(e.getKey())) p.add("order " + o.id() + ": onbekend item " + e.getKey());
                else if (!can.contains(e.getKey())) p.add("order " + o.id() + ": '" + e.getKey() + "' is niet te maken met de machines en grondstoffen");
                if (e.getValue() < 1) p.add("order " + o.id() + ": hoeveelheid van " + e.getKey() + " moet minimaal 1 zijn");
            }
        }
        return p;
    }

    /** The items that can come into existence: raw, plus whatever the placed machines can make from them. */
    public static Set<String> producible(Set<String> raw, Map<String, MachineType> types, Set<String> placedTypes) {
        Set<String> can = new HashSet<>(raw);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String tid : placedTypes) {
                MachineType t = types.get(tid);
                if (t == null) continue;
                for (Recipe r : t.recipes()) {
                    if (can.containsAll(r.in().keySet())) {
                        for (String o : r.out().keySet()) if (can.add(o)) grew = true;
                    }
                }
            }
        }
        return can;
    }

    // ── Machine state machine ─────────────────────────────────────────────────

    public enum MachineState { IDLE, WAITING_FOR_INPUT, PROCESSING, READY, BLOCKED, DISABLED }

    private static final class Job {
        final Recipe recipe;
        int ticksLeft;
        Job(Recipe r) { recipe = r; ticksLeft = r.ticks(); }
    }

    /**
     * One machine. Players put items into its input buffer; whenever a recipe is satisfied and there is room
     * in the queue the inputs are consumed and a job starts. A finished job moves its output to the output
     * buffer (or waits while the buffer is full: BLOCKED). Time only passes through {@link #tick}, so a pause
     * of the game pauses the machines.
     */
    public static final class Machine {
        public final String id;
        public final MachineType type;
        private final Map<String, Integer> input = new LinkedHashMap<>();
        private final Map<String, Integer> output = new LinkedHashMap<>();
        private final Deque<Job> jobs = new ArrayDeque<>();
        private boolean disabled;
        private int inputCap = 24;

        public Machine(String id, MachineType type) { this.id = id; this.type = type; }

        public void setDisabled(boolean d) { disabled = d; }
        public void setInputCap(int cap) { inputCap = Math.max(1, cap); }
        public Map<String, Integer> input() { return Collections.unmodifiableMap(input); }
        public Map<String, Integer> output() { return Collections.unmodifiableMap(output); }
        public int queued() { return jobs.size(); }
        public Recipe currentRecipe() { return jobs.isEmpty() ? null : jobs.peekFirst().recipe; }
        public int ticksLeft() { return jobs.isEmpty() ? 0 : jobs.peekFirst().ticksLeft; }

        public int outputCount() { int n = 0; for (int v : output.values()) n += v; return n; }

        /** Items the machine accepts as input at all. */
        public boolean accepts(String item) { return type.inputItems().contains(item); }

        /** @return how many of {@code n} were accepted (only inputs of its recipes, up to the buffer limit). */
        public int insert(String item, int n) {
            if (disabled || n <= 0 || !accepts(item)) return 0;
            int have = input.getOrDefault(item, 0);
            int take = Math.max(0, Math.min(n, inputCap - have));
            if (take > 0) input.merge(item, take, Integer::sum);
            return take;
        }

        /** Hands the unprocessed inputs back; what a running job already consumed is gone. */
        public Map<String, Integer> returnInputs() {
            Map<String, Integer> r = new LinkedHashMap<>(input);
            input.clear();
            return r;
        }

        /** Puts back output that could not be handed over (the inventory was full): it is not lost. */
        public void restoreOutput(String item, int n) {
            if (n > 0) output.merge(item, n, Integer::sum);
        }

        /** Takes everything from the output buffer (each output exists exactly once). */
        public Map<String, Integer> collect() {
            Map<String, Integer> r = new LinkedHashMap<>(output);
            output.clear();
            return r;
        }

        public MachineState state() {
            if (disabled) return MachineState.DISABLED;
            Job j = jobs.peekFirst();
            if (j != null) return j.ticksLeft <= 0 ? MachineState.BLOCKED : MachineState.PROCESSING;
            if (!output.isEmpty()) return MachineState.READY;
            if (!input.isEmpty()) return MachineState.WAITING_FOR_INPUT;
            return MachineState.IDLE;
        }

        /** Advances time by {@code ticks} (speed 1.0 = normal). */
        public void tick(int ticks, double speed) {
            if (disabled) return;
            int budget = Math.max(1, (int) Math.round(ticks * Math.max(0.1, speed)));
            startJobs();
            while (budget > 0 && !jobs.isEmpty()) {
                Job j = jobs.peekFirst();
                if (j.ticksLeft > 0) {
                    int step = Math.min(budget, j.ticksLeft);
                    j.ticksLeft -= step;
                    budget -= step;
                }
                if (j.ticksLeft > 0) break;
                // finished: the output moves over when there is room, otherwise the machine is blocked
                int add = 0;
                for (int v : j.recipe.out().values()) add += v;
                if (outputCount() + add > type.outputCap()) return;
                for (var e : j.recipe.out().entrySet()) output.merge(e.getKey(), e.getValue(), Integer::sum);
                jobs.pollFirst();
                startJobs();
            }
        }

        private void startJobs() {
            boolean again = true;
            while (again && jobs.size() < type.queueSize()) {
                again = false;
                for (Recipe r : type.recipes()) {
                    if (!satisfied(r)) continue;
                    for (var e : r.in().entrySet()) {
                        int left = input.get(e.getKey()) - e.getValue();
                        if (left <= 0) input.remove(e.getKey()); else input.put(e.getKey(), left);
                    }
                    jobs.addLast(new Job(r));
                    again = true;
                    break;
                }
            }
        }

        private boolean satisfied(Recipe r) {
            for (var e : r.in().entrySet()) if (input.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
            return true;
        }
    }

    // ── Resource station ──────────────────────────────────────────────────────

    public static final class Station {
        public final String id;
        public final String item;
        private final int max;
        private int stock;

        public Station(String id, String item, int max) {
            this.id = id; this.item = item; this.max = Math.max(1, max); this.stock = this.max;
        }

        public int stock() { return stock; }
        public int max() { return max; }

        public int take(int want) {
            int got = Math.max(0, Math.min(want, stock));
            stock -= got;
            return got;
        }

        public void restock(int n) { stock = Math.min(max, stock + Math.max(0, n)); }

        /** A refused hand-over goes back, never above the maximum. */
        public void giveBack(int n) { restock(n); }
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    /** An order in progress: what was delivered so far and by whom. */
    public static final class Order {
        public final OrderDef def;
        public final long issuedTick;
        private final Map<String, Integer> delivered = new LinkedHashMap<>();
        private final Map<UUID, Integer> contributors = new LinkedHashMap<>();
        private boolean done;

        public Order(OrderDef def, long issuedTick) { this.def = def; this.issuedTick = issuedTick; }

        public int remaining(String item) {
            return Math.max(0, def.items().getOrDefault(item, 0) - delivered.getOrDefault(item, 0));
        }

        public int delivered(String item) { return delivered.getOrDefault(item, 0); }
        public boolean done() { return done; }
        public Map<UUID, Integer> contributors() { return Collections.unmodifiableMap(contributors); }

        public boolean needs(String item) { return remaining(item) > 0; }

        /**
         * Delivers up to {@code n} of {@code item}; only what the order still needs is taken.
         * @return the amount accepted
         */
        public int deliver(String item, int n, UUID by) {
            if (done || n <= 0) return 0;
            int take = Math.min(n, remaining(item));
            if (take <= 0) return 0;
            delivered.merge(item, take, Integer::sum);
            if (by != null) contributors.merge(by, take, Integer::sum);
            boolean all = true;
            for (String i : def.items().keySet()) if (remaining(i) > 0) { all = false; break; }
            done = all;
            return take;
        }
    }

    /** Whether any open order still needs the item. */
    public static boolean anyNeeds(Collection<Order> open, String item) {
        for (Order o : open) if (!o.done() && o.needs(item)) return true;
        return false;
    }

    /**
     * The next order for a team: the difficulty is gated by how many orders it has completed, and an order
     * that is already open is not issued twice.
     */
    public static OrderDef nextOrder(List<OrderDef> pool, int completed, int mediumAfter, int complexAfter,
                                     Set<String> openIds, Random rnd) {
        List<OrderDef> ok = new ArrayList<>();
        for (OrderDef o : pool) {
            if (openIds.contains(o.id())) continue;
            if (o.difficulty() == Difficulty.MEDIUM && completed < mediumAfter) continue;
            if (o.difficulty() == Difficulty.COMPLEX && completed < complexAfter) continue;
            ok.add(o);
        }
        if (ok.isEmpty()) {                       // everything open or gated: never leave the board empty
            for (OrderDef o : pool) if (!openIds.contains(o.id())) ok.add(o);
        }
        if (ok.isEmpty()) ok.addAll(pool);
        if (ok.isEmpty()) return null;
        // prefer the hardest tier that is unlocked, so the game gets more demanding
        Difficulty top = Difficulty.SIMPLE;
        for (OrderDef o : ok) if (o.difficulty().ordinal() > top.ordinal()) top = o.difficulty();
        List<OrderDef> pick = new ArrayList<>();
        for (OrderDef o : ok) if (o.difficulty().ordinal() >= Math.max(0, top.ordinal() - 1)) pick.add(o);
        return pick.get(rnd.nextInt(pick.size()));
    }
}
