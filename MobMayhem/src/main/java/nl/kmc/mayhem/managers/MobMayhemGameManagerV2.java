package nl.kmc.mayhem.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.core.event.GameObjectiveEvent;
import nl.kmc.game.api.*;
import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import nl.kmc.mayhem.models.TeamGameState;
import nl.kmc.mayhem.waves.WaveLibrary;
import nl.kmc.mayhem.waves.WaveDefinition;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 Mob Mayhem manager — co-op wave survival, per-team arenas.
 *
 * <p>Each team fights waves in their own cloned world. The team that
 * survives the most waves wins. Points awarded per mob kill and per
 * completed wave. Placement determined by waves survived.
 */
public final class MobMayhemGameManagerV2 extends BaseGameManager {

    private final MobMayhemPlugin plugin;

    /** Per-team gameplay state. */
    private final Map<String, TeamGameState>  teamStates          = new LinkedHashMap<>();
    private final Map<String, WaveExecutor>   teamExecutors       = new LinkedHashMap<>();
    private final Map<String, World>          teamWorlds          = new LinkedHashMap<>();
    private final Map<String, PowerupSpawner> teamPowerupSpawners = new LinkedHashMap<>();
    private final Map<UUID, Integer>          playerMobKills      = new HashMap<>();
    private final Set<String>                 presentationStarted = new HashSet<>();
    private final Map<String, StandardStartFlow> teamStartFlows   = new LinkedHashMap<>();
    private final List<WaveDefinition>        waves;

    private static final int ARENA_WAIT_TIMEOUT_TICKS = 300; // 15s — generous margin over WorldCloner's typical 1-3s clone time

    private BossBar    bossBar;
    private BukkitTask heartbeatTask;

    public MobMayhemGameManagerV2(MobMayhemPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.waves  = WaveLibrary.loadWaves(plugin.getConfig().getConfigurationSection("waves"));
    }

    @Override
    protected void onPrepare() {
        teamStates.clear();
        teamExecutors.clear();
        teamWorlds.clear();
        teamPowerupSpawners.clear();
        playerMobKills.clear();
        presentationStarted.clear();
        teamStartFlows.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (kmcTeam == null) continue;
            String tid = kmcTeam.getId();
            teamStates.computeIfAbsent(tid, id -> new TeamGameState(id, id))
                    .addPlayer(p.getUniqueId());
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
        }

        bossBar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Mob Mayhem",
                BarColor.RED, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        // Kick off the per-team world clones now — cloning is async (1-3s typically) so
        // by the time onGameStart() runs (after the countdown + grace period) most/all
        // clones should already be ready. onGameStart() still waits defensively in case
        // a clone is slow (see awaitArenasAndStartWaves).
        List<String> teamIds = new ArrayList<>(teamStates.keySet());
        if (!teamIds.isEmpty()) {
            plugin.getWorldCloner().cloneForTeams(teamIds, result -> {
                teamWorlds.putAll(result);
                for (var e : result.entrySet()) {
                    Arena arena = plugin.getArenaManager().buildForWorld(e.getKey(), e.getValue());
                    if (arena != null) ArenaVoider.voidifyAsync(plugin, e.getValue(), arena, null);
                }
            });
        }
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§4§l[Mob Mayhem] §eSurvive the waves! The team that lasts longest wins!");
    }

    @Override
    protected void onGameStart() {
        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        WaveDefinition wave1 = waves.isEmpty() ? null : waves.get(0);
        if (wave1 == null) {
            plugin.getLogger().severe("[MobMayhem] No waves configured — ending game immediately.");
            end();
            return;
        }
        awaitArenasAndStartWaves(wave1, 0);

        // Heartbeat: check if all teams eliminated
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long alive = teamStates.values().stream()
                    .filter(ts -> !ts.isEliminated()).count();
            if (alive == 0) end();
            else updateBossBar();
        }, 20L, 20L);
    }

    /**
     * Waits for each team's cloned world to be ready before starting their
     * wave 1 — {@code cloneForTeams} is async and may not have finished by
     * the time {@code onGameStart()} fires. Polls every second up to
     * {@link #ARENA_WAIT_TIMEOUT_TICKS}; a team whose clone never arrives
     * in time is eliminated rather than leaving the game hanging forever.
     */
    private void awaitArenasAndStartWaves(WaveDefinition wave1, int elapsedTicks) {
        if (!getState().isRunning()) return;
        boolean allResolved = true;
        for (TeamGameState ts : teamStates.values()) {
            if (ts.isEliminated() || teamExecutors.containsKey(ts.getTeamId())
                    || presentationStarted.contains(ts.getTeamId())) continue;
            World world = teamWorlds.get(ts.getTeamId());
            Arena arena = world != null ? plugin.getArenaManager().buildForWorld(ts.getTeamId(), world) : null;
            if (arena == null) { allResolved = false; continue; }
            presentationStarted.add(ts.getTeamId());
            beginTeamPresentation(ts, arena, wave1);
        }

        if (allResolved) return;

        if (elapsedTicks < ARENA_WAIT_TIMEOUT_TICKS) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> awaitArenasAndStartWaves(wave1, elapsedTicks + 20), 20L);
            return;
        }

        for (TeamGameState ts : teamStates.values()) {
            if (ts.isEliminated() || teamExecutors.containsKey(ts.getTeamId())) continue;
            plugin.getLogger().severe("[MobMayhem] Team " + ts.getTeamId()
                    + " never got an arena in time — eliminating.");
            broadcast("§4[Mob Mayhem] §cTeam " + ts.getTeamId() + " kon niet starten (arena niet klaar).");
            onTeamEliminated(ts.getTeamId());
        }
    }

    /**
     * Runs the shared start-flow (teleport+freeze → intro → arena flyover →
     * tutorial tips → countdown → GO) for one team before their wave 1 —
     * giving MobMayhem the same polished start QuakeCraft/TNTTag have,
     * instead of a bare "GO!" title with no warning.
     */
    private void beginTeamPresentation(TeamGameState ts, Arena arena, WaveDefinition wave1) {
        List<Player> parts = ts.getAlivePlayers().stream()
                .map(Bukkit::getPlayer).filter(java.util.Objects::nonNull).toList();

        Location spawn = arena.getPlayerSpawn();
        warnIfEmbedded("player spawn", ts.getTeamId(), spawn);
        if (spawn != null) parts.forEach(p -> p.teleport(spawn));

        StandardStartFlow flow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning() && !ts.isEliminated(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§4§lMOB MAYHEM"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of(
                                "§4§l» §fOverleef zoveel mogelijk golven mobs samen met je team.",
                                "§4§l» §fElke golf wordt moeilijker — let op speciale modifiers!",
                                "§4§l» §fPak powerups op voor tijdelijke boosts.",
                                "§4§l» §fGa je dood? Dan kijk je de rest van de wedstrijd toe.");
                    }
                    @Override public Location flyoverCenter() { return spawn; }
                    @Override public void onFinished() {
                        for (Player p : parts) plugin.getKitManager().giveStarterKit(p);
                        startWaveForTeam(ts, arena, wave1);
                    }
                });
        teamStartFlows.put(ts.getTeamId(), flow);
        flow.prepareAndFreeze();
        flow.start();
    }

    private void startWaveForTeam(TeamGameState ts, Arena arena, WaveDefinition wave) {
        // Players are never put into the cloned arena world otherwise — without
        // this, mobs spawn in an empty world nobody is standing in, and vanilla
        // Minecraft's no-nearby-player despawn rule removes them within a tick
        // or two, making every wave look like it "clears" instantly.
        Location playerSpawn = arena.getPlayerSpawn();
        warnIfEmbedded("player spawn", ts.getTeamId(), playerSpawn);
        if (playerSpawn != null) {
            for (UUID uuid : ts.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.teleport(playerSpawn);
            }
        } else {
            plugin.getLogger().severe("[MobMayhem] Team " + ts.getTeamId()
                    + " has no player spawn set — players were NOT teleported into the arena!");
        }

        final String teamId = ts.getTeamId();
        WaveExecutor exec = new WaveExecutor(plugin, ts, arena, wave,
                survived -> {
                    if (survived) onTeamWaveComplete(teamId, wave.getWaveNumber());
                    else          onTeamEliminated(teamId);
                });
        teamExecutors.put(teamId, exec);
        exec.start();

        teamPowerupSpawners.computeIfAbsent(teamId, id -> {
            PowerupSpawner spawner = new PowerupSpawner(plugin, arena);
            spawner.start();
            return spawner;
        });
    }

    @Override
    protected void onGameEnd() {
        if (heartbeatTask != null) { heartbeatTask.cancel(); heartbeatTask = null; }
        teamExecutors.values().forEach(WaveExecutor::cancel);
        teamExecutors.clear();
        teamPowerupSpawners.values().forEach(PowerupSpawner::stop);
        teamPowerupSpawners.clear();
        teamStartFlows.values().forEach(StandardStartFlow::cancel);
        teamStartFlows.clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        // Rank by waves survived desc, tiebreak: total kills
        List<TeamGameState> ranked = new ArrayList<>(teamStates.values());
        ranked.sort((a, b) -> {
            int diff = Integer.compare(b.getHighestWaveSurvived(), a.getHighestWaveSurvived());
            return diff != 0 ? diff : Integer.compare(b.getMobsKilled(), a.getMobsKilled());
        });

        // Team placements
        for (int i = 0; i < ranked.size(); i++) {
            api.points().awardTeamPlacement(ranked.get(i).getTeamId(), i + 1, registration.getId());
        }

        // Build per-player finish order based on their team's rank
        Map<String, Integer> teamRankMap = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) teamRankMap.put(ranked.get(i).getTeamId(), i + 1);

        List<UUID> allPlayers = new ArrayList<>();
        UUID mvpUuid = null; String mvpName = null; int topKills = 0;
        int playerRank = 1;

        for (TeamGameState ts : ranked) {
            for (UUID uuid : ts.getAllPlayers()) {
                allPlayers.add(uuid);
                Player p = Bukkit.getPlayer(uuid);
                String name = p != null ? p.getName() : uuid.toString();
                api.points().awardPlayerPlacement(uuid, playerRank, allPlayers.size(), registration.getId());
                api.games().recordGameParticipation(uuid, name, registration.getId(),
                        teamRankMap.getOrDefault(ts.getTeamId(), 99) == 1);
            }
            playerRank += ts.getAllPlayers().size();
        }

        String winnerDesc = ranked.isEmpty() ? "No winner"
                : (ranked.get(0).getHighestWaveSurvived() + " waves — team " + ranked.get(0).getTeamId());

        returnToLobby();
        teamStates.clear();
        teamWorlds.clear();
        playerMobKills.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, allPlayers);
    }

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState s = new PlayerGameState();
        s.inventory = player.getInventory().getContents().clone();
        s.armor     = player.getInventory().getArmorContents().clone();
        s.health    = player.getHealth();
        s.maxHealth = 20;
        s.location  = player.getLocation();
        s.effects   = new ArrayList<>(player.getActivePotionEffects());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§4[MobMayhem] State restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        var kt = plugin.getKmcCore().getTeamManager().getTeamByPlayer(id);
        TeamGameState mine = kt != null ? teamStates.get(kt.getId()) : null;
        if (mine != null) {
            l.add(api.tr(id, "sb.mob.your-team"));
            l.add(api.tr(id, "sb.mob.wave", mine.getCurrentWave()));
            l.add(api.tr(id, "sb.mob.mobs-left", mine.getActiveMobCount()));
            l.add(api.tr(id, "sb.mob.mob-kills", mine.getMobsKilled()));
            l.add("");
        }
        l.add(api.tr(id, "sb.mob.teams"));
        teamStates.values().stream()
                .sorted((a, b) -> Math.max(b.getCurrentWave(), b.getHighestWaveSurvived())
                        - Math.max(a.getCurrentWave(), a.getHighestWaveSurvived()))
                .limit(5)
                .forEach(ts -> l.add(api.tr(id, "sb.mob.team-entry", ts.getTeamId(),
                        Math.max(ts.getCurrentWave(), ts.getHighestWaveSurvived()))));
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Mob Mayhem"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Mob Mayhem arena not ready: " + plugin.getArenaManager().getReadinessReport());
                if (!plugin.getWorldCloner().templateExists())
                    r.addError("Template world '" + plugin.getWorldCloner().getTemplateWorldName() + "' not found.");
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void onMobKill(UUID killer, int mobPoints, boolean wasBoss) {
        if (!getState().isRunning()) return;
        api.points().givePoints(killer, mobPoints, PointAward.Reason.KILL, registration.getId());
        // Credit the kill to the team state too
        for (TeamGameState ts : teamStates.values()) {
            if (ts.getAlivePlayers().contains(killer)) {
                ts.recordKill(mobPoints);
                break;
            }
        }
        try { statsService.recordKill(killer); } catch (Throwable ignored) {}

        Player p = Bukkit.getPlayer(killer);
        int kills = playerMobKills.merge(killer, 1, Integer::sum);
        if (p != null) {
            if (wasBoss) fireObjective(p, GameObjectiveEvent.Type.BOSS_KILLED);
            if (kills == 50)  fireObjective(p, GameObjectiveEvent.Type.KILL_MILESTONE);
            if (kills == 100) fireObjective(p, GameObjectiveEvent.Type.KILL_MILESTONE_LEGENDARY);
        }
    }

    public Map<String, TeamGameState> getTeamStates() { return Collections.unmodifiableMap(teamStates); }

    // ── Callbacks from WaveExecutor ───────────────────────────────────────────

    private void onTeamWaveComplete(String teamId, int waveNumber) {
        if (!getState().isRunning()) return;
        TeamGameState ts = teamStates.get(teamId);
        if (ts == null) return;

        int wavePts = plugin.getConfig().getInt("points.per-wave", 100);
        for (UUID uuid : ts.getAlivePlayers()) {
            api.points().givePoints(uuid, wavePts, PointAward.Reason.OBJECTIVE, registration.getId());
        }
        broadcast("§4[Mob Mayhem] §eTeam " + teamId + " §acleared wave " + waveNumber + "! §8(+" + wavePts + " pts each)");

        for (UUID uuid : ts.getAlivePlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            fireObjective(p, GameObjectiveEvent.Type.WAVE_SURVIVED);
            if (waveNumber == 5) fireObjective(p, GameObjectiveEvent.Type.NO_DEATH_MILESTONE);
        }

        int maxWaves = plugin.getConfig().getInt("game.max-waves", waves.size());
        WaveDefinition next = getWaveByNumber(waveNumber + 1);
        if (waveNumber >= maxWaves || next == null) {
            broadcast("§4§l[Mob Mayhem] §eTeam " + teamId + " §6cleared ALL waves! Incredible!");
            long otherAlive = teamStates.values().stream()
                    .filter(t -> !t.getTeamId().equals(teamId) && !t.isEliminated()).count();
            if (otherAlive == 0) end();
            updateBossBar();
            return;
        }

        World world = teamWorlds.get(teamId);
        Arena arena = world != null ? plugin.getArenaManager().buildForWorld(teamId, world) : null;
        if (arena == null) {
            plugin.getLogger().warning("[MobMayhem] Lost arena for team " + teamId
                    + " when starting wave " + next.getWaveNumber() + ".");
            updateBossBar();
            return;
        }

        int intermissionSeconds = plugin.getConfig().getInt("game.intermission-seconds", 5);
        teamExecutors.remove(teamId);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!getState().isRunning() || ts.isEliminated()) return;
            startWaveForTeam(ts, arena, next);
        }, intermissionSeconds * 20L);

        updateBossBar();
    }

    private void onTeamEliminated(String teamId) {
        if (!getState().isRunning()) return;
        broadcast("§4☠ §7Team " + teamId + " §7has been eliminated!");
        PowerupSpawner spawner = teamPowerupSpawners.remove(teamId);
        if (spawner != null) spawner.stop();
        long alive = teamStates.values().stream().filter(ts -> !ts.isEliminated()).count();
        if (alive == 0) end();
        updateBossBar();
    }

    /**
     * Warns loudly (console + in-chat to the team) if {@code loc} is inside a
     * solid block — a mob or player teleported here will spawn embedded in
     * terrain/suffocate, which looks exactly like "spawning in the ground".
     * Since the cloned arena is a byte-for-byte copy of the template world,
     * this means the recorded coordinate is bad in the TEMPLATE too — the
     * fix is re-running {@code /mm setspawn}/{@code /mm addmobspawn} while
     * standing in genuinely open air there, not a bug in the cloning itself.
     */
    private void warnIfEmbedded(String what, String teamId, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        var block = loc.getBlock();
        if (!block.getType().isSolid() && !block.getRelative(0, 1, 0).getType().isSolid()) return;
        String msg = "[MobMayhem] Team " + teamId + "'s " + what + " at (" + loc.getBlockX() + ", "
                + loc.getBlockY() + ", " + loc.getBlockZ() + ") in world " + loc.getWorld().getName()
                + " is INSIDE solid terrain (" + block.getType() + ") — re-run /mm setspawn or "
                + "/mm addmobspawn there while standing in open air in the TEMPLATE world.";
        plugin.getLogger().severe(msg);
        broadcast("§c⚠ " + msg);
    }

    private void fireObjective(Player p, GameObjectiveEvent.Type type) {
        try { new GameObjectiveEvent(p, registration.getId(), type).callEvent(); }
        catch (Throwable t) { plugin.getLogger().warning("GameObjectiveEvent failed: " + t); }
    }

    /** Checks whether exactly one player remains alive across all teams and, if so, grants Last Stand. */
    public void checkLastStanding() {
        List<UUID> allAlive = new ArrayList<>();
        for (TeamGameState ts : teamStates.values()) allAlive.addAll(ts.getAlivePlayers());
        if (allAlive.size() != 1) return;
        Player p = Bukkit.getPlayer(allAlive.get(0));
        if (p != null) fireObjective(p, GameObjectiveEvent.Type.LAST_STANDING);
    }

    public WaveDefinition getWaveByNumber(int number) {
        return waves.stream().filter(w -> w.getWaveNumber() == number).findFirst().orElse(null);
    }

    public List<WaveDefinition> getWaves() { return Collections.unmodifiableList(waves); }

    public PowerupSpawner getPowerupSpawner(String teamId) { return teamPowerupSpawners.get(teamId); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void updateBossBar() {
        if (bossBar == null) return;
        StringBuilder sb = new StringBuilder(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Mob Mayhem §8| ");
        teamStates.values().forEach(ts ->
                sb.append("§e").append(ts.getTeamId())
                  .append(" §7w").append(ts.getHighestWaveSurvived())
                  .append(ts.isEliminated() ? " §c✗" : " §a✓").append(" §8| "));
        bossBar.setTitle(sb.toString());
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        teamStates.values().forEach(ts -> ts.getAllPlayers().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        }));
        plugin.getWorldCloner().disposeAll();
    }
}
