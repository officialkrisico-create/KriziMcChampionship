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
            p.teleport(spawn);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);

            int countdownSec = plugin.getConfig().getInt("game.countdown-seconds", 10);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, countdownSec * 20, 255, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, countdownSec * 20, 128, true, false, false));

            racers.put(p.getUniqueId(), new RacerData(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.GREEN + "" + ChatColor.BOLD + "Adventure Escape",
                BarColor.GREEN, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§a§l[Adventure Escape] §eRace to the finish! Complete all laps!");
    }

    @Override
    protected void onGameStart() {
        long now = System.currentTimeMillis();

        for (RacerData rd : racers.values()) {
            rd.markRaceStart(now);
            rd.startFirstLap(now);
            Player p = Bukkit.getPlayer(rd.getUuid());
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.JUMP_BOOST);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Race is live!", 0, 40, 10);
        }

        bossBar.setColor(BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "🏁 RACE ACTIVE");

        if (plugin.getCheckpointManager() != null) plugin.getCheckpointManager().resetAllRacerState();
        if (plugin.getTrialKeyManager() != null) {
            plugin.getTrialKeyManager().onRaceStart(
                    racers.keySet().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList());
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long t = System.currentTimeMillis();
            racers.values().forEach(rd -> rd.tickUpdate(t));
            plugin.getRaceScoreboard().refresh();
            if (plugin.getCheckpointManager() != null)
                plugin.getCheckpointManager().tickCheckpointDetection(racers.keySet());
        }, 20L, 10L);

        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 600);
        if (maxDuration > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (getState().isRunning()) { broadcast("§c[Adventure Escape] §eTime's up!"); end(); }
            }, maxDuration * 20L);
        }
    }

    @Override
    protected void onGameEnd() {
        if (tickTask      != null) { tickTask.cancel();      tickTask      = null; }
        if (timeLimitTask != null) { timeLimitTask.cancel(); timeLimitTask = null; }
        if (bossBar       != null) { bossBar.removeAll();    bossBar       = null; }

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
        player.sendMessage("§a[Adventure Escape] State restored!");
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

    public void onPlayerCrossFinish(Player player) {
        if (!getState().isRunning()) return;
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

            broadcast("§6[Adventure Escape] §e" + player.getName() + " §bfinished #" + placement
                    + " §7(" + RacerData.formatMs(rd.getTotalTimeMs()) + ")");
            player.sendTitle(ChatColor.GOLD + "#" + placement,
                    ChatColor.YELLOW + "Time: " + RacerData.formatMs(rd.getTotalTimeMs()), 10, 60, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.setGameMode(GameMode.SPECTATOR);

            if (finishOrder.size() >= racers.size()) end();
        } else {
            broadcast("§6[Adventure Escape] §e" + player.getName() + " §7completed lap §6"
                    + rd.getLapsCompleted() + "/" + targetLaps);
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
            if (lobby != null) p.teleport(lobby);
        });
    }
}
