package nl.kmc.tournament.timeline;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.TournamentPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Live snapshot of the tournament's past and future game schedule,
 * used to populate the /kmctimeline GUI.
 */
public final class TournamentTimeline {

    public enum GameStatus { COMPLETED, ACTIVE, UPCOMING }

    public record TimelineEntry(
            GameRegistration game,
            int              round,
            GameStatus       status,
            String           mvpName,   // null if not yet completed
            int              winnerTeamPoints
    ) {}

    private final List<TimelineEntry> entries = new ArrayList<>();
    private int    currentRound;
    private int    totalRounds;
    private TournamentPhase currentPhase;

    public TournamentTimeline(int currentRound, int totalRounds, TournamentPhase phase) {
        this.currentRound  = currentRound;
        this.totalRounds   = totalRounds;
        this.currentPhase  = phase;
    }

    public void addEntry(TimelineEntry entry) {
        entries.add(entry);
    }

    public void update(int currentRound, int totalRounds, TournamentPhase phase) {
        this.currentRound = currentRound;
        this.totalRounds  = totalRounds;
        this.currentPhase = phase;
    }

    public List<TimelineEntry> getEntries()  { return Collections.unmodifiableList(entries); }
    public int                 getCurrentRound()  { return currentRound; }
    public int                 getTotalRounds()   { return totalRounds; }
    public TournamentPhase     getCurrentPhase()  { return currentPhase; }

    public List<TimelineEntry> getCompleted() {
        return entries.stream().filter(e -> e.status() == GameStatus.COMPLETED).toList();
    }

    public List<TimelineEntry> getUpcoming() {
        return entries.stream().filter(e -> e.status() == GameStatus.UPCOMING).toList();
    }
}
