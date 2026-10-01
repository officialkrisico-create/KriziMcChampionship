package nl.kmc.kmccore.simulation;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.snapshot.SnapshotManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.*;

/**
 * Dry-run simulator — runs a tournament with fake bot players instead of
 * real ones, but through the EXACT same pipeline a live tournament uses:
 * {@link nl.kmc.kmccore.managers.TournamentManager#start()}, the real
 * {@link nl.kmc.kmccore.managers.GameManager} rotation (respects enabled
 * games and never repeats one until every game has had a turn, same as
 * {@code /kmcauto}), real {@code awardPlayerPlacement}/{@code awardKill}
 * calls (so the round multiplier and points.yml curve apply exactly as in
 * a real game), and the real {@code endTournament()} — so you get a real
 * post-event book, a real Fan Favorite vote, and a real tournament-history
 * entry, exactly as if 13+ people had actually played it out.
 *
 * <p><b>This means it has real, lasting effects</b> — unlike the old
 * version of this tool, nothing is snapshotted-and-restored afterward.
 * A full run (reaches the last configured round) ends through the real
 * {@code endTournament()}, which itself resets points/team scores as its
 * normal last step — same as any real tournament. A short run (you asked
 * for fewer rounds than {@code tournament.total-rounds}) ends through the
 * real {@code stop()} instead (no book, scores NOT reset), exactly like an
 * admin running {@code /kmctournament stop} early for real.
 *
 * <p>Refuses to run if a real tournament is already active, so it can
 * never hijack a live event.
 */
public class SimulationEngine {

    private static final String SIM_PREFIX = "[SIM] ";

    private final KMCCore plugin;
    private boolean running = false;

    public SimulationEngine(KMCCore plugin) { this.plugin = plugin; }

    public boolean isRunning() { return running; }

    /**
     * Runs the simulation. Schedules round-by-round on the main thread via
     * the Bukkit scheduler so we don't fight thread safety, and so each
     * round's broadcasts are readable instead of dumped all at once.
     *
     * @param requestedRounds how many rounds to simulate; clamped to
     *                        {@code tournament.total-rounds} — running fewer
     *                        than that stops the tournament early (like
     *                        {@code /kmctournament stop}) instead of ending it.
     */
    public void run(CommandSender sender, int requestedRounds, int playerN) {
        if (running) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX + "Simulatie draait al.");
            return;
        }
        if (requestedRounds < 1 || requestedRounds > 20) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX + "Rounds moet tussen 1 en 20 zijn.");
            return;
        }
        if (playerN < 4 || playerN > 256) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX + "Players moet tussen 4 en 256 zijn.");
            return;
        }
        if (plugin.getTournamentManager().isActive()) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX
                    + "Er draait al een ECHT toernooi — simulatie zou dat kapen. Stop of beëindig dat eerst.");
            return;
        }

        Collection<KMCTeam> teams = plugin.getTeamManager().getAllTeams();
        if (teams.size() < 2) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX
                    + "Minstens 2 teams nodig in config.yml om te simuleren. Gevonden: "
                    + teams.size());
            return;
        }
        if (plugin.getGameManager().getEnabledGames().isEmpty()) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX
                    + "Geen enkele game staat aan in de rotatie (games.list.*.enabled) — niks om te simuleren.");
            return;
        }

        running = true;

        int totalRounds = plugin.getConfig().getInt("tournament.total-rounds", 5);
        int roundsToRun = Math.min(requestedRounds, totalRounds);
        boolean fullRun = roundsToRun >= totalRounds;

        send(sender, "&6═══════════════════════════════════════");
        send(sender, "&6&l    DRY-RUN SIMULATION STARTING");
        send(sender, "&6═══════════════════════════════════════");
        send(sender, "&7Simuleert &e" + roundsToRun + "&7/&e" + totalRounds
                + " &7rondes  &7Players: &e" + playerN + "  &7Teams: &e" + teams.size() + " &7(bestaande teams)");
        if (!fullRun) {
            send(sender, "&eLet op: &7minder rondes dan het echte toernooi (" + totalRounds
                    + ") — dit eindigt als een vroegtijdige /kmctournament stop: GEEN boek, punten blijven staan.");
        } else {
            send(sender, "&eDit draait het ECHTE endTournament()&7: boek, Fan Favorite-stemming, geschiedenis, puntenreset.");
        }

        // Safety checkpoint an admin can manually roll back to with /event rollback
        // (not auto-restored — that would undo the very realism you asked for).
        SnapshotManager sm = plugin.getSnapshotManager();
        var preSnap = sm.snapshot("sim-pre-" + System.currentTimeMillis());
        send(sender, "&7(Veiligheids-snapshot &f" + preSnap.label + "&7 gemaakt — handmatig terug te draaien met /event rollback.)");

        // Fake bots, attached to your real teams.
        SimState st = generateFakeState(playerN, new ArrayList<>(teams));
        registerFakePlayersInDB(st);
        attachFakePlayersToTeams(st);

        // Real tournament start — increments the real KMC event number.
        plugin.getTournamentManager().start();

        int delayPerRound = 100; // 5 seconds (in ticks), for readable logs
        for (int round = 1; round <= roundsToRun; round++) {
            final int finalRound = round;
            final boolean isLastSimulatedRound = (round == roundsToRun);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> simulateRound(sender, st, finalRound, roundsToRun, isLastSimulatedRound),
                    (long) round * delayPerRound);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (fullRun) {
                send(sender, "&7Laatste ronde bereikt — echte endTournament() wordt aangeroepen...");
                plugin.getTournamentManager().endTournament();
            } else {
                send(sender, "&7Simulatie gestopt vóór het einde — echte stop() wordt aangeroepen (geen boek).");
                plugin.getTournamentManager().stop();
            }
            cleanupFakePlayers(st);
            send(sender, "&aSimulatie klaar.");
            running = false;
        }, (long) (roundsToRun + 1) * delayPerRound);
    }

    // ----------------------------------------------------------------
    // Round simulation
    // ----------------------------------------------------------------

    private void simulateRound(CommandSender sender, SimState st, int round, int roundsToRun, boolean isLastSimulatedRound) {
        Random rng = new Random();

        KMCGame game = plugin.getGameManager().randomNextGame();
        if (game == null) {
            send(sender, "&c[SIM] Geen beschikbare game gevonden voor ronde " + round + " — overgeslagen.");
            return;
        }
        String gameId = game.getId();
        plugin.getGameManager().markGamePlayedForSimulation(gameId);

        // Same reveal point as a real /kmcauto game launch — see AutomationManager.launchGame().
        plugin.getTournamentManager().revealGoldenHourIfDue();

        double mul = plugin.getTournamentManager().getMultiplier();
        send(sender, "");
        send(sender, "&6── Round " + plugin.getTournamentManager().getCurrentRound() + "/"
                + plugin.getTournamentManager().getTotalRounds() + " — &e" + game.getDisplayName()
                + " &6(×" + mul + " multiplier) ──");

        List<UUID> playerOrder = new ArrayList<>(st.fakePlayers);
        Collections.shuffle(playerOrder, rng);

        for (int i = 0; i < playerOrder.size(); i++) {
            UUID uuid = playerOrder.get(i);
            int placement = i + 1;

            // Real scoring path — same points.yml placement curve + round
            // multiplier a real game's awardPlayerPlacement() call applies.
            plugin.getPointsManager().awardPlayerPlacement(uuid, placement);

            int kills = rng.nextInt(4); // 0-3 simulated kills
            for (int k = 0; k < kills; k++) {
                plugin.getPointsManager().awardKill(uuid);
            }

            PlayerData pd = plugin.getPlayerDataManager().get(uuid);
            if (pd != null) {
                if (placement <= 3) pd.addWin(gameId);
                else                pd.resetStreak();
            }
        }

        // Advance the REAL tournament round — exactly what /kmcauto does between
        // games, except we don't advance past the last round we're simulating
        // (that's left to endTournament()/stop() in the wrap-up).
        if (!isLastSimulatedRound) {
            plugin.getTournamentManager().nextRound();
        }

        printRoundStandings(sender, round);
    }

    private void printRoundStandings(CommandSender sender, int round) {
        send(sender, "&7Top 3 teams na round " + round + ":");
        List<KMCTeam> teams = new ArrayList<>(plugin.getTeamManager().getAllTeams());
        teams.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        for (int i = 0; i < Math.min(3, teams.size()); i++) {
            KMCTeam t = teams.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : "&c🥉";
            send(sender, "  " + medal + " &f" + t.getDisplayName()
                    + " &7- &e" + t.getPoints() + " pts");
        }
    }

    // ----------------------------------------------------------------
    // Fake state generation
    // ----------------------------------------------------------------

    private SimState generateFakeState(int playerN, List<KMCTeam> teams) {
        SimState st = new SimState();

        // Distribute playerN across teams as evenly as possible
        for (int p = 0; p < playerN; p++) {
            UUID uuid = UUID.nameUUIDFromBytes(("sim_player_" + p + "_" + System.nanoTime()).getBytes());
            String name = "SimBot" + (p + 1);
            st.fakePlayers.add(uuid);
            st.fakePlayerNames.put(uuid, name);

            // Round-robin assignment to existing teams
            KMCTeam team = teams.get(p % teams.size());
            st.fakePlayerTeams.put(uuid, team.getId());
        }
        return st;
    }

    private void registerFakePlayersInDB(SimState st) {
        for (UUID uuid : st.fakePlayers) {
            String name = st.fakePlayerNames.get(uuid);
            PlayerData pd = plugin.getPlayerDataManager().getOrCreate(uuid, name);
            // Reset their state to ensure clean sim numbers
            pd.setPoints(0);
            pd.setKills(0);
            pd.setWins(0);
            pd.setGamesPlayed(0);
            pd.setWinStreak(0);
            // Bind to assigned team
            String teamId = st.fakePlayerTeams.get(uuid);
            try { pd.getClass().getMethod("setTeamId", String.class).invoke(pd, teamId); }
            catch (Throwable ignored) { /* older API — try addPlayerToTeam path */ }
        }
    }

    private void attachFakePlayersToTeams(SimState st) {
        for (Map.Entry<UUID, String> e : st.fakePlayerTeams.entrySet()) {
            try {
                plugin.getTeamManager().addPlayerToTeam(e.getKey(), e.getValue());
            } catch (Throwable t) {
                plugin.getLogger().warning("Sim: failed to attach " + e.getKey()
                        + " to team " + e.getValue() + " — " + t.getMessage());
            }
        }
    }

    private void cleanupFakePlayers(SimState st) {
        for (UUID uuid : st.fakePlayers) {
            try { plugin.getTeamManager().removePlayerFromTeam(uuid); }
            catch (Throwable ignored) {}
            try { plugin.getPlayerDataManager().unload(uuid); }
            catch (Throwable ignored) {}
        }
    }

    private void send(CommandSender s, String msg) {
        String coloured = ChatColor.translateAlternateColorCodes('&', msg);
        s.sendMessage(coloured);
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender)) {
            Bukkit.getConsoleSender().sendMessage(coloured);
        }
    }

    // ----------------------------------------------------------------
    // Inner state
    // ----------------------------------------------------------------

    private static class SimState {
        final List<UUID>        fakePlayers     = new ArrayList<>();
        final Map<UUID, String> fakePlayerNames = new HashMap<>();
        final Map<UUID, String> fakePlayerTeams = new HashMap<>();
    }
}
