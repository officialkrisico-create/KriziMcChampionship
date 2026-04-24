package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.RacerData;
import nl.kmc.kmccore.api.KMCApi;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Controls an Adventure Escape race.
 *
 * <p>Lifecycle:
 * <pre>
 *  IDLE → COUNTDOWN → ACTIVE → ENDED → IDLE
 * </pre>
 *
 * <p>On COUNTDOWN: players teleported to spawns, frozen, countdown bossbar.
 * On ACTIVE: players released; racers tracked via RacerData.
 * On ENDED: placements awarded, arena reset to idle, return to IDLE after 5s.
 *
 * <p>A race is FIRST-PAST-THE-POST: first player to complete all configured
 * laps is 1st. Others ranked by finish order; non-finishers after race
 * timeout get "last-place" points.
 */
public class RaceManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "adventure_escape";

    private final AdventureEscapePlugin plugin;
    private State                       state = State.IDLE;

    /** Active racers keyed by UUID. */
    private final Map<UUID, RacerData> racers = new LinkedHashMap<>();

    /** All players who started (alive + finished). */
    private final Set<UUID> allRacers = new LinkedHashSet<>();

    /** Finish order — index 0 = 1st place. */
    private final List<UUID> finishOrder = new ArrayList<>();

    private BukkitTask countdownTask;
    private BukkitTask tickTask;
    private BukkitTask timeLimitTask;

    private int countdownSeconds;

    private org.bukkit.boss.BossBar bossBar;

    public RaceManager(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // API
    // ----------------------------------------------------------------

    /** @return error message or null on success */
    public String startCountdown() {
        if (state != State.IDLE) return "Er is al een race bezig.";
        if (!plugin.getArenaManager().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getReadinessReport();

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 10);

        // Enroll all online players
        racers.clear();
        allRacers.clear();
        finishOrder.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            racers.put(p.getUniqueId(), new RacerData(p.getUniqueId(), p.getName()));
            allRacers.add(p.getUniqueId());
        }

        // Teleport everyone to the race world and spawn grid
        List<Location> grid = plugin.getArenaManager().getShuffledSpawns();
        List<UUID> order = new ArrayList<>(racers.keySet());
        for (int i = 0; i < order.size(); i++) {
            Player p = Bukkit.getPlayer(order.get(i));
            if (p == null) continue;
            Location spawn = grid.get(i % grid.size());
            p.teleport(spawn);
            p.setGameMode(GameMode.ADVENTURE); // no breaking while racing
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            // Freeze: give slowness IV + jump reduction during countdown
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SLOW, countdownSeconds * 20, 255, true, false, false));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.JUMP, countdownSeconds * 20, 128, true, false, false));
        }

        // BossBar
        bossBar = Bukkit.createBossBar("Race start over " + countdownSeconds + "s",
                org.bukkit.boss.BarColor.YELLOW, org.bukkit.boss.BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Adventure Escape] &eRace start over &6" + countdownSeconds + " &eseconden!");

        // Countdown ticking
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("game.countdown-seconds", 10);
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "Race start over " + countdownSeconds + "s");

            if (countdownSeconds <= 3 && countdownSeconds > 0) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(ChatColor.GOLD + "" + countdownSeconds, "", 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }

            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                launchRace();
            }
        }, 20L, 20L);

        return null;
    }

    private void launchRace() {
        state = State.ACTIVE;
        bossBar.setColor(org.bukkit.boss.BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "🏁 RACE ACTIVE");

        long now = System.currentTimeMillis();
        for (RacerData rd : racers.values()) {
            rd.markRaceStart(now);
            rd.startFirstLap(now);

            Player p = Bukkit.getPlayer(rd.getUuid());
            if (p != null) {
                // Remove freeze effects
                p.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW);
                p.removePotionEffect(org.bukkit.potion.PotionEffectType.JUMP);
                p.sendTitle(ChatColor.GREEN + "GO!", ChatColor.YELLOW + "Race is live!", 0, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
            }
        }

        broadcast("&a&l[Adventure Escape] &eGO! &7Wees de eerste over de finishlijn!");

        // Live tick — updates lap timers + scoreboard
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long t = System.currentTimeMillis();
            for (RacerData rd : racers.values()) rd.tickUpdate(t);
            plugin.getRaceScoreboard().refresh();
        }, 20L, 10L); // 0.5s cadence on scoreboard

        // Time limit
        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 600);
        if (maxDuration > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (state == State.ACTIVE) {
                    broadcast("&c[Adventure Escape] &eTijd is op!");
                    endRace();
                }
            }, maxDuration * 20L);
        }
    }

    /**
     * Called when a player crosses the finish line.
     */
    public void onPlayerCrossFinish(Player player) {
        if (state != State.ACTIVE) return;
        RacerData rd = racers.get(player.getUniqueId());
        if (rd == null || rd.hasFinished()) return;

        long now = System.currentTimeMillis();
        long lapTime = rd.completeLap(now);

        int targetLaps = plugin.getArenaManager().getLaps();

        if (rd.getLapsCompleted() >= targetLaps) {
            // Finished the race
            int placement = finishOrder.size() + 1;
            rd.markFinished(now, placement);
            finishOrder.add(rd.getUuid());

            player.sendTitle(
                    ChatColor.GOLD + "#" + placement,
                    ChatColor.YELLOW + "Totaal: " + RacerData.formatMs(rd.getTotalTimeMs()),
                    10, 60, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

            broadcast("&6[Adventure Escape] &e" + player.getName()
                    + " &bfinisht als &6#" + placement
                    + " &7(" + RacerData.formatMs(rd.getTotalTimeMs()) + ")");

            // Switch to spectator to watch the rest
            player.setGameMode(GameMode.SPECTATOR);

            // End race if all finished
            if (finishOrder.size() >= racers.size()) endRace();
        } else {
            // Lap completed
            String lapStr = ChatColor.AQUA + "Lap " + rd.getLapsCompleted()
                    + "/" + targetLaps + "  "
                    + ChatColor.YELLOW + RacerData.formatMs(lapTime);
            player.sendTitle("", lapStr, 0, 30, 10);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }
    }

    /**
     * Called when a player crosses the start line for the first time.
     * Starts their lap timer if they haven't started yet.
     */
    public void onPlayerCrossStart(Player player) {
        if (state != State.ACTIVE) return;
        RacerData rd = racers.get(player.getUniqueId());
        if (rd == null || rd.hasStarted()) return;

        rd.startFirstLap(System.currentTimeMillis());
        player.sendTitle("", ChatColor.AQUA + "Lap 1 gestart!", 0, 30, 10);
    }

    public void endRace() {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        KMCApi api = plugin.getKmcCore().getApi();

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lAdventure Escape — Uitslag");
        broadcast("&6═══════════════════════════════════");

        // Award finish-order placements
        for (int i = 0; i < finishOrder.size(); i++) {
            UUID uuid = finishOrder.get(i);
            int placement = i + 1;
            int base = plugin.getConfig().getInt("points." + placement, 0);
            if (base == 0) base = plugin.getConfig().getInt("points.last-place", 25);
            int awarded = api.givePoints(uuid, base);

            RacerData rd = racers.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : rd != null ? rd.getName() : "?";
            String medal = placement == 1 ? "&6🥇" : placement == 2 ? "&7🥈" : placement == 3 ? "&c🥉" : "&7#" + placement;
            broadcast("  " + medal + " &f" + name + " &8- &e" + awarded + " punten "
                    + "&8(" + RacerData.formatMs(rd != null ? rd.getTotalTimeMs() : 0) + ")");

            if (p != null) {
                var pd = plugin.getKmcCore().getPlayerDataManager().getOrCreate(uuid, name);
                pd.incrementGamesPlayed();
                if (placement == 1) pd.addWin(GAME_ID);
                else pd.resetStreak();
            }
        }

        // Non-finishers get last-place points
        int lastPlace = plugin.getConfig().getInt("points.last-place", 25);
        for (UUID uuid : allRacers) {
            if (finishOrder.contains(uuid)) continue;
            api.givePoints(uuid, lastPlace);
            RacerData rd = racers.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : rd != null ? rd.getName() : "?";
            broadcast("  &7DNF &f" + name + " &8- &e" + lastPlace + " punten");
        }

        broadcast("&6═══════════════════════════════════");

        // Announce winner name + signal KMCCore automation
        String winnerName = !finishOrder.isEmpty()
                ? Optional.ofNullable(Bukkit.getPlayer(finishOrder.get(0)))
                    .map(Player::getName).orElse("?")
                : "Niemand";

        // Reset world state + TP to lobby after 5s
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getRaceScoreboard().cleanup();
            for (UUID uuid : allRacers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.setGameMode(GameMode.ADVENTURE);
                    p.getInventory().clear();
                    for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
                    // TP to KMCCore lobby
                    if (plugin.getKmcCore().getArenaManager().getLobby() != null) {
                        p.teleport(plugin.getKmcCore().getArenaManager().getLobby());
                    }
                }
            }
            racers.clear();
            allRacers.clear();
            finishOrder.clear();
            state = State.IDLE;

            // Notify KMCCore automation
            if (plugin.getKmcCore().getAutomationManager().isRunning()) {
                plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
            }
        }, 100L);
    }

    public void forceStop() {
        if (state != State.IDLE) endRace();
        cancelTasks();
    }

    private void cancelTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (tickTask      != null) { tickTask.cancel();      tickTask = null; }
        if (timeLimitTask != null) { timeLimitTask.cancel(); timeLimitTask = null; }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    // ---- Getters ---------------------------------------------------
    public State     getState()    { return state; }
    public boolean   isActive()    { return state == State.ACTIVE; }
    public Map<UUID, RacerData> getRacers() { return Collections.unmodifiableMap(racers); }
    public List<UUID> getFinishOrder()      { return Collections.unmodifiableList(finishOrder); }

    /** Returns racers sorted by: finish order (finished first), then by laps+currentLap position. */
    public List<RacerData> getRankedRacers() {
        List<RacerData> list = new ArrayList<>(racers.values());
        list.sort((a, b) -> {
            // Finished players ranked first by placement
            if (a.hasFinished() && b.hasFinished()) return Integer.compare(a.getPlacement(), b.getPlacement());
            if (a.hasFinished()) return -1;
            if (b.hasFinished()) return 1;
            // Neither finished — compare by laps (more = better), then lap progress (less currentLapMs = better)
            int lapCmp = Integer.compare(b.getLapsCompleted(), a.getLapsCompleted());
            if (lapCmp != 0) return lapCmp;
            return Long.compare(a.getCurrentLapMs(), b.getCurrentLapMs());
        });
        return list;
    }
}
