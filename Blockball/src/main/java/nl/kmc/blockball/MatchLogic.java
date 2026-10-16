package nl.kmc.blockball;

import java.util.*;

/** Match rules without a server: touches and assists, the score, heats and standings. */
public final class MatchLogic {

    private MatchLogic() { }

    // ── Touches and assists ───────────────────────────────────────────────────

    public record Touch(String player, int side, long tick, boolean pass) {}

    /** Who scored and who gets the assist; either can be null (own goal, ambiguous or no assist). */
    public record Credit(String scorer, String assist, boolean ownGoal) {}

    /**
     * Remembers the last touches of the ball. An assist is the previous touch by a different player of the
     * scoring side, within the window, with no touch by the other side in between.
     */
    public static final class TouchTracker {
        private final Deque<Touch> touches = new ArrayDeque<>();

        public void touch(String player, int side, long tick, boolean pass) {
            touches.addLast(new Touch(player, side, tick, pass));
            while (touches.size() > 6) touches.removeFirst();
        }

        public Touch last() { return touches.peekLast(); }

        public void clear() { touches.clear(); }

        /** @param goalSide the side whose team gets the goal (the one that is attacking) */
        public Credit credit(int goalSide, long tick, long windowTicks) {
            Touch last = touches.peekLast();
            if (last == null) return new Credit(null, null, false);
            if (last.side() != goalSide) return new Credit(null, null, true);   // the defender put it in his own goal
            String assist = null;
            Iterator<Touch> it = touches.descendingIterator();
            it.next();                                                          // the scorer's own touch
            while (it.hasNext()) {
                Touch t = it.next();
                if (t.side() != goalSide) break;                                // an opponent touched it: no assist
                if (tick - t.tick() > windowTicks) break;
                if (!t.player().equals(last.player())) { assist = t.player(); break; }
            }
            return new Credit(last.player(), assist, false);
        }
    }

    // ── The score of one match ────────────────────────────────────────────────

    public static final class MatchState {
        public final int[] goals = new int[2];
        private boolean overtime;
        private boolean finished;
        private int winner = -1;            // 0, 1 or -1 (draw / not decided)
        private final int maxGoals;
        private final boolean overtimeEnabled;

        public MatchState(int maxGoals, boolean overtimeEnabled) {
            this.maxGoals = maxGoals;
            this.overtimeEnabled = overtimeEnabled;
        }

        public boolean overtime() { return overtime; }
        public boolean finished() { return finished; }
        public int winner() { return winner; }
        public boolean draw() { return finished && winner < 0; }

        /** @return false when the match is already over (a late goal does not count). */
        public boolean goal(int side) {
            if (finished) return false;
            goals[side]++;
            if (overtime || (maxGoals > 0 && goals[side] >= maxGoals)) {   // golden goal / first to N
                finished = true;
                winner = side;
            }
            return true;
        }

        /**
         * Regular time is up. A draw goes to overtime when enabled (once), otherwise the match ends level.
         * @return true when the match is over now
         */
        public boolean timeUp() {
            if (finished) return true;
            if (goals[0] != goals[1]) {
                finished = true;
                winner = goals[0] > goals[1] ? 0 : 1;
                return true;
            }
            if (overtimeEnabled && !overtime) {
                overtime = true;
                return false;
            }
            finished = true;                       // draw (overtime ran out too, or is switched off)
            return true;
        }
    }

    // ── Heats ─────────────────────────────────────────────────────────────────

    /** Two teams on one field; {@code b} is null for a bye. */
    public record Heat(String a, String b) {
        public boolean bye() { return b == null; }
    }

    /**
     * Pairs the teams in the given order and spreads the pairs over waves of at most {@code fields} matches
     * that are played at the same time. An odd team gets a bye in the last heat.
     */
    public static List<List<Heat>> plan(List<String> teams, int fields) {
        int f = Math.max(1, fields);
        List<Heat> heats = new ArrayList<>();
        for (int i = 0; i < teams.size(); i += 2)
            heats.add(new Heat(teams.get(i), i + 1 < teams.size() ? teams.get(i + 1) : null));
        List<List<Heat>> waves = new ArrayList<>();
        for (int i = 0; i < heats.size(); i += f) waves.add(new ArrayList<>(heats.subList(i, Math.min(heats.size(), i + f))));
        return waves;
    }

    // ── Standings ─────────────────────────────────────────────────────────────

    public static final class Standing {
        public final String team;
        public int points, goalsFor, goalsAgainst, played, wins;
        Standing(String team) { this.team = team; }
        public int diff() { return goalsFor - goalsAgainst; }
    }

    public static final class Table {
        private final Map<String, Standing> rows = new LinkedHashMap<>();

        public Standing row(String team) { return rows.computeIfAbsent(team, Standing::new); }

        public void record(String team, int matchPoints, int goalsFor, int goalsAgainst, boolean won) {
            Standing s = row(team);
            s.points += matchPoints;
            s.goalsFor += goalsFor;
            s.goalsAgainst += goalsAgainst;
            s.played++;
            if (won) s.wins++;
        }

        /** Match points, then goal difference, then goals scored, then team id: never chance. */
        public List<String> ranking() {
            List<Standing> l = new ArrayList<>(rows.values());
            l.sort((a, b) -> {
                int c = Integer.compare(b.points, a.points);
                if (c != 0) return c;
                c = Integer.compare(b.diff(), a.diff());
                if (c != 0) return c;
                c = Integer.compare(b.goalsFor, a.goalsFor);
                return c != 0 ? c : a.team.compareTo(b.team);
            });
            List<String> out = new ArrayList<>();
            for (Standing s : l) out.add(s.team);
            return out;
        }
    }
}
