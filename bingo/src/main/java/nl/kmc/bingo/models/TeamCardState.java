package nl.kmc.bingo.models;

import nl.kmc.bingo.objectives.BingoObjective;

import java.util.*;

/**
 * Per-team progress on the bingo card.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Per-square completion (boolean per index)</li>
 *   <li>Per-square progress (current count toward target)</li>
 *   <li>Completed lines (bitmask of which lines are filled)</li>
 *   <li>Time of completion for each square (for tiebreakers)</li>
 * </ul>
 */
public class TeamCardState {

    private final String   teamId;
    private final BingoCard card;

    private final boolean[] completed     = new boolean[BingoCard.TOTAL];
    private final int[]     progress      = new int[BingoCard.TOTAL];
    private final long[]    completedAt   = new long[BingoCard.TOTAL];

    /** Bitmask: bit N set = line N is fully complete. */
    private int linesCompleted = 0;

    private boolean fullCardCompleted = false;
    private long    fullCardCompletedAt = 0;

    public TeamCardState(String teamId, BingoCard card) {
        this.teamId = teamId;
        this.card   = card;
    }

    /**
     * Adds progress to a square. Returns true iff this completed it.
     *
     * @param index  square index (0..24)
     * @param amount progress to add (typically 1, can be more for batched events)
     * @return newly-completed status (true only on the transition)
     */
    public boolean addProgress(int index, int amount) {
        if (completed[index]) return false;

        BingoObjective obj = card.get(index);
        progress[index] = Math.min(obj.getTargetAmount(), progress[index] + amount);

        if (progress[index] >= obj.getTargetAmount()) {
            completed[index]   = true;
            completedAt[index] = System.currentTimeMillis();
            checkLines();
            checkFullCard();
            return true;
        }
        return false;
    }

    /** Force-mark a square complete (used for "collect" objectives where we recount). */
    public boolean setProgress(int index, int amount) {
        if (completed[index]) return false;
        BingoObjective obj = card.get(index);
        progress[index] = Math.min(obj.getTargetAmount(), Math.max(0, amount));
        if (progress[index] >= obj.getTargetAmount()) {
            completed[index]   = true;
            completedAt[index] = System.currentTimeMillis();
            checkLines();
            checkFullCard();
            return true;
        }
        return false;
    }

    private void checkLines() {
        for (int line = 0; line < BingoCard.LINE_COUNT; line++) {
            if ((linesCompleted & (1 << line)) != 0) continue; // already done
            int[] indices = BingoCard.lineIndices(line);
            boolean allDone = true;
            for (int idx : indices) {
                if (!completed[idx]) { allDone = false; break; }
            }
            if (allDone) linesCompleted |= (1 << line);
        }
    }

    private void checkFullCard() {
        if (fullCardCompleted) return;
        for (boolean done : completed) if (!done) return;
        fullCardCompleted   = true;
        fullCardCompletedAt = System.currentTimeMillis();
    }

    // ---- Queries --------------------------------------------------

    public boolean isCompleted(int index)  { return completed[index]; }
    public int     getProgress(int index)  { return progress[index]; }
    public long    getCompletedAt(int idx) { return completedAt[idx]; }

    public int getCompletedSquareCount() {
        int n = 0;
        for (boolean c : completed) if (c) n++;
        return n;
    }

    public int getCompletedLineCount() { return Integer.bitCount(linesCompleted); }

    public List<Integer> getCompletedLines() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < BingoCard.LINE_COUNT; i++) {
            if ((linesCompleted & (1 << i)) != 0) out.add(i);
        }
        return out;
    }

    public boolean isLineCompleted(int line) {
        return (linesCompleted & (1 << line)) != 0;
    }

    public boolean isFullCardCompleted()      { return fullCardCompleted; }
    public long    getFullCardCompletedAt()   { return fullCardCompletedAt; }

    public String   getTeamId() { return teamId; }
    public BingoCard getCard()  { return card; }
}
