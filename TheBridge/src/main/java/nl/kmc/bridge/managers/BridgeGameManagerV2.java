package nl.kmc.bridge.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.models.BridgeTeam;
import nl.kmc.bridge.models.PlayerStats;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 The Bridge manager — first team to N goals wins.
 *
 * <p>Two teams build across to each other's goal hole. Scoring by entering
 * the opponent's goal. Death respawns at team spawn.
 */
public final class BridgeGameManagerV2 extends BaseGameManager {

    private final TheBridgePlugin plugin;

    private final Map<UUID, PlayerStats> stats      = new LinkedHashMap<>();
    private final Map<String, BridgeTeam> bridgeTeams = new LinkedHashMap<>();

    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;
    private int        remainingSeconds;
    private long       gameStartMs;

    private final Map<UUID, Long> goalCooldown   = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker   = new HashMap<>();
    private final Map<UUID, Long> lastAttackerMs = new HashMap<>();

    public BridgeGameManagerV2(TheBridgePlugin plugin, GameRegistration reg, StatisticsService statsService) {
        super(plugin, reg, statsService);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        stats.clear();
        bridgeTeams.clear();
        goalCooldown.clear();
        lastAttacker.clear();
        lastAttackerMs.clear();

        plugin.getArenaManager().load();

        for (Player p : Bukkit.getOnlinePlayers()) {
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            String teamId = kmcTeam != null ? kmcTeam.getId() : "default";
            if (!bridgeTeams.containsKey(teamId) && kmcTeam != null) {
                BridgeTeam bt = plugin.getArenaManager().getTeam(teamId);
                if (bt != null) bridgeTeams.put(teamId, bt);
            }
            Location spawn = bridgeTeams.containsKey(teamId)
                    ? bridgeTeams.get(teamId).getSpawn() : p.getLocation();
            p.teleport(spawn);
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
            stats.put(p.getUniqueId(), new PlayerStats(p.getUniqueId(), p.getName(), teamId));
            plugin.getKitManager().giveKit(p, bridgeTeams.get(teamId));
        }

        bossBar = Bukkit.createBossBar(ChatColor.BLUE + "" + ChatColor.BOLD + "The Bridge",
                BarColor.BLUE, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§9§l[The Bridge] §eStart building bridges to the enemy's goal!");
    }

    @Override
    protected void onGameStart() {
        gameStartMs      = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 480);

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::handleVoidAndBorder, 5L, 5L);
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (voidCheckTask != null) { voidCheckTask.cancel(); voidCheckTask = null; }
        if (bossBar       != null) { bossBar.removeAll();    bossBar       = null; }

        // Rank teams by goals, tiebreak: kills
        List<BridgeTeam> ranked = new ArrayList<>(bridgeTeams.values());
        ranked.sort((a, b) -> {
            int diff = Integer.compare(b.getGoalsScored(), a.getGoalsScored());
            return diff != 0 ? diff : Integer.compare(b.getKillsScored(), a.getKillsScored());
        });

        String winnerDesc = ranked.isEmpty() ? "No winner" :
                ranked.get(0).getChatColor().toString() + ranked.get(0).getDisplayName()
                + " §7(" + ranked.get(0).getGoalsScored() + " goals)";

        // Award team placements
        for (int i = 0; i < ranked.size(); i++) {
            api.points().awardTeamPlacement(ranked.get(i).getId(), i + 1, registration.getId());
        }

        // Per-player placements based on their team's rank
        List<UUID> finishOrder = new ArrayList<>();
        Map<String, Integer> teamRankMap = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) teamRankMap.put(ranked.get(i).getId(), i + 1);

        List<PlayerStats> playerRanked = new ArrayList<>(stats.values());
        playerRanked.sort((a, b) -> {
            int ar = teamRankMap.getOrDefault(a.getTeamId(), 99);
            int br = teamRankMap.getOrDefault(b.getTeamId(), 99);
            return Integer.compare(ar, br);
        });

        UUID mvpUuid = null; String mvpName = null; int topKills = 0;
        for (int i = 0; i < playerRanked.size(); i++) {
            PlayerStats ps = playerRanked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, playerRanked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getKills() > topKills) { topKills = ps.getKills(); mvpUuid = ps.getUuid(); mvpName = ps.getName(); }
        }

        returnToLobby();
        stats.clear();
        bridgeTeams.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, finishOrder);
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
        PlayerStats ps = stats.get(player.getUniqueId());
        if (ps != null) {
            s.extra.put("goals",  ps.getGoals());
            s.extra.put("kills",  ps.getKills());
            s.extra.put("teamId", ps.getTeamId());
        }
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        BridgeTeam rt = stats.containsKey(player.getUniqueId())
                ? bridgeTeams.get(stats.get(player.getUniqueId()).getTeamId()) : null;
        plugin.getKitManager().giveKit(player, rt);
        player.sendMessage("§9[Bridge] State restored!");
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "The Bridge"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Bridge arena not ready: " + plugin.getArenaManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void onGoalScored(Player scorer) {
        if (!getState().isRunning()) return;
        Long last = goalCooldown.get(scorer.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 2000) return;
        goalCooldown.put(scorer.getUniqueId(), System.currentTimeMillis());

        PlayerStats ps = stats.get(scorer.getUniqueId());
        if (ps == null) return;
        ps.addGoal();

        BridgeTeam team = bridgeTeams.get(ps.getTeamId());
        if (team != null) {
            team.addGoal();
            int goalPts = plugin.getConfig().getInt("points.per-goal", 150);
            api.points().givePoints(scorer.getUniqueId(), goalPts, PointAward.Reason.OBJECTIVE, registration.getId());
            api.points().giveTeamPoints(ps.getTeamId(), goalPts / 2, PointAward.Reason.OBJECTIVE, registration.getId());

            broadcast("§b⚽ §e" + scorer.getName() + " §9scored for §" + team.getChatColor().getChar() + team.getDisplayName()
                    + "§9! (" + team.getGoalsScored() + "/" + plugin.getConfig().getInt("game.goals-to-win", 5) + ")");

            int goalsToWin = plugin.getConfig().getInt("game.goals-to-win", 5);
            if (team.getGoalsScored() >= goalsToWin) end();
            else respawnAfterGoal(scorer);
        }
        updateBossBar();
    }

    public void recordAttack(UUID victim, UUID attacker) {
        lastAttacker.put(victim, attacker);
        lastAttackerMs.put(victim, System.currentTimeMillis());
    }

    public void handleDeath(Player victim) {
        if (!getState().isRunning()) return;
        PlayerStats ps = stats.get(victim.getUniqueId());
        if (ps == null) return;

        Player killer = getRecentAttacker(victim.getUniqueId());
        if (killer != null) {
            PlayerStats ks = stats.get(killer.getUniqueId());
            if (ks != null) {
                ks.addKill();
                BridgeTeam kt = bridgeTeams.get(ks.getTeamId());
                if (kt != null) kt.addKill();
            }
            api.points().givePoints(killer.getUniqueId(),
                    plugin.getConfig().getInt("points.per-kill", 50), PointAward.Reason.KILL, registration.getId());
            broadcast("§c☠ §7" + victim.getName() + " §8← §e" + killer.getName());
        }

        respawn(victim);
    }

    public boolean isPvpAllowed() { return getState().isRunning(); }
    public Map<UUID, PlayerStats> getStatsMap() { return Collections.unmodifiableMap(stats); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Player getRecentAttacker(UUID victim) {
        Long when = lastAttackerMs.get(victim);
        if (when == null || System.currentTimeMillis() - when > 10_000) return null;
        UUID id = lastAttacker.get(victim);
        return id != null ? Bukkit.getPlayer(id) : null;
    }

    private void respawn(Player player) {
        PlayerStats ps = stats.get(player.getUniqueId());
        if (ps == null) return;
        BridgeTeam team = bridgeTeams.get(ps.getTeamId());
        if (team != null) {
            player.teleport(team.getSpawn());
            player.setHealth(20); player.setFoodLevel(20);
            player.getInventory().clear();
            player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
            plugin.getKitManager().giveKit(player, team);
        }
    }

    private void respawnAfterGoal(Player scorer) {
        respawn(scorer);
        scorer.sendTitle("§b§lGOAL!", "§7Respawning…", 5, 40, 10);
        scorer.playSound(scorer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void handleVoidAndBorder() {
        if (!getState().isRunning()) return;
        int voidY = plugin.getArenaManager().getVoidYLevel();
        stats.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            if (p.getLocation().getY() < voidY) handleDeath(p);
        });
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        int goalsToWin = plugin.getConfig().getInt("game.goals-to-win", 5);
        StringBuilder sb = new StringBuilder(ChatColor.BLUE + "" + ChatColor.BOLD + "The Bridge §8| ");
        bridgeTeams.values().forEach(t -> sb.append("§").append(t.getChatColor().getChar())
                .append(t.getDisplayName()).append(" §7").append(t.getGoalsScored())
                .append("/").append(goalsToWin).append(" §8| "));
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        sb.append("§b").append(String.format("%02d:%02d", min, sec));
        bossBar.setTitle(sb.toString());
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        stats.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        });
    }
}
