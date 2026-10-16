package nl.kmc.dungeon;

import java.util.*;

/**
 * The rules of Dungeon Draft that do not need a server: the room pool and its validation, drafting the next
 * route options, resolving a team vote, the shared health pool with revives, and the sequence puzzle.
 */
public final class DungeonLogic {

    private DungeonLogic() { }

    public enum RoomType { COMBAT, PARKOUR, TRAP, TREASURE, PUZZLE, BOSS }

    public enum Modifier { NONE, DOUBLE_REWARD, REDUCED_TIME, EXTRA_WAVE, HEAL }

    /** A room as configured. {@code at} and {@code entry} are relative offsets; {@code params} is type specific. */
    public record RoomDef(String id, String name, String description, RoomType type, int difficulty, int reward,
                          int timeLimitSeconds, String risk, int[] at, int[] entry, Map<String, Object> params) {}

    /** What the team is shown: a room, possibly with a modifier. */
    public record Option(RoomDef room, Modifier modifier) {}

    // ── Validation ────────────────────────────────────────────────────────────

    /** Everything wrong with the room pool for a run of {@code totalRooms} rooms. */
    public static List<String> validate(List<RoomDef> pool, int totalRooms, int choices, boolean bossLast) {
        List<String> p = new ArrayList<>();
        if (pool.isEmpty()) { p.add("geen kamers in rooms"); return p; }
        Set<String> ids = new HashSet<>();
        int bosses = 0, normal = 0;
        for (RoomDef r : pool) {
            if (!ids.add(r.id())) p.add("kamer-id '" + r.id() + "' komt twee keer voor");
            if (r.at() == null) p.add("kamer " + r.id() + ": 'at' ontbreekt");
            if (r.entry() == null) p.add("kamer " + r.id() + ": 'entry' ontbreekt");
            if (r.difficulty() < 1 || r.difficulty() > 3) p.add("kamer " + r.id() + ": difficulty moet 1, 2 of 3 zijn");
            if (r.type() == RoomType.BOSS) bosses++; else normal++;
        }
        if (bossLast && bosses == 0) p.add("de laatste kamer is een boss, maar er is geen BOSS-kamer");
        int needed = bossLast ? Math.max(0, totalRooms - 1) : totalRooms;
        if (normal < needed) p.add("te weinig kamers voor een run van " + totalRooms + ": " + normal + " gewone beschikbaar, "
                + needed + " nodig (een kamer wordt maar één keer gebruikt)");
        if (choices < 1) p.add("route-choices moet minimaal 1 zijn");
        return p;
    }

    // ── Drafting ──────────────────────────────────────────────────────────────

    /** The hardest difficulty a room may have at this point of the run (1..3). */
    public static int maxDifficulty(int roomNumber, int totalRooms) {
        if (totalRooms <= 1) return 3;
        int step = (Math.max(1, roomNumber) - 1) * 3 / Math.max(1, totalRooms);
        return Math.max(1, Math.min(3, 1 + step));
    }

    /**
     * The options for room number {@code roomNumber} (1-based): unused rooms only, a boss only as the last
     * room (when {@code bossLast}), not harder than the curve allows while easier ones are available, and as
     * many different room types as possible. Never returns an empty list while an unused room exists.
     */
    public static List<Option> draft(List<RoomDef> pool, Set<String> used, int roomNumber, int totalRooms, int choices,
                                     boolean bossLast, double modifierChance, Random rnd) {
        boolean last = roomNumber >= totalRooms;
        List<RoomDef> eligible = new ArrayList<>();
        for (RoomDef r : pool) {
            if (used.contains(r.id())) continue;
            boolean boss = r.type() == RoomType.BOSS;
            if (bossLast && last && !boss) continue;
            if (bossLast && !last && boss) continue;
            eligible.add(r);
        }
        if (eligible.isEmpty()) {                // the boss rule cannot be met: fall back to any unused room
            for (RoomDef r : pool) if (!used.contains(r.id())) eligible.add(r);
        }
        if (eligible.isEmpty()) return List.of();

        int max = maxDifficulty(roomNumber, totalRooms);
        List<RoomDef> fit = new ArrayList<>(), harder = new ArrayList<>();
        for (RoomDef r : eligible) (r.difficulty() <= max ? fit : harder).add(r);
        Collections.shuffle(fit, rnd);
        harder.sort(Comparator.comparingInt(RoomDef::difficulty));
        List<RoomDef> ordered = new ArrayList<>(fit);
        ordered.addAll(harder);

        int n = Math.max(1, Math.min(choices, ordered.size()));
        List<RoomDef> picked = new ArrayList<>();
        Set<RoomType> types = new HashSet<>();
        for (RoomDef r : ordered) {                       // first pass: different types
            if (picked.size() >= n) break;
            if (types.add(r.type())) picked.add(r);
        }
        for (RoomDef r : ordered) {                       // second pass: fill up
            if (picked.size() >= n) break;
            if (!picked.contains(r)) picked.add(r);
        }
        List<Option> out = new ArrayList<>();
        for (RoomDef r : picked) {
            Modifier m = Modifier.NONE;
            if (r.type() != RoomType.BOSS && rnd.nextDouble() < modifierChance) {
                Modifier[] pool2 = {Modifier.DOUBLE_REWARD, Modifier.REDUCED_TIME, Modifier.HEAL,
                        r.type() == RoomType.COMBAT ? Modifier.EXTRA_WAVE : Modifier.DOUBLE_REWARD};
                m = pool2[rnd.nextInt(pool2.length)];
            }
            out.add(new Option(r, m));
        }
        return out;
    }

    /** What happens when nobody decided: RANDOM, EASIEST or HARDEST. */
    public static Option fallback(List<Option> options, String rule, Random rnd) {
        if (options.isEmpty()) return null;
        if ("EASIEST".equalsIgnoreCase(rule))
            return options.stream().min(Comparator.comparingInt(o -> o.room().difficulty())).orElse(options.get(0));
        if ("HARDEST".equalsIgnoreCase(rule))
            return options.stream().max(Comparator.comparingInt(o -> o.room().difficulty())).orElse(options.get(0));
        return options.get(rnd.nextInt(options.size()));
    }

    /**
     * The option the team chose: the leader's vote in leader mode, otherwise the one with the most votes
     * (a tie is decided by chance among the tied ones). -1 when nobody voted.
     */
    public static int resolveVote(Map<UUID, Integer> votes, boolean leaderMode, UUID leader, int optionCount, Random rnd) {
        if (optionCount <= 0 || votes.isEmpty()) return -1;
        if (leaderMode) {
            Integer v = votes.get(leader);
            return v != null && v >= 0 && v < optionCount ? v : -1;
        }
        int[] count = new int[optionCount];
        for (int v : votes.values()) if (v >= 0 && v < optionCount) count[v]++;
        int best = 0;
        for (int c : count) best = Math.max(best, c);
        if (best == 0) return -1;
        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < count.length; i++) if (count[i] == best) top.add(i);
        return top.get(rnd.nextInt(top.size()));
    }

    // ── Rewards ───────────────────────────────────────────────────────────────

    /** Room reward after the modifier. A reduced-time room pays the same (it is harder), a double one pays 2x. */
    public static int roomReward(int base, Modifier m) {
        return m == Modifier.DOUBLE_REWARD ? base * 2 : base;
    }

    /** Percentage of the time limit that is left when the room was completed (0 when there is no limit). */
    public static double timeLeftFraction(long limitTicks, long usedTicks) {
        if (limitTicks <= 0) return 0;
        return Math.max(0, Math.min(1, 1.0 - usedTicks / (double) limitTicks));
    }

    // ── Shared health ─────────────────────────────────────────────────────────

    public enum HealthResult { OK, REVIVED, FAILED }

    public static final class Health {
        private final double max;
        private double cur;
        private int revives;
        private boolean failed;

        public Health(double max, int revives) {
            this.max = Math.max(1, max);
            this.cur = this.max;
            this.revives = Math.max(0, revives);
        }

        public double current() { return cur; }
        public double max() { return max; }
        public int revives() { return revives; }
        public boolean failed() { return failed; }
        public double fraction() { return cur / max; }

        public HealthResult damage(double amount) {
            if (failed) return HealthResult.FAILED;
            cur -= Math.max(0, amount);
            if (cur > 0) return HealthResult.OK;
            if (revives > 0) {
                revives--;
                cur = max * 0.5;
                return HealthResult.REVIVED;
            }
            cur = 0;
            failed = true;
            return HealthResult.FAILED;
        }

        public void heal(double amount) {
            if (!failed) cur = Math.min(max, cur + Math.max(0, amount));
        }

        public void addRevive() { revives++; }
    }

    // ── Sequence puzzle ───────────────────────────────────────────────────────

    public enum Press { OK, WRONG, DONE }

    /** Pads that have to be pressed in a shown order; a mistake starts the sequence over. */
    public static final class SequencePuzzle {
        private final int[] sequence;
        private int progress;
        private boolean done;

        public SequencePuzzle(int pads, int length, Random rnd) {
            int p = Math.max(2, pads);
            sequence = new int[Math.max(2, length)];
            int prev = -1;
            for (int i = 0; i < sequence.length; i++) {
                int v;
                do { v = rnd.nextInt(p); } while (v == prev && p > 1);
                sequence[i] = v;
                prev = v;
            }
        }

        public int[] sequence() { return sequence.clone(); }
        public int progress() { return progress; }
        public boolean done() { return done; }

        public Press press(int pad) {
            if (done) return Press.DONE;
            if (sequence[progress] == pad) {
                progress++;
                if (progress >= sequence.length) { done = true; return Press.DONE; }
                return Press.OK;
            }
            progress = 0;
            return Press.WRONG;
        }
    }
}
