package nl.kmc.kmccore.simulation;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.snapshot.SnapshotManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.*;

/**
 * Dry-run simulator — runs a fake tournament without real players or
 * minigames. Generates fake game-end events and feeds them through the
 * standard point-award pipeline so admins can verify:
 *
 * <ul>
 *   <li>Scoring math (placement, kills, multipliers)</li>
 *   <li>Round transitions (multiplier escalation across rounds)</li>
 *   <li>Finals/winner detection</li>
 *   <li>End-of-tournament cleanup</li>
 * </ul>
 *
 * <p><b>Important:</b> the simulator uses your EXISTING configured teams
 * (it can't create new ones — TeamManager doesn't expose that API).
 * Fake players are temporarily assigned to your real teams. The
 * pre-simulation snapshot restores the real state when the sim ends, so
 * your real player/team rosters are not affected.
 *
 * <p><b>Caveat:</b> if no teams are configured, the simulator can't run
 * properly. You need at least 2 configured teams.
 */
public class SimulationEngine {

    private static final String SIM_PREFIX = "[SIM] ";

    /** Game IDs to randomly pick from each round. */
    private static final List<String> GAME_IDS = List.of(
            "adventure_escape", "skywars", "survival_games", "quakecraft",
            "parkour_warrior", "tgttos", "the_bridge", "elytra_endrium",
            "spleef", "meltdown_mayhem", "mob_mayhem", "lucky_block", "bingo"
    );

    private final KMCCore plugin;
    private boolean running = false;

    public SimulationEngine(KMCCore plugin) { this.plugin = plugin; }

    public boolean isRunning() { return running; }

    /**
     * Runs a full simulation. Schedules round-by-round on the main
     * thread via Bukkit scheduler so we don't fight thread safety.
     */
    public void run(CommandSender sender, int rounds, int playerN) {
        if (running) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX + "Simulatie draait al.");
            return;
        }
        if (rounds < 1 || rounds > 20) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX + "Rounds moet tussen 1 en 20 zijn.");
            return;
        }
        if (playerN < 4 || playerN > 256) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX + "Players moet tussen 4 en 256 zijn.");
            return;
        }

        // Need at least 2 existing teams
        Collection<KMCTeam> teams = plugin.getTeamManager().getAllTeams();
        if (teams.size() < 2) {
            sender.sendMessage(ChatColor.RED + SIM_PREFIX
                    + "Minstens 2 teams nodig in config.yml om te simuleren. Gevonden: "
                    + teams.size());
            return;
        }

        running = true;
        send(sender, "&6═══════════════════════════════════════");
        send(sender, "&6&l    DRY-RUN SIMULATION STARTING");
        send(sender, "&6═══════════════════════════════════════");
        send(sender, "&7Rounds: &e" + rounds + "  &7Players: &e" + playerN
                + "  &7Teams: &e" + teams.size() + " &7(uses existing)");

        // 1. Snapshot real state so we can restore after sim
        SnapshotManager sm = plugin.getSnapshotManager();
        var snap = sm.snapshot("sim-pre-" + System.currentTimeMillis());

        // 2. Build fake state — distribute fake players across real teams
        SimState st = generateFakeState(playerN, new ArrayList<>(teams));
        registerFakePlayersInDB(st);
        attachFakePlayersToTeams(st);

        // 3. Schedule rounds with 5s spacing for log readability
        int delayPerRound = 100;  // 5 seconds (in ticks)
        for (int round = 1; round <= rounds; round++) {
            final int finalRound = round;
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> simulateRound(sender, st, finalRound, rounds),
                    (long) round * delayPerRound);
        }

        // 4. Wrap up + restore
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            printFinalStandings(sender, st);
            send(sender, "&7Restoring pre-simulation state...");
            sm.restore(snap.label);
            cleanupFakePlayers(st);
            send(sender, "&aSimulation complete. Real state restored.");
            running = false;
        }, (long) (rounds + 1) * delayPerRound);
    }

    // ----------------------------------------------------------------
    // Round simulation
    // ----------------------------------------------------------------

    private void simulateRound(CommandSender sender, SimState st, int round, int totalRounds) {
        Random rng = new Random(System.nanoTime() + round);

        String gameId = GAME_IDS.get(rng.nextInt(GAME_IDS.size()));
        double mul = plugin.getPointsManager().getMultiplierForRound(round);

        send(sender, "");
        send(sender, "&6── Round " + round + "/" + totalRounds + " — &e" + gameId
                + " &6(×" + mul + " multiplier) ──");

        List<UUID> playerOrder = new ArrayList<>(st.fakePlayers);
        Collections.shuffle(playerOrder, rng);

        int basePerSlot      = 100;
        int decreasePerPlace = 10;

        for (int i = 0; i < playerOrder.size(); i++) {
            UUID uuid = playerOrder.get(i);
            int placement  = i + 1;
            int basePoints = Math.max(10, basePerSlot - (i * decreasePerPlace));

            // Apply multiplier — same path as real games via KMCApi.givePoints
            plugin.getApi().givePoints(uuid, basePoints);

            // Random 0-3 kills
            int kills = rng.nextInt(4);
            for (int k = 0; k < kills; k++) {
                plugin.getPointsManager().awardKill(uuid);
            }

            PlayerData pd = plugin.getPlayerDataManager().get(uuid);
            if (pd != null) {
                if (placement <= 3) pd.addWin(gameId);
                else                pd.resetStreak();
            }
        }

        // Advance the tournament round
        try { plugin.getTournamentManager().setRound(round); } catch (Exception ignored) {}

        // Snapshot at round-start
        plugin.getSnapshotManager().snapshot("sim-round-" + round);

        printRoundStandings(sender, st, round);
    }

    private void printRoundStandings(CommandSender sender, SimState st, int round) {
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

    private void printFinalStandings(CommandSender sender, SimState st) {
        send(sender, "");
        send(sender, "&6═══════════════════════════════════════");
        send(sender, "&6&l   FINAL STANDINGS (Simulated)");
        send(sender, "&6═══════════════════════════════════════");

        List<KMCTeam> teams = new ArrayList<>(plugin.getTeamManager().getAllTeams());
        teams.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        send(sender, "&eTeams:");
        for (int i = 0; i < teams.size(); i++) {
            KMCTeam t = teams.get(i);
            send(sender, "  &7#" + (i + 1) + " &f" + t.getDisplayName()
                    + " &7- &e" + t.getPoints() + " pts");
        }

        // Top 5 fake players
        List<PlayerData> players = new ArrayList<>();
        for (UUID uuid : st.fakePlayers) {
            PlayerData pd = plugin.getPlayerDataManager().get(uuid);
            if (pd != null) players.add(pd);
        }
        players.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        send(sender, "&eTop 5 spelers:");
        for (int i = 0; i < Math.min(5, players.size()); i++) {
            PlayerData pd = players.get(i);
            send(sender, "  &7#" + (i + 1) + " &f" + pd.getName()
                    + " &7- &e" + pd.getPoints() + " pts &7("
                    + pd.getKills() + " kills, " + pd.getWins() + " wins)");
        }
    }

    // ----------------------------------------------------------------
    // Fake state generation
    // ----------------------------------------------------------------

    private SimState generateFakeState(int playerN, List<KMCTeam> teams) {
        SimState st = new SimState();
        Random rng = new Random();

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
