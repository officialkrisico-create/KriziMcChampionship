package nl.kmc.skywars.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.skywars.util.ShrinkingRingRenderer;
import nl.kmc.skywars.SkyWarsPlugin;
import nl.kmc.skywars.models.Island;
import nl.kmc.skywars.models.PlayerStats;
import nl.kmc.skywars.models.Team;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 SkyWars game manager — extends {@link BaseGameManager} for tournament integration.
 *
 * <p>Lifecycle driven by BaseGameManager. Game-specific logic:
 *   onPrepare()         → map KMCTeams → islands, stock chests, TP + freeze players
 *   onCountdownStart()  → unfreeze
 *   onGameStart()       → enable PvP, start game timer + void check
 *   onGameEnd()         → rank teams, award placement points, fireResult()
 */
public final class SkyWarsGameManagerV2 extends BaseGameManager {

    private final SkyWarsPlugin plugin;

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private final Map<String, Team>      teams = new LinkedHashMap<>();
    private int eliminationCounter;

    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BukkitTask deathmatchRingTask;
    private BukkitTask restockTask;
    private BossBar    bossBar;

    private int  remainingSeconds;
    private long gameStartMs;

    private final Map<UUID, UUID> lastAttacker   = new HashMap<>();
    private final Map<UUID, Long> lastAttackerMs = new HashMap<>();

    private ShrinkingRingRenderer ringRenderer;
    private boolean deathmatchActive = false;

    public SkyWarsGameManagerV2(SkyWarsPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    // ── BaseGameManager abstract methods ──────────────────────────────────────

    @Override
    protected void onPrepare() {
        stats.clear();
        teams.clear();
        eliminationCounter = 0;
        lastAttacker.clear();
        lastAttackerMs.clear();
        deathmatchActive   = false;
        ringRenderer = new ShrinkingRingRenderer(
                plugin.getConfig().getDouble("game.deathmatch-ring-start", 40),
                plugin.getConfig().getDouble("game.deathmatch-ring-shrink-per-sec", 0.5));

        if (!buildTeamMap()) {
            log.severe("[SkyWars] onPrepare() failed: could not build team map.");
            end();
            return;
        }

        int stocked = plugin.getChestStocker().stockAll();
        broadcast("§6[SkyWars] §e" + stocked + " chests stocked with loot.");

        teleportAndFreezePlayers();

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "SkyWars starting…",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        for (PlayerStats ps : stats.values()) {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p != null) GamePlayerUtil.unfreezePlayer(p);
        }
        if (bossBar != null) {
            bossBar.setColor(BarColor.GREEN);
            bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "OPEN CHESTS — no PvP yet");
        }
        broadcast("§a§l[SkyWars] §eOpen chests! §7No PvP during grace period.");
    }

    @Override
    protected void onGameStart() {
        gameStartMs    = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 600);

        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(true);

        if (bossBar != null) bossBar.setColor(BarColor.RED);
        updateBossBar();
        broadcast("§c§l[SkyWars] §ePvP is live! Last team standing wins!");

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            int dmTrigger = plugin.getConfig().getInt("game.deathmatch-trigger-seconds", 360);
            int totalSec  = plugin.getConfig().getInt("game.max-duration-seconds", 600);
            if (!deathmatchActive && remainingSeconds <= totalSec - dmTrigger) beginDeathmatch();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);

        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoidFalls, 5L, 5L);

        // Optional periodic chest restock (0 = off). Pauses during deathmatch.
        int restockSec = plugin.getConfig().getInt("game.chest-restock-seconds", 0);
        if (restockSec > 0) {
            restockTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!getState().isRunning() || deathmatchActive) return;
                int n = plugin.getChestStocker().stockAll();
                broadcast("§6[SkyWars] §eDe kisten zijn opnieuw gevuld! §7(" + n + ")");
            }, restockSec * 20L, restockSec * 20L);
        }
    }

    @Override
    protected void onGameEnd() {
        cancelTasks();

        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(false);

        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        // Rank teams: alive members desc → total kills desc
        var teamList = new ArrayList<>(teams.values());
        teamList.sort((a, b) -> {
            int diff = Integer.compare(countAlive(b), countAlive(a));
            if (diff != 0) return diff;
            return Integer.compare(teamKills(b), teamKills(a));
        });

        broadcast("§6═══════════════════════════════════");
        broadcast("§6§lSkyWars — Results");
        for (int i = 0; i < teamList.size(); i++) {
            Team t = teamList.get(i);
            String medal = i == 0 ? "§6🥇" : i == 1 ? "§7🥈" : i == 2 ? "§c🥉" : "§7#" + (i + 1);
            broadcast("  " + medal + " " + t.getChatColor() + t.getDisplayName()
                    + " §8- §e" + countAlive(t) + " alive (" + teamKills(t) + " kills)");
            api.points().awardTeamPlacement(t.getId(), i + 1, registration.getId());
        }

        // Per-player placement
        List<PlayerStats> ranked = new ArrayList<>(stats.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.getEliminationOrder() != b.getEliminationOrder())
                return Integer.compare(b.getEliminationOrder(), a.getEliminationOrder());
            return Integer.compare(b.getKills(), a.getKills());
        });

        List<UUID> finishOrder = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            PlayerStats ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
        }

        String winnerDesc = teamList.isEmpty() ? "No winner" :
                teamList.get(0).getChatColor() + teamList.get(0).getDisplayName();

        // Best killer = MVP candidate
        UUID   mvpUuid = null;
        String mvpName = null;
        int    topKills = 0;
        for (PlayerStats ps : stats.values()) {
            if (ps.getKills() > topKills) {
                topKills = ps.getKills();
                mvpUuid  = ps.getUuid();
                mvpName  = ps.getName();
            }
        }

        // Return players to lobby
        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID uuid : stats.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20);
            p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        }

        stats.clear();
        teams.clear();

        fireResult(winnerDesc, mvpUuid, mvpName, finishOrder);
    }

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState state = new PlayerGameState();
        state.inventory = player.getInventory().getContents().clone();
        state.armor      = player.getInventory().getArmorContents().clone();
        state.health     = player.getHealth();
        state.maxHealth  = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20;
        state.location   = player.getLocation();
        state.effects    = new ArrayList<>(player.getActivePotionEffects());
        PlayerStats ps = stats.get(player.getUniqueId());
        if (ps != null) {
            state.extra.put("kills",            ps.getKills());
            state.extra.put("alive",            ps.isAlive());
            state.extra.put("teamId",           ps.getTeamId());
            state.extra.put("eliminationOrder", ps.getEliminationOrder());
        }
        return state;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§a[SkyWars] Your state has been restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.common.time", String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)));
        long aliveP = stats.values().stream().filter(PlayerStats::isAlive).count();
        java.util.Set<String> aliveT = new java.util.HashSet<>();
        stats.values().stream().filter(PlayerStats::isAlive).forEach(ps -> aliveT.add(ps.getTeamId()));
        l.add(api.tr(id, "sb.common.teams-left", aliveT.size()));
        l.add(api.tr(id, "sb.common.players-left", aliveP));
        PlayerStats me = stats.get(id);
        if (me != null) {
            l.add("");
            l.add(me.isAlive() ? api.tr(id, "sb.common.alive") : api.tr(id, "sb.common.eliminated"));
            l.add(api.tr(id, "sb.common.kills", me.getKills()));
        }
        if (deathmatchActive) { l.add(""); l.add(api.tr(id, "sb.common.deathmatch")); }
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Team SkyWars"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                var arena = plugin.getArenaManager();
                if (!arena.isReady()) r.addError("Arena not ready: " + arena.getReadinessReport());
                if (arena.getIslands().size() < 2) r.addError("Minimum 2 islands required.");
                if (arena.getMiddleSpawn() == null) r.addWarning("No middle island spawn set.");
                return r;
            }
        };
    }

    // ── Public game events ────────────────────────────────────────────────────

    public void recordAttack(UUID victim, UUID attacker) {
        if (victim.equals(attacker)) return;
        lastAttacker.put(victim, attacker);
        lastAttackerMs.put(victim, System.currentTimeMillis());
    }

    public Player getRecentAttacker(UUID victim) {
        Long when = lastAttackerMs.get(victim);
        if (when == null || System.currentTimeMillis() - when > 10_000) return null;
        UUID id = lastAttacker.get(victim);
        return id != null ? Bukkit.getPlayer(id) : null;
    }

    public void handleDeath(Player victim, Player killer, String reason) {
        if (!getState().isRunning()) return;
        PlayerStats ps = stats.get(victim.getUniqueId());
        if (ps == null || !ps.isAlive()) return;

        ps.eliminate(eliminationCounter++);
        victim.setGameMode(GameMode.SPECTATOR);
        victim.getInventory().clear();
        victim.getActivePotionEffects().forEach(e -> victim.removePotionEffect(e.getType()));
        victim.setHealth(20);
        victim.setFoodLevel(20);

        if (killer != null && !killer.equals(victim)) {
            PlayerStats ks = stats.get(killer.getUniqueId());
            if (ks != null) ks.incrementKills();
            int killPts = plugin.getConfig().getInt("points.per-kill", 50);
            api.points().givePoints(killer.getUniqueId(), killPts, PointAward.Reason.KILL, registration.getId());
            broadcast("§c☠ §7" + victim.getName() + " §8← §e" + killer.getName());
        } else {
            broadcast("§c☠ §7" + victim.getName() + " §7" + reason);
        }

        victim.sendTitle("§c§lEliminated!", "§7" + reason, 10, 50, 10);

        // Survival bonus for still-alive players
        int survivalBonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (survivalBonus > 0) {
            stats.values().stream()
                .filter(s -> s.isAlive() && !s.getUuid().equals(victim.getUniqueId()))
                .forEach(s -> api.points().givePoints(
                    s.getUuid(), survivalBonus, PointAward.Reason.SURVIVAL_BONUS, registration.getId()));
        }

        checkWinCondition();
        updateBossBar();
    }

    public boolean isPvpAllowed() { return getState() == GameState.ACTIVE || deathmatchActive; }

    public Map<UUID, PlayerStats> getStatsMap() { return Collections.unmodifiableMap(stats); }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private boolean buildTeamMap() {
        var islands = new ArrayList<>(plugin.getArenaManager().getIslands().values());
        if (islands.size() < 2) return false;

        Set<String> teamsWithPlayers = new LinkedHashSet<>();
        Map<UUID, String> playerToKmcTeam = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var t = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (t != null) {
                teamsWithPlayers.add(t.getId());
                playerToKmcTeam.put(p.getUniqueId(), t.getId());
            }
        }

        // Assign teams to islands (bound first, then round-robin)
        Set<String> usedIslands = new HashSet<>();
        Map<String, Team> kmcToSw = new HashMap<>();
        for (Island island : islands) {
            String bound = island.getTeamId();
            if (bound == null || !teamsWithPlayers.contains(bound)) continue;
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeam(bound);
            if (kmcTeam == null) continue;
            Team t = new Team(bound, kmcTeam.getDisplayName(), kmcTeam.getColor(), island);
            teams.put(bound, t);
            kmcToSw.put(bound, t);
            usedIslands.add(island.getId());
        }
        List<Island> freeIslands = islands.stream().filter(i -> !usedIslands.contains(i.getId())).toList();
        int idx = 0;
        for (String kmcId : teamsWithPlayers) {
            if (kmcToSw.containsKey(kmcId) || idx >= freeIslands.size()) continue;
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeam(kmcId);
            if (kmcTeam == null) continue;
            Team t = new Team(kmcId, kmcTeam.getDisplayName(), kmcTeam.getColor(), freeIslands.get(idx++));
            teams.put(kmcId, t);
            kmcToSw.put(kmcId, t);
        }
        if (teams.size() < 2) return false;

        // Assign players to teams + build stats map
        var teamList = new ArrayList<>(teams.values());
        int wrap = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String kmcId = playerToKmcTeam.get(p.getUniqueId());
            Team target  = kmcId != null && kmcToSw.containsKey(kmcId)
                    ? kmcToSw.get(kmcId)
                    : teamList.get(wrap++ % teamList.size());
            target.addMember(p.getUniqueId());
            stats.put(p.getUniqueId(), new PlayerStats(p.getUniqueId(), p.getName(), target.getId()));
        }
        return !stats.isEmpty();
    }

    private void teleportAndFreezePlayers() {
        int countdownTicks = plugin.getConfig().getInt("game.countdown-seconds", 15) * 20;
        for (Team t : teams.values()) {
            int memberIdx = 0;
            for (UUID uuid : t.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) { memberIdx++; continue; }
                p.teleport((Location) t.getIsland().getSpawnForMember(memberIdx++));
                GamePlayerUtil.resetPlayer(p);
                GamePlayerUtil.freezePlayer(p, countdownTicks);
            }
        }
    }

    private void beginDeathmatch() {
        deathmatchActive = true;
        broadcast("§c§l[DEATHMATCH] §eThe ring closes in!");
        Bukkit.getOnlinePlayers().forEach(p ->
                p.sendTitle("§c§lDEATHMATCH", "§eGlowing active — find each other!", 10, 60, 20));

        var glow = GamePlayerUtil.glowing();
        if (glow != null) {
            stats.values().stream().filter(PlayerStats::isAlive).forEach(ps -> {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p != null) p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
            });
        }
        ringRenderer.reset();
        double dmg    = plugin.getConfig().getDouble("game.deathmatch-ring-damage", 2.0);
        double buffer = plugin.getConfig().getDouble("game.deathmatch-ring-buffer", 1.0);
        deathmatchRingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Location mid = plugin.getArenaManager().getMiddleSpawn();
            ringRenderer.tick(mid);
            if (mid == null || dmg <= 0) return;
            // Damage anyone outside the closing ring (horizontal distance).
            double limit = Math.max(1, ringRenderer.getCurrentRadius() - buffer);
            double limitSq = limit * limit;
            stats.values().stream().filter(PlayerStats::isAlive).forEach(ps -> {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p == null || p.isDead()) return;
                double dx = p.getLocation().getX() - mid.getX();
                double dz = p.getLocation().getZ() - mid.getZ();
                if (dx * dx + dz * dz > limitSq) {
                    p.damage(dmg);
                    p.getWorld().spawnParticle(org.bukkit.Particle.DAMAGE_INDICATOR, p.getEyeLocation(), 4, 0.2, 0.2, 0.2, 0);
                }
            });
        }, 20L, 20L);
    }

    private void checkVoidFalls() {
        if (!getState().isRunning()) return;
        int voidY = plugin.getArenaManager().getVoidYLevel();
        for (UUID uuid : new ArrayList<>(stats.keySet())) {
            PlayerStats ps = stats.get(uuid);
            if (ps == null || !ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getLocation().getY() < voidY) handleDeath(p, getRecentAttacker(uuid), "fell into the void");
        }
    }

    private void checkWinCondition() {
        Set<String> aliveTeams = new HashSet<>();
        stats.values().stream().filter(PlayerStats::isAlive).forEach(ps -> aliveTeams.add(ps.getTeamId()));
        if (aliveTeams.size() <= 1) end();
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        int aliveTeams = 0, alivePlayers = 0;
        for (Team t : teams.values()) {
            int n = countAlive(t);
            if (n > 0) { aliveTeams++; alivePlayers += n; }
        }
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        String phase = deathmatchActive ? "§4DEATHMATCH" : "§cPVP";
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                phase + " §8| §e" + aliveTeams + " teams (" + alivePlayers
                + " alive) §8| §b" + String.format("%02d:%02d", min, sec)));
        int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 600);
        bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingSeconds / totalSec)));
    }

    private int countAlive(Team team) {
        return (int) team.getMembers().stream()
                .filter(uuid -> { PlayerStats ps = stats.get(uuid); return ps != null && ps.isAlive(); })
                .count();
    }

    private int teamKills(Team team) {
        return team.getMembers().stream()
                .mapToInt(uuid -> { PlayerStats ps = stats.get(uuid); return ps != null ? ps.getKills() : 0; })
                .sum();
    }

    private void cancelTasks() {
        cancel(gameTimerTask);      gameTimerTask      = null;
        cancel(voidCheckTask);      voidCheckTask      = null;
        cancel(deathmatchRingTask); deathmatchRingTask = null;
        cancel(restockTask);        restockTask        = null;
    }

    private void cancel(BukkitTask t) { if (t != null) t.cancel(); }

}
