package nl.kmc.adventure.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.RacerData;
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

/**
 * V2 Adventure Escape manager — multi-lap racing with checkpoints.
 *
 * <p>First player to complete all configured laps wins. Points awarded per
 * lap and per finish placement. Non-finishers ranked by laps + progress.
 */
public final class AdventureEscapeGameManagerV2 extends BaseGameManager {

    private final AdventureEscapePlugin plugin;

    private final Map<UUID, RacerData> racers      = new LinkedHashMap<>();
    private final List<UUID>           finishOrder = new ArrayList<>();

    private BukkitTask tickTask;
    private BukkitTask timeLimitTask;
    private BossBar    bossBar;
    private StandardStartFlow startFlow;

    /** True from "GO!" until the game ends — the old RaceManager-gated listeners key off this. */
    private volatile boolean raceLive;
    private BukkitTask pollTask;

    public AdventureEscapeGameManagerV2(AdventureEscapePlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        racers.clear();
        finishOrder.clear();

        List<Location> grid = plugin.getArenaManager().getShuffledSpawns();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());

        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            Location spawn = grid.isEmpty() ? p.getLocation() : grid.get(i % grid.size());
            nl.kmc.game.api.GamePlayerUtil.safeTeleport(p, spawn);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);

            // Freezing is handled by StandardStartFlow (lasts through intro/flyover/countdown).
            racers.put(p.getUniqueId(), new RacerData(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.GREEN + "" + ChatColor.BOLD + "Adventure Escape",
                BarColor.GREEN, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        List<Player> parts = racers.keySet().stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        Location center = !grid.isEmpty() ? grid.get(0) : null;

        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§a§lADVENTURE ESCAPE"; }
                    @Override public String introSubtitle() { return "@ae.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@ae.tut.1", "@ae.tut.2", "@ae.tut.3", "@ae.tut.4");
                    }
                    @Override public Location flyoverCenter() { return center; }
                    @Override public void onFinished() { beginRace(); }
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

    private void beginRace() {
        raceLive = true;
        long now = System.currentTimeMillis();

        for (RacerData rd : racers.values()) {
            rd.markRaceStart(now);
            rd.startFirstLap(now);
            Player p = Bukkit.getPlayer(rd.getUuid());
            if (p == null) continue;
            // Only the boots show: invisible racers in team-coloured boots.
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            nl.kmc.game.api.FeetOnlyVisibility.apply(p, team != null ? team.getColor() : null);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    api.tr(p.getUniqueId(), "ae.go-sub"), 0, 40, 10);
        }

        bossBar.setColor(BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "🏁 RACE ACTIVE");

        if (plugin.getCheckpointManager() != null) plugin.getCheckpointManager().resetAllRacerState();
        if (plugin.getTrialKeyManager() != null) {
            plugin.getTrialKeyManager().onRaceStart(
                    racers.keySet().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList());
        }

        // Position poll every 2 ticks: finish / checkpoints / effect blocks also work for bots and teleports
        // (neither fires move events) and for generous effect-block overlap.
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!raceLive) return;
            for (UUID id : new ArrayList<>(racers.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) continue;
                plugin.getLineCrossListener().check(p, p.getLocation());
                plugin.getBlockStepListener().check(p);
            }
        }, 2L, 2L);

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long t = System.currentTimeMillis();
            racers.values().forEach(rd -> rd.tickUpdate(t));
            if (plugin.getCheckpointManager() != null)
                plugin.getCheckpointManager().tickCheckpointDetection(racers.keySet());
        }, 20L, 10L);

        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 600);
        if (maxDuration > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (getState().isRunning()) { tellKey("ae.time-up"); end(); }
            }, maxDuration * 20L);
        }
    }

    @Override
    protected void onGameEnd() {
        raceLive = false;
        if (pollTask != null) { pollTask.cancel(); pollTask = null; }
        for (UUID id : racers.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) nl.kmc.game.api.FeetOnlyVisibility.clear(p);
        }
        if (plugin.getTrialKeyManager() != null) plugin.getTrialKeyManager().onRaceEnd();
        if (plugin.getCheckpointManager() != null) plugin.getCheckpointManager().resetAllRacerState();
        plugin.getRaceScoreboard().cleanup();
        if (tickTask     != null) { tickTask.cancel();      tickTask      = null; }
        if (timeLimitTask != null) { timeLimitTask.cancel(); timeLimitTask = null; }
        if (bossBar       != null) { bossBar.removeAll();    bossBar       = null; }
        if (startFlow     != null) { startFlow.cancel();     startFlow     = null; }

        // Rank: finishers by time, then non-finishers by laps + progress
        List<RacerData> ranked = new ArrayList<>(racers.values());
        ranked.sort((a, b) -> {
            if (a.hasFinished() != b.hasFinished()) return a.hasFinished() ? -1 : 1;
            if (a.hasFinished()) return Long.compare(a.getTotalTimeMs(), b.getTotalTimeMs());
            if (a.getLapsCompleted() != b.getLapsCompleted())
                return Integer.compare(b.getLapsCompleted(), a.getLapsCompleted());
            return 0;
        });

        List<UUID> orderedUUIDs = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = ranked.isEmpty() ? null : ranked.get(0).getUuid();
        String mvpName = winnerDesc;

        for (int i = 0; i < ranked.size(); i++) {
            RacerData rd = ranked.get(i);
            orderedUUIDs.add(rd.getUuid());
            api.points().awardPlayerPlacement(rd.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(rd.getUuid(), rd.getName(), registration.getId(), i == 0);
        }

        returnToLobby();
        racers.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, orderedUUIDs);
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
        RacerData rd = racers.get(player.getUniqueId());
        if (rd != null) s.extra.put("laps", rd.getLapsCompleted());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage(api.tr(player.getUniqueId(), "game.state-restored"));
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        RacerData me = racers.get(id);
        if (me != null) {
            l.add(api.tr(id, "sb.ae.your-laps", me.getLapsCompleted()));
            l.add(me.hasFinished() ? api.tr(id, "sb.ae.finished") : api.tr(id, "sb.ae.busy"));
            l.add("");
        }
        l.add(api.tr(id, "sb.ae.top"));
        racers.values().stream()
                .sorted((a, b) -> b.getLapsCompleted() - a.getLapsCompleted())
                .limit(5)
                .forEach(r -> l.add(api.tr(id, "sb.ae.entry", r.getName(), r.getLapsCompleted())));
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Adventure Escape"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Adventure Escape arena not ready: " + plugin.getArenaManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isRaceLive() { return raceLive; }

    public List<UUID> getFinishOrder() { return Collections.unmodifiableList(finishOrder); }

    public void onPlayerCrossFinish(Player player) {
        if (!raceLive || !getState().isRunning()) return;
        RacerData rd = racers.get(player.getUniqueId());
        if (rd == null || rd.hasFinished()) return;

        long now = System.currentTimeMillis();
        rd.completeLap(now);
        int targetLaps = plugin.getArenaManager().getLaps();

        int lapPts = plugin.getConfig().getInt("points.per-lap", 50);
        api.points().givePoints(player.getUniqueId(), lapPts, PointAward.Reason.OBJECTIVE, registration.getId());

        if (rd.getLapsCompleted() >= targetLaps) {
            int placement = finishOrder.size() + 1;
            rd.markFinished(now, placement);
            finishOrder.add(rd.getUuid());

            int finishBonus = plugin.getConfig().getInt("points.finish-bonus", 150);
            api.points().givePoints(player.getUniqueId(), finishBonus, PointAward.Reason.OBJECTIVE, registration.getId());

            tellKey("ae.finished", player.getName(), placement, RacerData.formatMs(rd.getTotalTimeMs()));
            player.sendTitle(ChatColor.GOLD + "#" + placement,
                    api.tr(player.getUniqueId(), "ae.time", RacerData.formatMs(rd.getTotalTimeMs())), 10, 60, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.setGameMode(GameMode.SPECTATOR);

            if (finishOrder.size() == 1 && racers.size() > 1) {
                int grace = plugin.getConfig().getInt("game.finish-grace-seconds", 60);
                if (grace > 0) {
                    tellKey("ae.grace", grace);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (getState().isRunning()) { tellKey("ae.grace-over"); end(); }
                    }, grace * 20L);
                }
            }
            if (finishOrder.size() >= racers.size()) end();
        } else {
            tellKey("ae.lap", player.getName(), rd.getLapsCompleted(), targetLaps);
        }
    }

    public Map<UUID, RacerData> getRacers() { return Collections.unmodifiableMap(racers); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        racers.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            nl.kmc.game.api.GamePlayerUtil.teleportToLobby(p, lobby);
        });
    }
}
