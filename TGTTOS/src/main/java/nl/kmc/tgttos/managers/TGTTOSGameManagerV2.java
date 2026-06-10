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

    public enum MapPhase { RACING, COUNTDOWN }

    private int  currentMap;          // 1..mapsPerGame
    private int  mapFinishCounter;    // placement counter for the current map
    private int  countdownRemaining;  // seconds left in the dynamic-finish countdown
    private int  mapElapsed;          // seconds the current map has been running
    private int  finishThreshold;     // # finishers that triggers the countdown (50%)
    private MapPhase mapPhase = MapPhase.RACING;

    private BukkitTask mapTickTask;
    private BossBar    bossBar;

    public TGTTOSGameManagerV2(TGTTOSPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        runners.clear();
        rotation.clear();
        currentMap = 0;

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
        int maps = mapsPerGame();
        broadcast("§e§l[TGTTOS] §e" + maps + " maps — verzamel plaatsingen over alle maps!");
    }

    @Override
    protected void onGameStart() {
        beginMap();
    }

    @Override
    protected void onGameEnd() {
        if (mapTickTask != null) { mapTickTask.cancel(); mapTickTask = null; }
        if (bossBar     != null) { bossBar.removeAll();  bossBar     = null; }

        // Final standings after all maps: rank by accumulated map score, tiebreak
        // on average placement (lower is better), then maps won.
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> {
            if (a.getTotalPoints() != b.getTotalPoints()) return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
            double pa = a.getAveragePlacement() == 0 ? 999 : a.getAveragePlacement();
            double pb = b.getAveragePlacement() == 0 ? 999 : b.getAveragePlacement();
            if (pa != pb) return Double.compare(pa, pb);
            return Integer.compare(b.getMapsWon(), a.getMapsWon());
        });

        broadcastFinalStandings(ranked);

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = ranked.isEmpty() ? null : ranked.get(0).getUuid();
        String mvpName = ranked.isEmpty() ? null : ranked.get(0).getName();

        // KMC points are awarded ONLY here, once the full 5-map set has concluded.
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

    private void broadcastFinalStandings(List<RunnerState> ranked) {
        broadcast("§8§m                                        ");
        broadcast("        §e§l🏁 TGTTOS — EINDUITSLAG");
        broadcast("§8§m                                        ");
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            RunnerState rs = ranked.get(i);
            String medal = i == 0 ? "§6#1" : i == 1 ? "§7#2" : i == 2 ? "§c#3" : "§7#" + (i + 1);
            broadcast("  " + medal + " §f" + rs.getName() + " §8— §e" + rs.getTotalPoints() + " pts §8("
                    + rs.getMapsWon() + " maps gewonnen, " + rs.getDnfCount() + " DNF)");
        }
        broadcast("§8§m                                        ");
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
    protected java.util.List<String> getScoreboardLines(org.bukkit.entity.Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.tgttos.round", Math.max(1, currentMap), mapsPerGame()));
        if (mapPhase == MapPhase.COUNTDOWN) l.add(api.tr(id, "sb.tgttos.time", Math.max(0, countdownRemaining)));
        l.add("§7Gefinisht §a" + mapFinishCounter + "§7/§a" + runners.size());
        l.add(api.tr(id, "sb.tgttos.running", activeRacers()));
        RunnerState me = runners.get(id);
        if (me != null) {
            l.add("");
            l.add(me.isCurrentRoundFinished() ? api.tr(id, "sb.tgttos.you-finished") : api.tr(id, "sb.tgttos.you-running"));
            l.add(api.tr(id, "sb.tgttos.rounds-done", me.getMapsFinished()));
            l.add(api.tr(id, "sb.common.points", me.getTotalPoints()));
        }
        return l;
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

        // Finishers DURING the countdown still get their placement + map points.
        int placement = ++mapFinishCounter;
        int pts = mapPoints(placement);
        rs.finishRound(currentMap, placement, pts); // internal map score only — KMC points come at game end

        String medal = placement == 1 ? "§6🥇" : placement == 2 ? "§7🥈" : placement == 3 ? "§c🥉" : "§7#" + placement;
        broadcast(medal + " §e" + player.getName() + " §7finished! §8(§e+" + pts + "§8 pts)");
        player.sendTitle(medal, "§7Mooie run!", 5, 40, 10);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        player.setGameMode(GameMode.SPECTATOR);

        // Whole field done → end the map immediately.
        if (activeRacers() == 0) { endMap(); return; }
        // Half the field done → kick off the dynamic-finish countdown.
        if (mapPhase == MapPhase.RACING && mapFinishCounter >= finishThreshold) startFinishCountdown(true);
    }

    private int mapPoints(int placement) {
        int[] tiers = {100, 75, 60, 50, 40, 30, 25, 20, 15, 10};
        return placement <= tiers.length ? tiers[placement - 1] : 5;
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

    private void beginMap() {
        currentMap++;
        if (currentMap > mapsPerGame()) { end(); return; }

        mapPhase           = MapPhase.RACING;
        mapFinishCounter   = 0;
        countdownRemaining = 0;
        mapElapsed         = 0;
        runners.values().forEach(RunnerState::startRound);

        Map map = getCurrentMap();
        if (map == null) { end(); return; }

        List<Location> spawns = map.getStartSpawns();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            Location dest = spawns.isEmpty() ? p.getLocation() : spawns.get(i % spawns.size());
            nl.kmc.game.api.GamePlayerUtil.safeTeleport(p, dest);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20); p.setFoodLevel(20);
        }

        // Threshold = 50% of the players actually racing this map.
        int pct = plugin.getConfig().getInt("game.finish-threshold-percent", 50);
        finishThreshold = Math.max(1, (int) Math.ceil(runners.size() * pct / 100.0));

        broadcast("§e§l[TGTTOS] §eMap §6" + currentMap + "§e/§6" + mapsPerGame() + " §7— §b" + map.getDisplayName());
        broadcast("§7De map eindigt §e" + minSec(countdownSeconds()) + " §7nadat §e" + pct + "% §7gefinisht is.");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.2f);

        mapTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::mapTick, 20L, 20L);
    }

    /** One-second tick: drive the threshold check, countdown, and live display. */
    private void mapTick() {
        if (!getState().isRunning()) return;
        mapElapsed++;

        if (mapPhase == MapPhase.RACING) {
            int maxSec = plugin.getConfig().getInt("game.max-map-seconds", 300);
            if (mapFinishCounter >= finishThreshold)          startFinishCountdown(true);
            else if (maxSec > 0 && mapElapsed >= maxSec)      startFinishCountdown(false);
        } else { // COUNTDOWN
            countdownRemaining--;
            if (countdownRemaining <= 0) { forceEndMap(); return; }
        }
        updateProgressDisplay();
    }

    /** Begins the dynamic-finish countdown (the 50% trigger or the safety cap). */
    private void startFinishCountdown(boolean halfReached) {
        if (mapPhase == MapPhase.COUNTDOWN) return;
        mapPhase = MapPhase.COUNTDOWN;
        countdownRemaining = countdownSeconds();

        if (halfReached) {
            broadcast("§e§l⚡ HELFT VAN HET VELD IS GEFINISHT!");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§e§lHELFT GEFINISHT", "§cResterende spelers: " + minSec(countdownRemaining), 8, 50, 12);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            }
        }
        broadcast("§e§l[TGTTOS] §cResterende spelers hebben §e" + minSec(countdownRemaining) + " §com te finishen!");
        updateProgressDisplay();
    }

    /** Countdown hit zero — anyone still racing is a DNF. */
    private void forceEndMap() {
        runners.values().forEach(rs -> {
            if (rs.isCurrentRoundFinished()) return;
            rs.recordDnf(currentMap);
            Player p = Bukkit.getPlayer(rs.getUuid());
            if (p != null) {
                p.sendTitle("§c§lDNF", "§7Te laat — geen punten", 5, 45, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                p.setGameMode(GameMode.SPECTATOR);
            }
            broadcast("§c✗ §7" + rs.getName() + " §c— DNF");
        });
        endMap();
    }

    /** Cleans up the current map and advances (or ends the game after the last map). */
    private void endMap() {
        if (mapTickTask != null) { mapTickTask.cancel(); mapTickTask = null; }
        if (currentMap >= mapsPerGame()) { end(); return; }
        broadcast("§7Volgende map start zo...");
        Bukkit.getScheduler().runTaskLater(plugin, this::beginMap, 100L);
    }

    public Map getCurrentMap() {
        return rotation.isEmpty() ? null : rotation.get((currentMap - 1) % rotation.size());
    }

    /** Live progress: boss bar + action bar (finished / racing / time), for players + spectators. */
    private void updateProgressDisplay() {
        long finished = mapFinishCounter;
        long racing   = activeRacers();
        boolean cd    = mapPhase == MapPhase.COUNTDOWN;

        if (bossBar != null) {
            bossBar.setColor(cd ? BarColor.RED : BarColor.YELLOW);
            if (cd) bossBar.setProgress(Math.max(0, Math.min(1, countdownRemaining / (double) Math.max(1, countdownSeconds()))));
            else bossBar.setProgress(Math.max(0, Math.min(1, finished / (double) Math.max(1, runners.size()))));
            bossBar.setTitle("§e§lTGTTOS §8| §7Map §e" + currentMap + "/" + mapsPerGame()
                    + " §8| §aGefinisht " + finished + "/" + runners.size()
                    + " §8| §cRacet " + racing + (cd ? " §8| §c⏱ " + minSec(countdownRemaining) : ""));
        }

        String hud = "§eGefinisht §a" + finished + "§7/§a" + runners.size()
                + " §8| §eRacet §c" + racing + (cd ? " §8| §c⏱ " + minSec(countdownRemaining) : " §8| §7race naar de finish!");
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendActionBar(net.kyori.adventure.text.Component.text(hud));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int mapsPerGame()      { return Math.max(1, plugin.getConfig().getInt("game.maps-per-game", plugin.getConfig().getInt("game.total-rounds", 5))); }
    private int countdownSeconds() { return Math.max(5, plugin.getConfig().getInt("game.finish-countdown-seconds", 120)); }
    private long activeRacers()    { return runners.values().stream().filter(r -> !r.isCurrentRoundFinished()).count(); }
    private static String minSec(int s) { return (s / 60) + ":" + String.format("%02d", s % 60); }

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
