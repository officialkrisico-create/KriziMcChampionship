package nl.kmc.tournament.engine;

import nl.kmc.core.domain.TournamentPhase;

/**
 * Contract for each phase handler in the tournament flow.
 * The engine calls {@link #enter(TournamentEngine)} when a phase begins,
 * and the handler calls {@link TournamentEngine#advance()} when it is complete.
 */
public interface TournamentPhaseHandler {

    /** The phase this handler is responsible for. */
    TournamentPhase phase();

    /** Called once when entering this phase. Kick off async work here. */
    void enter(TournamentEngine engine);

    /** Called if the engine needs to force-skip this phase. */
    default void skip(TournamentEngine engine) { engine.advance(); }
}
