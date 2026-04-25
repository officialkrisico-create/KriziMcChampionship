package nl.kmc.bingo.models;

import nl.kmc.bingo.objectives.BingoObjective;

import java.util.*;

/**
 * The 5×5 bingo card — same for every team.
 *
 * <p>Squares are stored row-major: index = row*5 + col.
 *
 * <p>Lines are: 5 rows + 5 columns + 2 diagonals = 12 total.
 */
public class BingoCard {

    public static final int SIZE       = 5;
    public static final int TOTAL      = SIZE * SIZE;
    public static final int LINE_COUNT = SIZE + SIZE + 2; // rows + cols + diagonals

    private final BingoObjective[] objectives = new BingoObjective[TOTAL];

    public BingoCard(List<BingoObjective> objectives) {
        if (objectives.size() != TOTAL) {
            throw new IllegalArgumentException("Need exactly " + TOTAL + " objectives, got " + objectives.size());
        }
        for (int i = 0; i < TOTAL; i++) this.objectives[i] = objectives.get(i);
    }

    public BingoObjective get(int row, int col) { return objectives[row * SIZE + col]; }
    public BingoObjective get(int index)        { return objectives[index]; }

    public BingoObjective[] getObjectives() {
        return objectives.clone();
    }

    /** Returns indices of squares belonging to the given line. */
    public static int[] lineIndices(int line) {
        int[] out = new int[SIZE];
        if (line < SIZE) {
            // Row
            for (int c = 0; c < SIZE; c++) out[c] = line * SIZE + c;
        } else if (line < 2 * SIZE) {
            // Column
            int col = line - SIZE;
            for (int r = 0; r < SIZE; r++) out[r] = r * SIZE + col;
        } else if (line == 2 * SIZE) {
            // Top-left → bottom-right diagonal
            for (int i = 0; i < SIZE; i++) out[i] = i * SIZE + i;
        } else {
            // Top-right → bottom-left diagonal
            for (int i = 0; i < SIZE; i++) out[i] = i * SIZE + (SIZE - 1 - i);
        }
        return out;
    }

    public static String lineDescription(int line) {
        if (line < SIZE)            return "Rij " + (line + 1);
        if (line < 2 * SIZE)        return "Kolom " + (line - SIZE + 1);
        if (line == 2 * SIZE)       return "Diagonaal ↘";
        return "Diagonaal ↙";
    }
}
