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
    private StandardStartFlow startFlow;
    private Difficulty previousDifficulty;
    /** True during the opening grace minute: nobody can be hurt, loot up in peace. */
    private volatile boolean graceActive;

    private final AssistTracker assistTracker = new AssistTracker();
    private final SupplyDropManager supplyDrops;
    private int bloodbathElapsed;

    public SurvivalGamesManagerV2(SurvivalGamesPlugin plugin, GameRegistration reg,
                                   StatisticsService statsService) {
        super(plugin, reg, statsService);
        this.plugin = plugin;
        this.supplyDrops = new SupplyDropManager(plugin,
                (key, args) -> tellKey(key, args),
                base -> titleKey(base, 10, 50, 10),
                () -> {
                    if (borderRing != null) return borderRing.getRadius();
                    var a = plugin.getArenaManager().getArena();
                    return a != null ? a.getBorderRadius() : 0;
                });
    }

    public SupplyDropManager getSupplyDrops() { return supplyDrops; }

    @Override
    protected void onPrepare() {
        stats.clear();
        eliminationCounter = 0;
        deathmatchActive   = false;
        bloodbathElapsed   = 0;
        supplyDrops.reset();
        assistTracker.clearAll();

        plugin.getChestStocker().stockAllAsync(() ->
                tellKey("sg.stocked", plugin.getChestStocker().getStockedCount()));

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
        // Peaceful would switch off hunger and make health regenerate far too fast.
        if (world != null && world.getDifficulty() == Difficulty.PEACEFUL) {
            previousDifficulty = Difficulty.PEACEFUL;
            world.setDifficulty(Difficulty.NORMAL);
        }

        List<Player> parts = stats.keySet().stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).toList();

        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§e§lSURVIVAL GAMES"; }
                    @Override public String introSubtitle() { return "@sg.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@sg.tut.1", "@sg.tut.2", "@sg.tut.3");
                    }
                    @Override public Location flyoverCenter() { return plugin.getArenaManager().getArena().getCornucopiaCenter(); }
                    @Override public void onFinished() { beginBloodbath(); }
                });
        startFlow.prepareAndFreeze();
    }

    @Override
    protected void onCountdownStart() {
        // Presentation (intro/flyover/tutorial/countdown) runs from onGameStart instead.
    }

    @Override
    protected void onGameStart() {
        startFlow.start();
    }

    private void beginBloodbath() {
        int graceSec = plugin.getConfig().getInt("game.grace-seconds", 60);
        if (graceSec > 0) {
            graceActive = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(api.tr(p.getUniqueId(), "sg.grace.start", graceSec));
                p.sendTitle(api.tr(p.getUniqueId(), "sg.grace.title"), api.tr(p.getUniqueId(), "sg.grace.sub", graceSec), 10, 60, 20);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!getState().isRunning()) return;
                graceActive = false;
                tellKey("sg.grace.over");
                titleKey("sg.pvp", 5, 40, 10);
            }, graceSec * 20L);
        }
        if (bossBar != null) {
            bossBar.setColor(BarColor.RED);
            bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "Bloodbath — PvP");
        }
        if (graceSec <= 0) tellKey("sg.bloodbath");

        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 480)
                + Math.max(0, graceSec);
        int dmTrigger    = plugin.getConfig().getInt("game.deathmatch-trigger-seconds", 120);

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            bloodbathElapsed++;
            if (!deathmatchActive) supplyDrops.tick(bloodbathElapsed);
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
                        tellKey("sg.restock", plugin.getChestStocker().getStockedCount()));
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
        graceActive = false;
        if (gameTimerTask   != null) { gameTimerTask.cancel();   gameTimerTask   = null; }
        if (voidCheckTask   != null) { voidCheckTask.cancel();   voidCheckTask   = null; }
        if (restockTask     != null) { restockTask.cancel();     restockTask     = null; }
        if (borderRingTask  != null) { borderRingTask.cancel();  borderRingTask  = null; }
        if (startFlow       != null) { startFlow.cancel();       startFlow       = null; }
        supplyDrops.clearAll();

        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(false);
        if (world != null && previousDifficulty != null) { world.setDifficulty(previousDifficulty); previousDifficulty = null; }

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
            nl.kmc.game.api.GamePlayerUtil.teleportToLobby(p, lobby);
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
        player.sendMessage(api.tr(player.getUniqueId(), "game.state-restored"));
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

    /** SGListener cancels PvP itself outside the PvP phases, so the server-wide no-PvP rule must stand aside. */
    @Override protected boolean handlesPlayerPvp() { return true; }

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
        assistTracker.recordHit(victim, attacker);
    }

    public Player getRecentAttacker(UUID victim) {
        UUID id = assistTracker.getKiller(victim);
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
            api.games().announceElimination(victim.getUniqueId(), killer.getUniqueId(), registration.getId());
            PlayerStats ks = stats.get(killer.getUniqueId());
            if (ks != null) ks.incrementKills();
            int killPts = plugin.getConfig().getInt("points.per-kill", 50);
            UUID assistId = assistTracker.getAssist(victim.getUniqueId());
            var split = AssistTracker.split(killPts, assistId,
                    plugin.getConfig().getDouble("points.assist-fraction", 0.2));
            api.points().givePoints(killer.getUniqueId(), split.killerAmount(),
                    PointAward.Reason.KILL, registration.getId());
            if (assistId != null && split.assistAmount() > 0) {
                api.points().givePoints(assistId, split.assistAmount(), PointAward.Reason.ASSIST, registration.getId());
                Player assistPlayer = Bukkit.getPlayer(assistId);
                if (assistPlayer != null)
                    assistPlayer.sendMessage(api.tr(assistId, "game.assist", split.assistAmount(), victim.getName()));
            }
            assistTracker.clear(victim.getUniqueId());
            broadcast("§c☠ §7" + victim.getName() + " §8← §e" + killer.getName());
        } else {
            assistTracker.clear(victim.getUniqueId());
            for (Player rp : Bukkit.getOnlinePlayers())
                rp.sendMessage("§c☠ §7" + victim.getName() + " §7" + api.tr(rp.getUniqueId(), reason));
        }
        victim.sendTitle(api.tr(victim.getUniqueId(), "game.eliminated.title"),
                "§7" + api.tr(victim.getUniqueId(), reason), 10, 50, 10);

        // Survival bonus
        int bonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (bonus > 0) {
            stats.values().stream().filter(s -> s.isAlive() && !s.getUuid().equals(victim.getUniqueId()))
                .forEach(s -> api.points().givePoints(s.getUuid(), bonus, PointAward.Reason.SURVIVAL_BONUS, registration.getId()));
        }

        checkWin();
        updateBossBar();
    }

    public boolean isPvpAllowed() { return getState().isRunning() && !graceActive; }
    public boolean isGraceActive() { return graceActive; }
    public Map<UUID, PlayerStats> getStatsMap() { return Collections.unmodifiableMap(stats); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void startDeathmatch() {
        deathmatchActive = true;
        supplyDrops.stopScheduling();
        tellKey("game.deathmatch.chat");
        PotionEffectType glow = GamePlayerUtil.glowing();
        if (glow != null) {
            stats.values().stream().filter(PlayerStats::isAlive).forEach(ps -> {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p != null) p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
            });
        }
        if (bossBar != null) bossBar.setColor(BarColor.PURPLE);

        spawnFeast();
    }

    /** One-time top-loot chest at the cornucopia, timed with deathmatch — draws survivors together. */
    private void spawnFeast() {
        if (!plugin.getConfig().getBoolean("game.feast-enabled", true)) return;
        var arena = plugin.getArenaManager().getArena();
        Location cor = arena != null ? arena.getCornucopiaCenter() : null;
        if (cor == null || !plugin.getChestStocker().spawnFeastChest(cor)) return;

        tellKey("sg.feast.chat");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(api.tr(p.getUniqueId(), "sg.feast.title"), api.tr(p.getUniqueId(), "sg.feast.sub"), 10, 60, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        }
        cor.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, cor.clone().add(0, 1.5, 0), 40, 1, 1, 1, 0.3);
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
            if (p.getLocation().getY() < voidY) handleDeath(p, getRecentAttacker(uuid), "game.reason.void");
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = stats.values().stream().filter(PlayerStats::isAlive).count();
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        bossBar.setTitle((deathmatchActive ? "§4DEATHMATCH" : "§cSG")
                + " §8| §e" + alive + "👤 §8| §b" + String.format("%02d:%02d", min, sec));
    }

}
