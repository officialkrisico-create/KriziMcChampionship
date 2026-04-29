package nl.kmc.tgttos.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.tgttos.TGTTOSPlugin;
import nl.kmc.tgttos.models.Map;
import nl.kmc.tgttos.models.RunnerState;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.models.KMCTeam;
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
 * TGTTOS round orchestrator.
 *
 * <p>Game = N rounds (default 3). Each round:
 * <ol>
 *   <li>Pick the next map from the shuffled rotation</li>
 *   <li>Teleport all players to that map's start spawns</li>
 *   <li>Freeze with Slowness 255 + Jump Boost 128 for the countdown</li>
 *   <li>Release — players race to the finish region</li>
 *   <li>Each player who enters the finish gets a placement (1st, 2nd, ...)</li>
 *   <li>Round ends when all alive finish OR round timer expires</li>
 *   <li>Brief intermission, then next round</li>
 * </ol>
 *
 * <p>Death = TP back to a random start spawn for the same map (no
 * elimination — they can still finish, just later).
 *
 * <p>States: IDLE → COUNTDOWN → ROUND_ACTIVE → INTERMISSION → ENDED
 */
public class GameManager {

    public enum State { IDLE, COUNTDOWN, ROUND_ACTIVE, INTERMISSION, ENDED }

    public static final String GAME_ID = "tgttos";

    private final TGTTOSPlugin plugin;
    private State state = State.IDLE;

    private final java.util.Map<UUID, RunnerState> runners = new LinkedHashMap<>();
    private final List<Map>  rotation     = new ArrayList<>();
    private int  currentRound;
    private int  roundFinishCounter;
    private long roundStartMs;
    private int  remainingRoundSeconds;

    /** Tracks which teams have fully finished this round (in order). */
    private final List<String> teamFinishOrderThisRound = new ArrayList<>();

    private BukkitTask countdownTask;
    private BukkitTask roundTimerTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;

    private int countdownSeconds;

    public GameManager(TGTTOSPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State getState() { return state; }
    public boolean isActive() {
        return state == State.ROUND_ACTIVE || state == State.INTERMISSION
            || state == State.COUNTDOWN;
    }
    public boolean isRoundActive() { return state == State.ROUND_ACTIVE; }
    public RunnerState get(UUID uuid) { return runners.get(uuid); }
    public java.util.Map<UUID, RunnerState> getRunners() {
        return Collections.unmodifiableMap(runners);
    }
    public Map getCurrentMap() {
        if (currentRound < 1 || currentRound > rotation.size()) return null;
        return rotation.get(currentRound - 1);
    }

    private PotionEffectType slow() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("slowness")); }
        catch (Exception e) { return null; }
    }
    private PotionEffectType jumpBoost() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public String startGame() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (plugin.getMapManager().getMaps().isEmpty()) return "Geen maps geconfigureerd.";

        int roundCount = plugin.getConfig().getInt("game.round-count", 3);

        runners.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            runners.put(p.getUniqueId(), new RunnerState(p.getUniqueId(), p.getName()));
        }
        if (runners.isEmpty()) return "Geen spelers online.";

        // Pick map rotation
        rotation.clear();
        rotation.addAll(plugin.getMapManager().pickRandom(roundCount));
        if (rotation.isEmpty()) return "Geen maps geschikt voor rotatie.";

        plugin.getKmcCore().getApi().acquireScoreboard("tgttos");

        // BossBar
        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "TGTTOS",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        currentRound = 0;
        broadcast("&6[TGTTOS] &eRotation: &f" + rotation.size() + " maps");
        for (int i = 0; i < rotation.size(); i++) {
            broadcast("  &7" + (i + 1) + ". &e" + rotation.get(i).getDisplayName());
        }

        // Start round 1
        Bukkit.getScheduler().runTaskLater(plugin, this::startNextRound, 40L);
        return null;
    }

    private void startNextRound() {
        currentRound++;
        if (currentRound > rotation.size()) {
            endGame("all_rounds_done");
            return;
        }
        Map map = rotation.get(currentRound - 1);
        if (map == null || !map.isReady()) {
            broadcast("&c[TGTTOS] Map " + currentRound + " is niet klaar, sla over.");
            startNextRound();
            return;
        }

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 10);
        roundFinishCounter = 0;
        teamFinishOrderThisRound.clear();

        // Reset per-round state
        for (RunnerState rs : runners.values()) rs.startRound();

        // TP all players to start spawns + freeze
        var spawns = new ArrayList<>(map.getStartSpawns());
        Collections.shuffle(spawns);
        int i = 0;
        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            Location spawn = spawns.get(i % spawns.size());
            p.teleport(spawn);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setFallDistance(0);
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
            i++;
        }

        broadcast("&6[TGTTOS] &eRound &6" + currentRound + "/" + rotation.size()
                + " &7— &b" + map.getDisplayName() + " &7start over &6"
                + countdownSeconds + " &7seconden!");

        bossBar.setColor(BarColor.YELLOW);
        bossBar.setProgress(1.0);
        bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + "Round " + currentRound
                + " — " + map.getDisplayName() + " — start over " + countdownSeconds + "s");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            bossBar.setProgress(Math.max(0,
                    (double) countdownSeconds /
                            Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 10))));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + "Round " + currentRound
                    + " — " + map.getDisplayName() + " — " + countdownSeconds + "s");

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                bossBar.setColor(BarColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "" + countdownSeconds,
                            ChatColor.YELLOW + map.getDisplayName(), 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }
            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                launchRound(map);
            }
        }, 20L, 20L);
    }

    private void launchRound(Map map) {
        state = State.ROUND_ACTIVE;
        roundStartMs = System.currentTimeMillis();
        remainingRoundSeconds = plugin.getConfig().getInt("game.round-duration-seconds", 90);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();
        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            if (jumpType != null) p.removePotionEffect(jumpType);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Get to the other side!", 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }
        broadcast("&a&l[TGTTOS] &eGO! &7Bereik de overkant!");

        // Round timer
        roundTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingRoundSeconds--;
            updateBossBar();
            if (remainingRoundSeconds <= 0) {
                endRound("time_limit");
            }
        }, 20L, 20L);

        // Void / fall check (every 5 ticks)
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> checkVoidFalls(map), 5L, 5L);
    }

    // ----------------------------------------------------------------
    // Movement detection — finish + void
    // ----------------------------------------------------------------

    /** Called by listener when a player moves. */
    public void handleMovement(Player p, Location to) {
        if (state != State.ROUND_ACTIVE) return;
        RunnerState rs = runners.get(p.getUniqueId());
        if (rs == null || rs.isCurrentRoundFinished()) return;
        Map map = getCurrentMap();
        if (map == null) return;

        if (map.isInFinishRegion(to)) {
            handleFinish(p, rs, map);
        }
    }

    private void checkVoidFalls(Map map) {
        if (state != State.ROUND_ACTIVE) return;
        int voidY = map.getVoidYLevel();
        for (UUID uuid : runners.keySet()) {
            RunnerState rs = runners.get(uuid);
            if (rs == null || rs.isCurrentRoundFinished()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (p.getLocation().getY() < voidY) {
                handleDeath(p, rs, map);
            }
        }
    }

    private void handleDeath(Player p, RunnerState rs, Map map) {
        rs.recordDeath();
        // TP back to a random start spawn for this map
        var spawns = new ArrayList<>(map.getStartSpawns());
        Collections.shuffle(spawns);
        Location respawn = spawns.get(0);
        p.teleport(respawn);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFallDistance(0);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.7f);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.RED + "✘ Dood! &7Terug naar start. &8(deaths: " + rs.getCurrentRoundDeaths() + ")"));
    }

    private void handleFinish(Player p, RunnerState rs, Map map) {
        roundFinishCounter++;
        int placement = roundFinishCounter;
        int points = pointsForPlacement(placement);

        rs.finishRound(currentRound, placement, points);

        // Award points via KMCCore so team aggregation works
        if (points > 0) plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), points);

        long elapsedMs = System.currentTimeMillis() - roundStartMs;
        broadcast("&6[TGTTOS] &e" + p.getName()
                + " &bfinisht round " + currentRound + " als &6#" + placement
                + " &7(+" + points + " pts, " + (elapsedMs / 1000) + "s)");

        p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "#" + placement,
                ChatColor.YELLOW + "+" + points + " punten", 10, 50, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        // Spectator until round ends
        p.setGameMode(GameMode.SPECTATOR);

        // Team-finish bonus: did this finish complete a TEAM?
        var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (team != null && !teamFinishOrderThisRound.contains(team.getId())) {
            // Check if all members of this team have finished the current round
            boolean teamDone = true;
            for (UUID memberId : team.getMembers()) {
                RunnerState mrs = runners.get(memberId);
                if (mrs == null) continue;  // member not in game
                if (!mrs.isCurrentRoundFinished()) { teamDone = false; break; }
            }
            if (teamDone && !team.getMembers().isEmpty()) {
                teamFinishOrderThisRound.add(team.getId());
                int teamRank = teamFinishOrderThisRound.size();
                int bonusEach = readPlacement("points.team-finish-bonus", teamRank);
                if (bonusEach > 0) {
                    for (UUID memberId : team.getMembers()) {
                        plugin.getKmcCore().getApi().givePoints(memberId, bonusEach);
                    }
                    broadcast("&6&l✦ Team " + team.getDisplayName()
                            + " &eis finish-team #" + teamRank
                            + " &7(+" + bonusEach + " elk)");
                }
            }
        }

        // All finished?
        long stillRacing = runners.values().stream()
                .filter(r -> !r.isCurrentRoundFinished())
                .count();
        if (stillRacing == 0) {
            endRound("all_finished");
        }
    }

    private int pointsForPlacement(int placement) {
        var cfg = plugin.getConfig();
        // New: explicit per-placement values (1=80, 2=70, 3=65, 4=60, 5=55, default=0)
        int explicit = cfg.getInt("points.round-placement." + placement, -1);
        if (explicit >= 0) return explicit;
        return cfg.getInt("points.round-placement.default", 0);
    }

    // ----------------------------------------------------------------
    // Round end + intermission
    // ----------------------------------------------------------------

    private void endRound(String reason) {
        if (state != State.ROUND_ACTIVE) return;
        state = State.INTERMISSION;
        cancelRoundTasks();

        // DNF participants get the lowest tier
        for (RunnerState rs : runners.values()) {
            if (!rs.isCurrentRoundFinished()) {
                int dnf = plugin.getConfig().getInt("points.round-dnf", 0);
                rs.finishRound(currentRound, 0, dnf);
                if (dnf > 0) plugin.getKmcCore().getApi().givePoints(rs.getUuid(), dnf);
            }
        }

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lRound " + currentRound + " &7— &fklassement");
        var ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> Integer.compare(b.getTotalPoints(), a.getTotalPoints()));
        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            broadcast("  &7" + (i + 1) + ". &f" + rs.getName()
                    + " &8- &e" + rs.getTotalPoints() + " pts &7(round: "
                    + (rs.getRoundPlacement(currentRound) > 0
                        ? "#" + rs.getRoundPlacement(currentRound) : "DNF") + ")");
        }
        broadcast("&6═══════════════════════════════════");

        // Intermission then next round
        int intermissionSec = plugin.getConfig().getInt("game.intermission-seconds", 8);
        Bukkit.getScheduler().runTaskLater(plugin, this::startNextRound, intermissionSec * 20L);
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelRoundTasks();

        // Final ranking — by total points desc, deaths asc as tiebreaker
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> {
            if (a.getTotalPoints() != b.getTotalPoints())
                return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
            return Integer.compare(a.getTotalDeaths(), b.getTotalDeaths());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lTGTTOS — Eindstand");
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"first-place", "second-place", "third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(rs.getUuid());
            String teamColor = team != null ? team.getColor().toString() : "";

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + teamColor + rs.getName()
                    + " &8- &e" + rs.getTotalPoints() + " pts"
                    + " &8(" + rs.getRoundsFinished() + "/" + rotation.size() + " rounds)");

            // Final placement bonuses on top of round points
            int bonus;
            if (i < placeKeys.length)
                bonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                bonus = plugin.getConfig().getInt("points.participation", 25);
            if (bonus > 0) api.givePoints(rs.getUuid(), bonus);

            // Standardized end-of-game stats
            api.recordGameParticipation(rs.getUuid(), rs.getName(), GAME_ID, i == 0);

            if (i == 0) winnerName = teamColor + rs.getName();
        }

        // Team aggregate footer
        java.util.Map<String, Integer> teamTotals = new HashMap<>();
        java.util.Map<String, KMCTeam> teamLookup = new HashMap<>();
        for (RunnerState rs : ranked) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(rs.getUuid());
            if (team == null) continue;
            teamTotals.merge(team.getId(), rs.getTotalPoints(), Integer::sum);
            teamLookup.put(team.getId(), team);
        }
        if (!teamTotals.isEmpty()) {
            broadcast("&6═══ Team Totalen ═══");
            teamTotals.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        KMCTeam t = teamLookup.get(e.getKey());
                        broadcast("  " + t.getColor() + t.getDisplayName()
                                + " &8- &e" + e.getValue() + " pts");
                    });
        }
        broadcast("&6═══════════════════════════════════");

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint TGTTOS!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("tgttos");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            p.setHealth(20);
            p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        }

        runners.clear();
        rotation.clear();
        currentRound = 0;
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame("force_stop");
    }

    private void cancelRoundTasks() {
        if (countdownTask  != null) { countdownTask.cancel();  countdownTask = null; }
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (voidCheckTask  != null) { voidCheckTask.cancel();  voidCheckTask = null; }
    }

    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        Map map = getCurrentMap();
        String mapName = map != null ? map.getDisplayName() : "?";
        long stillRacing = runners.values().stream()
                .filter(r -> !r.isCurrentRoundFinished()).count();
        int min = remainingRoundSeconds / 60;
        int sec = remainingRoundSeconds % 60;
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&aRound " + currentRound + "/" + rotation.size() + " &8| &b" + mapName
                + " &8| &e" + (runners.size() - stillRacing) + "/" + runners.size() + " finisht"
                + " &8| &b" + String.format("%02d:%02d", min, sec)));
        if (state == State.ROUND_ACTIVE) {
            int totalSec = plugin.getConfig().getInt("game.round-duration-seconds", 90);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingRoundSeconds / totalSec)));
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    /**
     * Reads a tiered placement value from config. Falls back to
     * "{section}.default" if the specific placement key is absent.
     */
    private int readPlacement(String section, int placement) {
        int explicit = plugin.getConfig().getInt(section + "." + placement, -1);
        if (explicit >= 0) return explicit;
        return plugin.getConfig().getInt(section + ".default", 0);
    }
}
