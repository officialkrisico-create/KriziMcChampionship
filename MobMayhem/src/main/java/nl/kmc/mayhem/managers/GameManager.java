package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import nl.kmc.mayhem.models.TeamGameState;
import nl.kmc.mayhem.waves.WaveDefinition;
import nl.kmc.mayhem.waves.WaveLibrary;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Mob Mayhem game orchestrator.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #startGame()} — clone template once per team, async</li>
 *   <li>When all clones loaded → countdown</li>
 *   <li>For each team: spawn players, wooden kit, start wave 1</li>
 *   <li>Each team has their own WaveExecutor that runs independently</li>
 *   <li>When a team's wave completes: drop loot → intermission → next wave</li>
 *   <li>Game ends when all teams eliminated OR wave 10 cleared by anyone</li>
 *   <li>Winner = team with highest wave survived (tiebreak: most kills)</li>
 *   <li>Cleanup: dispose all cloned worlds, TP players to lobby</li>
 * </ol>
 */
public class GameManager {

    public enum State { IDLE, PREPARING, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "mob_mayhem";

    private final MobMayhemPlugin plugin;
    private State state = State.IDLE;

    /** Team state by team id. */
    private final Map<String, TeamGameState> teamStates = new LinkedHashMap<>();

    /** Per-team arena (built from cloned world). */
    private final Map<String, Arena> teamArenas = new LinkedHashMap<>();

    /** Per-team current wave executor. */
    private final Map<String, WaveExecutor> teamExecutors = new LinkedHashMap<>();

    /** Wave list (configurable, defaults to 10-wave library). */
    private List<WaveDefinition> waves;

    private BossBar    bossBar;
    private BukkitTask countdownTask;
    private int        countdownSeconds;

    public GameManager(MobMayhemPlugin plugin) {
        this.plugin = plugin;
        this.waves = WaveLibrary.defaultWaves();
    }

    // ----------------------------------------------------------------

    public State                       getState()      { return state; }
    public boolean                     isActive()      { return state == State.ACTIVE; }
    public Map<String, TeamGameState>  getTeamStates() { return Collections.unmodifiableMap(teamStates); }

    public TeamGameState getTeamStateForPlayer(UUID uuid) {
        for (TeamGameState ts : teamStates.values()) {
            if (ts.getAllPlayers().contains(uuid)) return ts;
        }
        return null;
    }

    // ----------------------------------------------------------------

    public String startGame() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getArenaManager().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getReadinessReport();
        if (!plugin.getWorldCloner().templateExists())
            return "Template world '" + plugin.getWorldCloner().getTemplateWorldName()
                    + "' bestaat niet!";

        // Pick teams that have at least 1 online member
        List<KMCTeam> playingTeams = new ArrayList<>();
        for (KMCTeam team : plugin.getKmcCore().getTeamManager().getAllTeams()) {
            boolean hasOnline = false;
            for (UUID uuid : team.getMembers()) {
                if (Bukkit.getPlayer(uuid) != null) { hasOnline = true; break; }
            }
            if (hasOnline) playingTeams.add(team);
        }
        if (playingTeams.size() < 2) return "Minimaal 2 teams met online spelers nodig.";

        state = State.PREPARING;
        broadcast("&6[Mob Mayhem] &eVoorbereiden... &7(klonen van " + playingTeams.size() + " arena's)");

        // Acquire scoreboard lock
        plugin.getKmcCore().getApi().acquireScoreboard("mobmayhem");

        // Clone arenas in parallel
        List<String> teamIds = playingTeams.stream().map(KMCTeam::getId).toList();
        plugin.getWorldCloner().cloneForTeams(teamIds, clonedWorlds -> {
            if (clonedWorlds.size() < playingTeams.size()) {
                broadcast("&c[Mob Mayhem] Niet alle werelden konden worden gekloond. Game afgebroken.");
                cleanup(null);
                return;
            }
            // Build per-team arena from each clone
            for (KMCTeam team : playingTeams) {
                World w = clonedWorlds.get(team.getId());
                if (w == null) continue;
                Arena arena = plugin.getArenaManager().buildForWorld(team.getId(), w);
                if (arena == null) continue;
                teamArenas.put(team.getId(), arena);

                TeamGameState ts = new TeamGameState(team.getId(), team.getId());
                for (UUID uuid : team.getMembers()) {
                    if (Bukkit.getPlayer(uuid) != null) ts.addPlayer(uuid);
                }
                teamStates.put(team.getId(), ts);
            }
            beginCountdown();
        });

        return null;
    }

    private void beginCountdown() {
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        // Teleport players to their team's arena spawn
        for (var entry : teamStates.entrySet()) {
            Arena arena = teamArenas.get(entry.getKey());
            if (arena == null) continue;
            for (UUID uuid : entry.getValue().getAllPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                p.teleport(arena.getPlayerSpawn());
                p.setGameMode(GameMode.ADVENTURE);
                p.setHealth(20);
                p.setFoodLevel(20);
                for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
                p.getInventory().clear();
            }
        }

        // BossBar
        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Mob Mayhem start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Mob Mayhem] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "Mob Mayhem start over " + countdownSeconds + "s");

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                bossBar.setColor(BarColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }

            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                launch();
            }
        }, 20L, 20L);
    }

    private void launch() {
        state = State.ACTIVE;
        bossBar.setColor(BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "Mob Mayhem — Wave 1");

        // Give wooden kit to all participants
        for (TeamGameState ts : teamStates.values()) {
            for (UUID uuid : ts.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                plugin.getKitManager().giveStarterKit(p);
                p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                        ChatColor.YELLOW + "Wave 1 begint!", 0, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
            }
        }

        broadcast("&a&l[Mob Mayhem] &eGO! &7Overleef alle 10 waves!");

        // Start wave 1 for each team
        for (String teamId : teamStates.keySet()) {
            startNextWave(teamId);
        }
    }

    /**
     * Starts the next wave for one team. Called at game launch (wave 1)
     * and after each wave-end intermission.
     */
    private void startNextWave(String teamId) {
        TeamGameState ts = teamStates.get(teamId);
        if (ts == null || ts.isEliminated()) return;
        if (state != State.ACTIVE) return;

        int nextWaveNumber = ts.getCurrentWave() + 1;
        if (nextWaveNumber > waves.size()) {
            // Team cleared all waves — instant win condition
            broadcast("&6[Mob Mayhem] " + teamColor(teamId) + nameOfTeam(teamId)
                    + " &7heeft &6alle waves &7overleefd!");
            endGame("all_waves_cleared", teamId);
            return;
        }

        WaveDefinition def = waves.get(nextWaveNumber - 1);
        Arena arena = teamArenas.get(teamId);
        if (arena == null) {
            plugin.getLogger().warning("No arena for team " + teamId + "!");
            return;
        }

        WaveExecutor exec = new WaveExecutor(plugin, ts, arena, def, survived -> {
            // Wave complete callback (on main thread)
            handleWaveComplete(teamId, def, survived);
        });
        teamExecutors.put(teamId, exec);
        exec.start();
    }

    private void handleWaveComplete(String teamId, WaveDefinition wave, boolean survived) {
        TeamGameState ts = teamStates.get(teamId);
        if (ts == null) return;

        teamExecutors.remove(teamId);

        if (!survived || ts.isEliminated()) {
            // Team is out
            broadcast("&c[Mob Mayhem] " + teamColor(teamId) + nameOfTeam(teamId)
                    + " &7is geëlimineerd op wave &c" + wave.getWaveNumber() + "&7!");
            checkAllTeamsOut();
            return;
        }

        // Wave bonus
        int bonus = wave.isBossWave()
                ? plugin.getConfig().getInt("points.wave-survived-bonus-boss", 100)
                : plugin.getConfig().getInt("points.wave-survived-bonus", 25);
        ts.recordKill(bonus); // counts as "points earned"
        for (UUID uuid : ts.getAlivePlayers()) {
            plugin.getKmcCore().getApi().givePoints(uuid, bonus);
        }

        broadcast("&a[Mob Mayhem] " + teamColor(teamId) + nameOfTeam(teamId)
                + " &aheeft wave &6" + wave.getWaveNumber() + " &aoverleefd! &7(+"
                + bonus + " pts)");

        // Drop wave-end loot at the team's player spawn
        Arena arena = teamArenas.get(teamId);
        if (arena != null) {
            List<ItemStack> drops = plugin.getKitManager().getWaveDropLoot(wave.getWaveNumber());
            for (ItemStack drop : drops) {
                arena.getPlayerSpawn().getWorld().dropItem(arena.getPlayerSpawn(), drop);
            }
            // Heal alive players
            for (UUID uuid : ts.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.setHealth(20);
                    p.setFoodLevel(20);
                }
            }
        }

        // Intermission then next wave
        int intermission = plugin.getConfig().getInt("game.intermission-seconds", 15);
        for (UUID uuid : ts.getAlivePlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.sendTitle(ChatColor.GREEN + "Wave Overleefd!",
                    ChatColor.YELLOW + "Volgende wave over " + intermission + "s",
                    10, 50, 20);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> startNextWave(teamId), intermission * 20L);
    }

    private void checkAllTeamsOut() {
        boolean anyAlive = teamStates.values().stream().anyMatch(ts -> !ts.isEliminated());
        if (!anyAlive) {
            endGame("all_eliminated", null);
        }
    }

    // ----------------------------------------------------------------
    // Player death
    // ----------------------------------------------------------------

    /** Called by listener when a participant dies. */
    public void handlePlayerDeath(Player p) {
        TeamGameState ts = getTeamStateForPlayer(p.getUniqueId());
        if (ts == null) return;
        ts.eliminatePlayer(p.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        broadcast("&c☠ &7" + p.getName() + " &8is uitgeschakeld op wave " + ts.getCurrentWave());

        // Living-while-someone-dies bonus — every still-alive player on
        // ANY team gets a small reward for outlasting this death.
        int livingBonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (livingBonus > 0) {
            UUID deadId = p.getUniqueId();
            List<UUID> stillAlive = new ArrayList<>();
            for (TeamGameState other : teamStates.values()) {
                for (UUID uuid : other.getAlivePlayers()) {
                    if (!uuid.equals(deadId)) stillAlive.add(uuid);
                }
            }
            nl.kmc.kmccore.util.SurvivorBonusHelper.award(
                    plugin.getKmcCore(), stillAlive, livingBonus);
        }

        if (ts.getAlivePlayers().isEmpty()) {
            // Team out — kill their wave executor
            WaveExecutor exec = teamExecutors.remove(ts.getTeamId());
            if (exec != null) exec.cancel();
        }
    }

    /** Called by listener when a tagged mob dies. Routes the kill. */
    public void handleMobKill(String teamId, int waveNumber, org.bukkit.entity.EntityType type, Player killer) {
        TeamGameState ts = teamStates.get(teamId);
        if (ts == null) return;
        if (ts.getCurrentWave() != waveNumber) return; // stale wave's mob

        // Score based on type
        boolean wasBoss = waves.size() >= waveNumber && waves.get(waveNumber - 1).isBossWave();
        int points = WaveLibrary.defaultPointsForKill(type, wasBoss);
        ts.recordKill(points);

        // Award to the killer if they're on this team
        if (killer != null && ts.getAllPlayers().contains(killer.getUniqueId())) {
            plugin.getKmcCore().getApi().givePoints(killer.getUniqueId(), points);
            plugin.getKmcCore().getHallOfFameManager().recordKill(killer);
        }
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason, String earlyWinnerId) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        // Cancel all team executors
        for (WaveExecutor exec : teamExecutors.values()) exec.cancel();
        teamExecutors.clear();

        // Rank teams: highest wave survived desc, then mob kills desc
        List<TeamGameState> ranked = new ArrayList<>(teamStates.values());
        ranked.sort((a, b) -> {
            if (a.getHighestWaveSurvived() != b.getHighestWaveSurvived())
                return Integer.compare(b.getHighestWaveSurvived(), a.getHighestWaveSurvived());
            return Integer.compare(b.getMobsKilled(), a.getMobsKilled());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lMob Mayhem — Uitslag");
        broadcast("&7Reden: " + (reason.equals("all_waves_cleared")
                ? "&aTeam clearde alle waves!" : "&7Alle teams uitgeschakeld"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"team-first-place", "team-second-place", "team-third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < ranked.size(); i++) {
            TeamGameState ts = ranked.get(i);
            KMCTeam team = plugin.getKmcCore().getTeamManager().getTeam(ts.getTeamId());
            if (team == null) continue;

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + team.getColor() + team.getDisplayName()
                    + " &8- &eWave " + ts.getHighestWaveSurvived()
                    + " &8(" + ts.getMobsKilled() + " kills)");

            int placeBonus;
            if (i < placeKeys.length)
                placeBonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                placeBonus = plugin.getConfig().getInt("points.team-participation", 25);

            if (placeBonus > 0) {
                for (UUID memberId : team.getMembers()) {
                    api.givePoints(memberId, placeBonus);
                }
            }

            // Record per-player tournament stats — every member of the
            // winning team gets a win, others reset their streak.
            for (UUID memberId : team.getMembers()) {
                Player member = Bukkit.getPlayer(memberId);
                String memberName = member != null ? member.getName() : memberId.toString();
                api.recordGameParticipation(memberId, memberName, GAME_ID, i == 0);
            }

            if (i == 0) winnerName = team.getColor() + team.getDisplayName();
        }
        broadcast("&6═══════════════════════════════════");

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint Mob Mayhem!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("mobmayhem");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }

        // TP everyone to the lobby
        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (TeamGameState ts : teamStates.values()) {
            for (UUID uuid : ts.getAllPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
                p.setHealth(20);
                p.setFoodLevel(20);
                if (lobby != null) p.teleport(lobby);
            }
        }

        // Dispose all cloned worlds (this auto-kicks any stragglers)
        plugin.getWorldCloner().disposeAll();

        teamStates.clear();
        teamArenas.clear();
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state == State.IDLE) return;
        endGame("force_stop", null);
    }

    // ----------------------------------------------------------------

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    private String nameOfTeam(String teamId) {
        var t = plugin.getKmcCore().getTeamManager().getTeam(teamId);
        return t != null ? t.getDisplayName() : teamId;
    }

    private String teamColor(String teamId) {
        var t = plugin.getKmcCore().getTeamManager().getTeam(teamId);
        return t != null ? t.getColor().toString() : "&f";
    }
}
