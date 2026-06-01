package nl.kmc.mayhem.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.mayhem.MobMayhemPlugin;
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
    private final Map<String, TeamGameState> teamStates    = new LinkedHashMap<>();
    private final Map<String, WaveExecutor>  teamExecutors = new LinkedHashMap<>();
    private final List<WaveDefinition>       waves;

    private BossBar    bossBar;
    private BukkitTask heartbeatTask;

    public MobMayhemGameManagerV2(MobMayhemPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.waves  = WaveLibrary.defaultWaves();
    }

    @Override
    protected void onPrepare() {
        teamStates.clear();
        teamExecutors.clear();

        // V2 prepare: world cloning is async — delegate to legacy GameManager.startGame()
        // which will call back into V2 lifecycle when ready. Here we only set up state
        // for players already assigned to arenas after the async clone completes.

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
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§4§l[Mob Mayhem] §eSurvive the waves! The team that lasts longest wins!");
    }

    @Override
    protected void onGameStart() {
        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        // Give starter kits and start wave 1 for each active team
        WaveDefinition wave1 = waves.isEmpty() ? null : waves.get(0);
        for (TeamGameState ts : teamStates.values()) {
            for (UUID uuid : ts.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                plugin.getKitManager().giveStarterKit(p);
                p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                        ChatColor.YELLOW + "Wave 1 begins!", 0, 40, 10);
            }
            if (wave1 == null) continue;
            org.bukkit.World clonedWorld = plugin.getWorldCloner().getActiveClones()
                    .get(ts.getTeamId());
            nl.kmc.mayhem.models.Arena arena = clonedWorld != null
                    ? plugin.getArenaManager().buildForWorld(ts.getTeamId(), clonedWorld)
                    : null;
            if (arena == null) {
                plugin.getLogger().warning("[MobMayhem] No arena for team " + ts.getTeamId() + ", skipping.");
                continue;
            }
            final String teamId = ts.getTeamId();
            WaveExecutor exec = new WaveExecutor(plugin, ts, arena, wave1,
                    survived -> {
                        if (survived) onTeamWaveComplete(teamId, wave1.getWaveNumber());
                        else          onTeamEliminated(teamId);
                    });
            teamExecutors.put(ts.getTeamId(), exec);
            exec.start();
        }

        // Heartbeat: check if all teams eliminated
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long alive = teamStates.values().stream()
                    .filter(ts -> !ts.isEliminated()).count();
            if (alive == 0) end();
            else updateBossBar();
        }, 20L, 20L);
    }

    @Override
    protected void onGameEnd() {
        if (heartbeatTask != null) { heartbeatTask.cancel(); heartbeatTask = null; }
        teamExecutors.values().forEach(WaveExecutor::cancel);
        teamExecutors.clear();
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

    public void onMobKill(UUID killer, int mobPoints) {
        if (!getState().isRunning()) return;
        api.points().givePoints(killer, mobPoints, PointAward.Reason.KILL, registration.getId());
        // Credit the kill to the team state too
        for (TeamGameState ts : teamStates.values()) {
            if (ts.getAlivePlayers().contains(killer)) {
                ts.recordKill(mobPoints);
                break;
            }
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

        int maxWaves = plugin.getConfig().getInt("game.max-waves", waves.size());
        if (waveNumber >= maxWaves) {
            // Team cleared all waves
            broadcast("§4§l[Mob Mayhem] §eTeam " + teamId + " §6cleared ALL waves! Incredible!");
            long otherAlive = teamStates.values().stream()
                    .filter(t -> !t.getTeamId().equals(teamId) && !t.isEliminated()).count();
            if (otherAlive == 0) end();
        }
        updateBossBar();
    }

    private void onTeamEliminated(String teamId) {
        if (!getState().isRunning()) return;
        broadcast("§4☠ §7Team " + teamId + " §7has been eliminated!");
        long alive = teamStates.values().stream().filter(ts -> !ts.isEliminated()).count();
        if (alive == 0) end();
        updateBossBar();
    }

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
