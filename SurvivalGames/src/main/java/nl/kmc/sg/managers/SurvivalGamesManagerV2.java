package nl.kmc.sg.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.sg.SurvivalGamesPlugin;
import nl.kmc.sg.models.PlayerStats;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** V2 Survival Games manager — solo, last alive wins, world-border deathmatch. */
public final class SurvivalGamesManagerV2 extends BaseGameManager {

    private final SurvivalGamesPlugin plugin;

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private int eliminationCounter;

    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BukkitTask restockTask;
    private BukkitTask borderRingTask;
    private nl.kmc.game.api.ShrinkingBorderRing borderRing;
    private BossBar    bossBar;

    private int  remainingSeconds;
    private boolean deathmatchActive = false;

    private final Map<UUID, UUID> lastAttacker   = new HashMap<>();
    private final Map<UUID, Long> lastAttackerMs = new HashMap<>();

    public SurvivalGamesManagerV2(SurvivalGamesPlugin plugin, GameRegistration reg,
                                   StatisticsService statsService) {
        super(plugin, reg, statsService);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        stats.clear();
        eliminationCounter = 0;
        deathmatchActive   = false;
        lastAttacker.clear();
        lastAttackerMs.clear();

        plugin.getChestStocker().stockAllAsync(() ->
                broadcast("§6[SG] §e" + plugin.getChestStocker().getStockedCount() + " chests stocked."));

        // Teleport players to scatter spawns
        List<Location> spawns = plugin.getArenaManager().getSpawnLocations();
        List<Player> online   = new ArrayList<>(Bukkit.getOnlinePlayers());
        int countdownSec = plugin.getConfig().getInt("game.countdown-seconds", 30);
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            Location dest = spawns.isEmpty() ? p.getLocation() : spawns.get(i % spawns.size());
            GamePlayerUtil.safeTeleport(p, dest);
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
            GamePlayerUtil.freezePlayer(p, countdownSec * 20);
            stats.put(p.getUniqueId(), new PlayerStats(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.YELLOW + "" + ChatColor.BOLD + "Survival Games",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(true);
    }

    @Override
    protected void onCountdownStart() {
        stats.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) GamePlayerUtil.unfreezePlayer(p);
        });
        if (bossBar != null) {
            bossBar.setColor(BarColor.RED);
            bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "Bloodbath — PvP ACTIVE");
        }
        broadcast("§c§l[SG] §eBloodbath started — all PvP active!");
    }

    @Override
    protected void onGameStart() {
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 480);
        int dmTrigger    = plugin.getConfig().getInt("game.deathmatch-trigger-seconds", 120);

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (!deathmatchActive && remainingSeconds <= dmTrigger) startDeathmatch();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);

        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoid, 5L, 5L);

        // Damaging particle ring that slowly closes toward the cornucopia.
        startBorderRing();

        // Optional periodic chest restock (0 = off). Pauses during deathmatch.
        int restockSec = plugin.getConfig().getInt("game.chest-restock-seconds", 0);
        if (restockSec > 0) {
            restockTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!getState().isRunning() || deathmatchActive) return;
                plugin.getChestStocker().stockAllAsync(() ->
                        broadcast("§6[SG] §eDe kisten zijn opnieuw gevuld! §7("
                                + plugin.getChestStocker().getStockedCount() + ")"));
            }, restockSec * 20L, restockSec * 20L);
        }
    }

    /** Starts a damaging particle ring that slowly closes toward the cornucopia. */
    private void startBorderRing() {
        var arena = plugin.getArenaManager().getArena();
        if (arena == null) return;
        Location center = arena.getCornucopiaCenter();
        double start = arena.getBorderRadius();
        if (center == null || start <= 0) return;

        double min = arena.getBorderMinRadius() > 0 ? arena.getBorderMinRadius() : 5;
        int shrinkSec = plugin.getConfig().getInt("game.border-shrink-seconds", 0);
        if (shrinkSec <= 0) shrinkSec = Math.max(30, remainingSeconds - 20);
        double perSec  = (start - min) / shrinkSec;
        double damage  = plugin.getConfig().getDouble("game.border-damage", 1.0);
        double buffer  = plugin.getConfig().getDouble("game.border-buffer", 2.0);

        borderRing = new ShrinkingBorderRing(center, start, min, perSec, damage, buffer, org.bukkit.Particle.FLAME);
        borderRingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!getState().isRunning()) return;
            var alive = stats.values().stream().filter(PlayerStats::isAlive)
                    .map(ps -> Bukkit.getPlayer(ps.getUuid()))
                    .filter(java.util.Objects::nonNull).toList();
            borderRing.tick(alive);
        }, 20L, 20L);
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask   != null) { gameTimerTask.cancel();   gameTimerTask   = null; }
        if (voidCheckTask   != null) { voidCheckTask.cancel();   voidCheckTask   = null; }
        if (restockTask     != null) { restockTask.cancel();     restockTask     = null; }
        if (borderRingTask  != null) { borderRingTask.cancel();  borderRingTask  = null; }

        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(false);

        List<PlayerStats> ranked = new ArrayList<>(stats.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.getEliminationOrder() != b.getEliminationOrder())
                return Integer.compare(b.getEliminationOrder(), a.getEliminationOrder());
            return Integer.compare(b.getKills(), a.getKills());
        });

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null; int topKills = 0;

        for (int i = 0; i < ranked.size(); i++) {
            PlayerStats ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getKills() > topKills) { topKills = ps.getKills(); mvpUuid = ps.getUuid(); mvpName = ps.getName(); }
        }

        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        stats.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            GamePlayerUtil.resetPlayer(p);
            if (lobby != null) p.teleport(lobby);
        });
        stats.clear();

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
            s.extra.put("kills", ps.getKills());
            s.extra.put("alive", ps.isAlive());
        }
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§a[SG] Your state has been restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.common.time", String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)));
        long alive = stats.values().stream().filter(PlayerStats::isAlive).count();
        l.add(api.tr(id, "sb.common.players-left", alive));
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
            @Override public String getGameName() { return "Survival Games"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Arena not ready: " + plugin.getArenaManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API for listeners ──────────────────────────────────────────────

    public void recordAttack(UUID victim, UUID attacker) {
        if (!victim.equals(attacker)) {
            lastAttacker.put(victim, attacker);
            lastAttackerMs.put(victim, System.currentTimeMillis());
        }
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
        victim.setHealth(20); victim.setFoodLevel(20);

        if (killer != null && !killer.equals(victim)) {
            PlayerStats ks = stats.get(killer.getUniqueId());
            if (ks != null) ks.incrementKills();
            api.points().givePoints(killer.getUniqueId(),
                    plugin.getConfig().getInt("points.per-kill", 50),
                    PointAward.Reason.KILL, registration.getId());
            broadcast("§c☠ §7" + victim.getName() + " §8← §e" + killer.getName());
        } else {
            broadcast("§c☠ §7" + victim.getName() + " §7" + reason);
        }
        victim.sendTitle("§c§lEliminated!", "§7" + reason, 10, 50, 10);

        // Survival bonus
        int bonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (bonus > 0) {
            stats.values().stream().filter(s -> s.isAlive() && !s.getUuid().equals(victim.getUniqueId()))
                .forEach(s -> api.points().givePoints(s.getUuid(), bonus, PointAward.Reason.SURVIVAL_BONUS, registration.getId()));
        }

        checkWin();
        updateBossBar();
    }

    public boolean isPvpAllowed() { return getState().isRunning(); }
    public Map<UUID, PlayerStats> getStatsMap() { return Collections.unmodifiableMap(stats); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void startDeathmatch() {
        deathmatchActive = true;
        broadcast("§c§l[DEATHMATCH] §eDe ring sluit nu snel naar het midden!");
        PotionEffectType glow = GamePlayerUtil.glowing();
        if (glow != null) {
            stats.values().stream().filter(PlayerStats::isAlive).forEach(ps -> {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p != null) p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
            });
        }
        if (bossBar != null) bossBar.setColor(BarColor.PURPLE);
    }

    private void checkWin() {
        long alive = stats.values().stream().filter(PlayerStats::isAlive).count();
        if (alive <= 1) end();
    }

    private void checkVoid() {
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

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = stats.values().stream().filter(PlayerStats::isAlive).count();
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        bossBar.setTitle((deathmatchActive ? "§4DEATHMATCH" : "§cSG")
                + " §8| §e" + alive + " alive §8| §b" + String.format("%02d:%02d", min, sec));
    }

}
