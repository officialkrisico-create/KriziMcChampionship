package nl.kmc.tgttos.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.core.event.GameObjectiveEvent;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
    private boolean fogOfWarActive;    // this map's random "restricted visibility" twist

    // Team-finish-bonus bookkeeping for the current map (see points.team-finish-bonus).
    private final java.util.Map<String, Integer> teamRemaining = new HashMap<>();
    private int teamFinishRank;

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

        // Start line first: players are teleported to the first map NOW (frozen until GO), instead of standing
        // in the lobby until the countdown ends — a lobby that lies below the map's void floor made the first
        // move look like a "void fall".
        if (!rotation.isEmpty()) {
            List<Location> startSpawns = rotation.get(0).getStartSpawns();
            List<Player> everyone = new ArrayList<>(Bukkit.getOnlinePlayers());
            for (int i = 0; i < everyone.size(); i++) {
                Player p = everyone.get(i);
                if (!startSpawns.isEmpty()) {
                    nl.kmc.game.api.GamePlayerUtil.safeTeleport(p, startSlot(startSpawns, i));
                }
                p.setGameMode(GameMode.ADVENTURE);
                p.setHealth(20); p.setFoodLevel(20);
                nl.kmc.game.api.GamePlayerUtil.freezePlayer(p, 20 * 120);
                nl.kmc.game.api.GamePlayerUtil.freezeJump(p, 20 * 120);
            }
        }

        bossBar = Bukkit.createBossBar(ChatColor.YELLOW + "" + ChatColor.BOLD + "TGTTOS",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        int maps = mapsPerGame();
        tellKey("tgttos.intro", maps);
    }

    @Override
    protected void onGameStart() {
        // Flyover over the first map (auto-generated orbit, or a recorded arena-tgttos route), then GO.
        Map first = rotation.isEmpty() ? null : rotation.get(0);
        List<Location> sp = first != null ? first.getStartSpawns() : List.of();
        if (sp.isEmpty() || sp.get(0).getWorld() == null) { beginMap(); return; }
        double x = 0, y = 0, z = 0;
        for (Location l : sp) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        Location center = new Location(sp.get(0).getWorld(), x / sp.size(), y / sp.size(), z / sp.size());
        boolean played = api.cinematics().playArenaFlyover(registration.getId(),
                new ArrayList<>(Bukkit.getOnlinePlayers()), center,
                plugin.getConfig().getDouble("start-sequence.flyover-radius", 20.0),
                plugin.getConfig().getDouble("start-sequence.flyover-height", 0.0),
                () -> { if (getState().isRunning()) beginMap(); });
        if (!played) beginMap();
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
        for (Player v : Bukkit.getOnlinePlayers()) {
            java.util.UUID vid = v.getUniqueId();
            v.sendMessage("§8§m                                        ");
            v.sendMessage(api.tr(vid, "tgttos.final"));
            v.sendMessage("§8§m                                        ");
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                RunnerState rs = ranked.get(i);
                String medal = i == 0 ? "§6#1" : i == 1 ? "§7#2" : i == 2 ? "§c#3" : "§7#" + (i + 1);
                v.sendMessage("  " + medal + " §f" + rs.getName() + " §8— "
                        + api.tr(vid, "tgttos.final-line", rs.getTotalPoints(), rs.getMapsWon(), rs.getDnfCount()));
            }
            v.sendMessage("§8§m                                        ");
        }
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
        player.sendMessage(api.tr(player.getUniqueId(), "game.state-restored"));
    }

    @Override
    protected java.util.List<String> getScoreboardLines(org.bukkit.entity.Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.tgttos.round", Math.max(1, currentMap), mapsPerGame()));
        if (mapPhase == MapPhase.COUNTDOWN) l.add(api.tr(id, "sb.tgttos.time", Math.max(0, countdownRemaining)));
        l.add(api.tr(id, "tgttos.sb.finished", mapFinishCounter, runners.size()));
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
        long elapsedMs = rs.getRoundElapsedMillis();
        var curMap = getCurrentMap();
        api.games().recordSector(registration.getId(), curMap != null ? curMap.getDisplayName() : "map " + currentMap,
                0, player.getUniqueId(), player.getName(), elapsedMs);
        api.games().recordHighlight(player.getUniqueId(), "racer", Math.max(1, runners.size() - placement + 1),
                registration.getId());
        api.games().announceFinish(player.getUniqueId(), placement, runners.size(), registration.getId());

        String medal = placement == 1 ? "§6🥇" : placement == 2 ? "§7🥈" : placement == 3 ? "§c🥉" : "§7#" + placement;
        for (Player v : Bukkit.getOnlinePlayers()) {
            v.sendMessage(medal + " " + api.tr(v.getUniqueId(), "tgttos.finished", player.getName(),
                    formatTime(elapsedMs), pts));
        }
        player.sendTitle(medal, "§7" + formatTime(elapsedMs), 5, 40, 10);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        player.setGameMode(GameMode.SPECTATOR);

        if (placement == 1) fireObjective(player, GameObjectiveEvent.Type.TGTTOS_FIRST_FINISH, elapsedMs);
        applyTeamFinishBonus(player);

        // Whole field done → end the map immediately.
        if (activeRacers() == 0) { endMap(); return; }
        // Half the field done → kick off the dynamic-finish countdown.
        if (mapPhase == MapPhase.RACING && mapFinishCounter >= finishThreshold) startFinishCountdown(true);
    }

    /** Checkpoints only count as progress going forward — never downgrade a runner's respawn point. */
    public void onPlayerReachCheckpoint(Player player, int index) {
        if (!getState().isRunning()) return;
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null || rs.isCurrentRoundFinished() || index <= rs.getLastCheckpointIndex()) return;
        rs.setLastCheckpointIndex(index);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
        fireObjective(player, GameObjectiveEvent.Type.CHECKPOINT_HIT);
    }

    /** No elimination — fell below the map's void floor, respawn at the last checkpoint (or a start spawn). */
    public void onPlayerFellInVoid(Player player) {
        if (!getState().isRunning()) return;
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null || rs.isCurrentRoundFinished()) return;
        Map map = getCurrentMap();
        if (map == null) return;

        Location dest = map.respawnPointFor(rs.getLastCheckpointIndex(), map.getStartSpawns(),
                Math.abs(player.getUniqueId().hashCode()));
        if (dest == null) return;
        rs.recordDeath();
        nl.kmc.game.api.GamePlayerUtil.safeTeleport(player, dest);
        player.setHealth(20); player.setFoodLevel(20);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
        java.util.UUID pid = player.getUniqueId();
        player.sendActionBar(net.kyori.adventure.text.Component.text(api.tr(pid, "tgttos.void",
                rs.getLastCheckpointIndex() >= 0
                        ? api.tr(pid, "tgttos.void-cp", rs.getLastCheckpointIndex() + 1)
                        : api.tr(pid, "tgttos.void-start"))));
    }

    /** Once every member of a team currently racing this map has finished, the team gets a rank-based bonus. */
    private void applyTeamFinishBonus(Player finisher) {
        String teamId = api.teams().getTeamByPlayer(finisher.getUniqueId()).map(t -> t.getId()).orElse(null);
        if (teamId == null || !teamRemaining.containsKey(teamId)) return;
        int remaining = teamRemaining.merge(teamId, -1, Integer::sum);
        if (remaining > 0) return;

        int rank = ++teamFinishRank;
        var bonusSection = plugin.getConfig().getConfigurationSection("points.team-finish-bonus");
        int bonus = bonusSection != null
                ? bonusSection.getInt(String.valueOf(rank), bonusSection.getInt("default", 0))
                : 0;
        if (bonus <= 0) return;

        // a team bonus goes to the team (one award), not to the personal points of its runners
        api.points().giveTeamPoints(teamId, bonus, PointAward.Reason.TEAM_PLACEMENT, registration.getId());
        for (RunnerState rs : runners.values()) {
            if (!teamId.equals(api.teams().getTeamByPlayer(rs.getUuid()).map(t -> t.getId()).orElse(null))) continue;
            rs.giveTeamBonus(bonus);
        }
        tellKey("tgttos.team-bonus", bonus);
    }

    private int mapPoints(int placement) {
        var section = plugin.getConfig().getConfigurationSection("points.round-placement");
        if (section == null) {
            int[] tiers = {100, 75, 60, 50, 40, 30, 25, 20, 15, 10};
            return placement <= tiers.length ? tiers[placement - 1] : 5;
        }
        int fallback = section.getInt("default", 0);
        return section.getInt(String.valueOf(placement), fallback);
    }

    private static String formatTime(long ms) {
        long totalCentis = ms / 10;
        long minutes = totalCentis / 6000;
        long seconds = (totalCentis / 100) % 60;
        long centis  = totalCentis % 100;
        return (minutes > 0 ? minutes + ":" + String.format("%02d", seconds) : String.valueOf(seconds))
                + "." + String.format("%02d", centis);
    }

    public java.util.Map<UUID, RunnerState> getRunnersMap() { return Collections.unmodifiableMap(runners); }

    // ── Round management ──────────────────────────────────────────────────────

    /**
     * The start place of player {@code i}: every spawn is used first, and players beyond the number of spawns
     * stand beside the spawns instead of inside whoever is there, so the whole field starts side by side.
     */
    private static Location startSlot(List<Location> spawns, int i) {
        Location base = spawns.get(i % spawns.size());
        int layer = i / spawns.size();
        if (layer == 0) return base;
        double step = 1.1 * ((layer + 1) / 2);
        double side = layer % 2 == 1 ? step : -step;
        double r = Math.toRadians(base.getYaw());
        return base.clone().add(Math.cos(r) * side, 0, Math.sin(r) * side);
    }

    private void beginMap() {
        currentMap++;
        if (currentMap > mapsPerGame()) { end(); return; }

        mapPhase           = MapPhase.RACING;
        mapFinishCounter   = 0;
        countdownRemaining = 0;
        mapElapsed         = 0;
        teamFinishRank     = 0;
        runners.values().forEach(RunnerState::startRound);

        Map map = getCurrentMap();
        if (map == null) { end(); return; }

        teamRemaining.clear();
        for (RunnerState rs : runners.values()) {
            api.teams().getTeamByPlayer(rs.getUuid()).ifPresent(t ->
                    teamRemaining.merge(t.getId(), 1, Integer::sum));
        }

        List<Location> spawns = map.getStartSpawns();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            Location dest = spawns.isEmpty() ? p.getLocation() : startSlot(spawns, i);
            nl.kmc.game.api.GamePlayerUtil.safeTeleport(p, dest);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20); p.setFoodLevel(20);
            nl.kmc.game.api.GamePlayerUtil.unfreezePlayer(p);
        }

        // Threshold = 50% of the players actually racing this map.
        int pct = plugin.getConfig().getInt("game.finish-threshold-percent", 50);
        finishThreshold = Math.max(1, (int) Math.ceil(runners.size() * pct / 100.0));

        tellKey("tgttos.map", currentMap, mapsPerGame(), map.getDisplayName());
        tellKey("tgttos.map-rule", minSec(countdownSeconds()), pct);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.2f);

        fogOfWarActive = Math.random() < plugin.getConfig().getDouble("game.fog-of-war-chance", 0.15);
        if (fogOfWarActive) {
            tellKey("tgttos.fog");
            for (Player p : online) {
                p.sendTitle("§8§l🌫 FOG OF WAR", api.tr(p.getUniqueId(), "tgttos.fog-sub"), 8, 50, 12);
                applyFogOfWar(p);
            }
        }

        mapTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::mapTick, 20L, 20L);
    }

    /** One-second tick: drive the threshold check, countdown, and live display. */
    private void mapTick() {
        if (!getState().isRunning()) return;
        mapElapsed++;

        if (fogOfWarActive) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                RunnerState rs = runners.get(p.getUniqueId());
                if (rs != null && !rs.isCurrentRoundFinished()) applyFogOfWar(p);
            }
        }
        applyRubberBanding();

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
            tellKey("tgttos.half");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(api.tr(p.getUniqueId(), "tgttos.half-title"),
                        api.tr(p.getUniqueId(), "tgttos.half-sub", minSec(countdownRemaining)), 8, 50, 12);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            }
        }
        tellKey("tgttos.countdown", minSec(countdownRemaining));
        updateProgressDisplay();
    }

    /** Countdown hit zero — anyone still racing is a DNF. */
    private void forceEndMap() {
        runners.values().forEach(rs -> {
            if (rs.isCurrentRoundFinished()) return;
            rs.recordDnf(currentMap);
            Player p = Bukkit.getPlayer(rs.getUuid());
            if (p != null) {
                p.sendTitle("§c§lDNF", api.tr(p.getUniqueId(), "tgttos.dnf-sub"), 5, 45, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                p.setGameMode(GameMode.SPECTATOR);
            }
            tellKey("tgttos.dnf", rs.getName());
        });
        endMap();
    }

    /** Cleans up the current map and advances (or ends the game after the last map). */
    private void endMap() {
        if (mapTickTask != null) { mapTickTask.cancel(); mapTickTask = null; }
        if (currentMap >= mapsPerGame()) { end(); return; }
        tellKey("tgttos.next-map");
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
        List<java.util.Map.Entry<Player, Double>> raceOrder = liveRaceOrder();

        if (bossBar != null) {
            bossBar.setColor(cd ? BarColor.RED : BarColor.YELLOW);
            if (cd) bossBar.setProgress(Math.max(0, Math.min(1, countdownRemaining / (double) Math.max(1, countdownSeconds()))));
            else bossBar.setProgress(Math.max(0, Math.min(1, finished / (double) Math.max(1, runners.size()))));
            String leaderTag = raceOrder.isEmpty() ? "" : " §8| §6🏁 " + raceOrder.get(0).getKey().getName();
            bossBar.setTitle("§e§lTGTTOS §8| §7Map §e" + currentMap + "/" + mapsPerGame()
                    + " §8| §a✔ " + finished + "/" + runners.size()
                    + " §8| §c🏃 " + racing + (cd ? " §8| §c⏱ " + minSec(countdownRemaining) : "") + leaderTag);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            String hud = api.tr(p.getUniqueId(), "tgttos.hud", finished, runners.size(), racing)
                    + (cd ? " §8| §c⏱ " + minSec(countdownRemaining) : api.tr(p.getUniqueId(), "tgttos.hud-race"));
            p.sendActionBar(net.kyori.adventure.text.Component.text(hud + raceGapSuffix(p, raceOrder)));
        }
    }

    /** Per-player "you're closest / X blocks behind the leader" tag for the action bar. */
    private String raceGapSuffix(Player viewer, List<java.util.Map.Entry<Player, Double>> raceOrder) {
        if (raceOrder.isEmpty()) return "";
        if (raceOrder.get(0).getKey().equals(viewer)) return " §8| " + api.tr(viewer.getUniqueId(), "tgttos.leader");
        for (var entry : raceOrder) {
            if (entry.getKey().equals(viewer)) {
                double gap = entry.getValue() - raceOrder.get(0).getValue();
                return " §8| §7-" + Math.round(gap) + "m";
            }
        }
        return "";
    }

    // ── Fog of War / rubber-banding ────────────────────────────────────────────

    /** Short, refreshed Blindness — players only make out what's right in front of them. */
    private void applyFogOfWar(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, false));
    }

    /**
     * Gives whoever is currently furthest from the finish a brief Speed II —
     * recomputed every tick, so it always follows whoever is actually last
     * (never sticks to a player who has since caught up).
     */
    private void applyRubberBanding() {
        if (!plugin.getConfig().getBoolean("game.rubber-banding", true)) return;
        List<java.util.Map.Entry<Player, Double>> order = liveRaceOrder();
        // Needs a real field to matter — with 1-2 racers left it'd just flip-flop uselessly.
        if (order.size() < 3) return;
        Player last = order.get(order.size() - 1).getKey();
        last.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, true, false, false));
    }

    /**
     * Still-racing players on the current map with their live distance (in
     * blocks) to the finish centre, closest first. Shared by rubber-banding
     * and the live race-position display.
     */
    private List<java.util.Map.Entry<Player, Double>> liveRaceOrder() {
        Map map = getCurrentMap();
        if (map == null || map.getFinishPos1() == null || map.getFinishPos2() == null) return List.of();
        Location f1 = map.getFinishPos1(), f2 = map.getFinishPos2();
        if (f1.getWorld() == null) return List.of();
        Location finishCenter = new Location(f1.getWorld(),
                (f1.getX() + f2.getX()) / 2.0, (f1.getY() + f2.getY()) / 2.0, (f1.getZ() + f2.getZ()) / 2.0);

        List<java.util.Map.Entry<Player, Double>> out = new ArrayList<>();
        for (var entry : runners.entrySet()) {
            if (entry.getValue().isCurrentRoundFinished()) continue;
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null && p.getWorld().equals(finishCenter.getWorld()))
                out.add(java.util.Map.entry(p, p.getLocation().distance(finishCenter)));
        }
        out.sort(java.util.Map.Entry.comparingByValue());
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void fireObjective(Player p, GameObjectiveEvent.Type type) { fireObjective(p, type, 0); }

    private void fireObjective(Player p, GameObjectiveEvent.Type type, long elapsedMs) {
        try { new GameObjectiveEvent(p, registration.getId(), type, elapsedMs).callEvent(); }
        catch (Throwable t) { plugin.getLogger().warning("GameObjectiveEvent failed: " + t); }
    }

    private int mapsPerGame()      { return Math.max(1, plugin.getConfig().getInt("game.maps-per-game", plugin.getConfig().getInt("game.total-rounds", 5))); }
    private int countdownSeconds() { return Math.max(5, plugin.getConfig().getInt("game.finish-countdown-seconds", 120)); }
    private long activeRacers()    { return runners.values().stream().filter(r -> !r.isCurrentRoundFinished()).count(); }
    private static String minSec(int s) { return (s / 60) + ":" + String.format("%02d", s % 60); }

    private void returnToLobby() {
        fogOfWarActive = false;
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        runners.keySet().forEach(uuid -> {
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
