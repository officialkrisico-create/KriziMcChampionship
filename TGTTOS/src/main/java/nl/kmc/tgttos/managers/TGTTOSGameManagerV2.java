package nl.kmc.tgttos.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.tgttos.TGTTOSPlugin;
import nl.kmc.tgttos.models.Map;
import nl.kmc.tgttos.models.RunnerState;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 TGTTOS (That Guy Throws The Sheep) manager.
 *
 * <p>N rounds on rotating maps. Players race from start to finish.
 * Points awarded per round placement. No elimination — all players finish every round.
 */
public final class TGTTOSGameManagerV2 extends BaseGameManager {

    private final TGTTOSPlugin plugin;

    private final java.util.Map<UUID, RunnerState> runners = new LinkedHashMap<>();
    private final List<Map> rotation = new ArrayList<>();

    private int  currentRound;
    private int  roundFinishCounter;
    private int  remainingRoundSeconds;

    private BukkitTask roundTimerTask;
    private BossBar    bossBar;

    public TGTTOSGameManagerV2(TGTTOSPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        runners.clear();
        rotation.clear();
        currentRound = 0;

        // Build shuffled rotation from configured maps
        rotation.addAll(plugin.getMapManager().getMaps().values());
        Collections.shuffle(rotation);

        for (Player p : Bukkit.getOnlinePlayers()) {
            runners.put(p.getUniqueId(), new RunnerState(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.YELLOW + "" + ChatColor.BOLD + "TGTTOS",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§e§l[TGTTOS] §eGet to the end of each map!");
    }

    @Override
    protected void onGameStart() {
        beginRound();
    }

    @Override
    protected void onGameEnd() {
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (bossBar        != null) { bossBar.removeAll();     bossBar        = null; }

        // Rank by total score
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> Integer.compare(b.getTotalPoints(), a.getTotalPoints()));

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = ranked.isEmpty() ? null : ranked.get(0).getUuid();
        String mvpName = ranked.isEmpty() ? null : ranked.get(0).getName();

        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            finishOrder.add(rs.getUuid());
            api.points().awardPlayerPlacement(rs.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(rs.getUuid(), rs.getName(), registration.getId(), i == 0);
        }

        returnToLobby();
        runners.clear();
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
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs != null) s.extra.put("totalScore", rs.getTotalPoints());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§e[TGTTOS] State restored!");
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "TGTTOS"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (plugin.getMapManager().getMaps().isEmpty())
                    r.addError("No TGTTOS maps configured.");
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void onPlayerReachFinish(Player player) {
        if (!getState().isRunning()) return;
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null || rs.isCurrentRoundFinished()) return;

        int placement = ++roundFinishCounter;
        int[] pointsPerPlace = {100, 75, 60, 50, 40, 30, 25, 20, 15, 10};
        int pts = placement <= pointsPerPlace.length ? pointsPerPlace[placement - 1] : 5;
        api.points().givePoints(player.getUniqueId(), pts, PointAward.Reason.PLACEMENT, registration.getId());
        rs.finishRound(currentRound, placement, pts);

        String medal = placement == 1 ? "§6🥇" : placement == 2 ? "§7🥈" : placement == 3 ? "§c🥉" : "§7#" + placement;
        broadcast(medal + " §e" + player.getName() + " §7finished! §8(§e+" + pts + "§8 pts)");
        player.sendTitle(medal, "§7Great run!", 5, 40, 10);
        player.setGameMode(GameMode.SPECTATOR);

        long remaining = runners.values().stream().filter(r -> !r.isCurrentRoundFinished()).count();
        if (remaining == 0) advanceToNextRound();
    }

    public void onPlayerDeath(Player player) {
        // No elimination — teleport back to a start spawn
        Map map = getCurrentMap();
        if (map == null) return;
        List<Location> spawns = map.getStartSpawns();
        if (!spawns.isEmpty()) {
            player.teleport(spawns.get((int)(Math.random() * spawns.size())));
            player.setHealth(20); player.setFoodLevel(20);
        }
    }

    public java.util.Map<UUID, RunnerState> getRunnersMap() { return Collections.unmodifiableMap(runners); }

    // ── Round management ──────────────────────────────────────────────────────

    private void beginRound() {
        currentRound++;
        if (currentRound > plugin.getConfig().getInt("game.total-rounds", 3)) {
            end();
            return;
        }

        roundFinishCounter = 0;
        runners.values().forEach(RunnerState::startRound);

        Map map = getCurrentMap();
        if (map == null) { end(); return; }

        List<Location> spawns = map.getStartSpawns();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            Location dest = spawns.isEmpty() ? p.getLocation() : spawns.get(i % spawns.size());
            p.teleport(dest);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20); p.setFoodLevel(20);
        }

        remainingRoundSeconds = plugin.getConfig().getInt("game.round-duration-seconds", 90);
        broadcast("§e§l[TGTTOS] §eRound §6" + currentRound + "§e — map: §b" + map.getDisplayName());

        if (bossBar != null) updateBossBar();

        roundTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingRoundSeconds--;
            updateBossBar();
            if (remainingRoundSeconds <= 0) advanceToNextRound();
        }, 20L, 20L);
    }

    private void advanceToNextRound() {
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }

        // Award any unfinished players a consolation score
        runners.values().stream().filter(rs -> !rs.isCurrentRoundFinished()).forEach(rs -> {
            rs.finishRound(currentRound, ++roundFinishCounter, 0);
        });

        int nextRound = currentRound + 1;
        int totalRounds = plugin.getConfig().getInt("game.total-rounds", 3);
        if (nextRound > totalRounds) {
            end();
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, this::beginRound, 80L);
        }
    }

    public Map getCurrentMap() {
        int idx = (currentRound - 1) % Math.max(1, rotation.size());
        return rotation.isEmpty() ? null : rotation.get(idx);
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long finished = runners.values().stream().filter(RunnerState::isCurrentRoundFinished).count();
        int totalRounds = plugin.getConfig().getInt("game.total-rounds", 3);
        bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + "TGTTOS §8| §eRound " + currentRound
                + "/" + totalRounds + " §8| §e" + finished + "/" + runners.size()
                + " finished §8| §b" + remainingRoundSeconds + "s");
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        runners.keySet().forEach(uuid -> {
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
