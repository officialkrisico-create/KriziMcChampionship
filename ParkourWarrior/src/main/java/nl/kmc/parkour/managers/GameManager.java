package nl.kmc.parkour.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.parkour.ParkourWarriorPlugin;
import nl.kmc.parkour.models.Checkpoint;
import nl.kmc.parkour.models.RunnerState;
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
 * Parkour Warrior game engine.
 *
 * <p>States: IDLE → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * <p>Win conditions:
 * <ul>
 *   <li>Timer expires → players ranked by points; tiebreaker = finish time</li>
 *   <li>If end-on-first-finish: first player to reach the finish ends the game</li>
 * </ul>
 *
 * <p>Both solo and team scoring:
 * <ul>
 *   <li>Each player earns individual points for checkpoints + finish bonus</li>
 *   <li>KMCCore aggregates per-team automatically (each player's points
 *       contribute to their team's total)</li>
 *   <li>Top 3 individual placement gets bonus points</li>
 * </ul>
 */
public class GameManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "parkour_warrior";

    private final ParkourWarriorPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, RunnerState> runners = new LinkedHashMap<>();
    private final List<UUID> finishOrder = new ArrayList<>();

    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private BukkitTask tickTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    public GameManager(ParkourWarriorPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Helpers — Paper 1.21 effect lookups
    // ----------------------------------------------------------------

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
    private PotionEffectType speed() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("speed")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public String startCountdown() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getCourseManager().isReady())
            return "Course niet klaar:\n" + plugin.getCourseManager().getReadinessReport();

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        runners.clear();
        finishOrder.clear();

        // All online players race
        for (Player p : Bukkit.getOnlinePlayers()) {
            runners.put(p.getUniqueId(), new RunnerState(p.getUniqueId(), p.getName()));
        }

        if (runners.isEmpty()) {
            state = State.IDLE;
            return "Geen spelers online.";
        }

        plugin.getKmcCore().getApi().acquireScoreboard("parkour");

        // Teleport everyone to start + freeze
        Location start = plugin.getCourseManager().getStartSpawn();
        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.teleport(start);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
        }

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Parkour start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Parkour] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "Parkour start over " + countdownSeconds + "s");

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                bossBar.setColor(BarColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "" + countdownSeconds,
                            ChatColor.YELLOW + "Maak je klaar!", 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }

            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                launch();
            }
        }, 20L, 20L);

        return null;
    }

    private void launch() {
        state = State.ACTIVE;
        gameStartMs = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 720);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();
        PotionEffectType speedType = speed();
        boolean baseSpeed = plugin.getConfig().getBoolean("game.base-speed", true);

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            if (jumpType != null) p.removePotionEffect(jumpType);
            if (baseSpeed && speedType != null) {
                p.addPotionEffect(new PotionEffect(speedType, Integer.MAX_VALUE, 0, true, false, false));
            }
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Race door de course!", 0, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }

        broadcast("&a&l[Parkour] &eGO! &7Race naar de finish!");

        // Game timer
        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();

            if (remainingSeconds <= 0) {
                endGame("time_limit");
            }
        }, 20L, 20L);

        // Frequent tick for void-fall detection (cheap check)
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::voidCheckAll, 10L, 10L);
    }

    /**
     * Periodic check — if a player has fallen into void or below
     * world floor, treat as death.
     */
    private void voidCheckAll() {
        if (state != State.ACTIVE) return;
        World world = plugin.getCourseManager().getCourseWorld();
        if (world == null) return;
        double minY = world.getMinHeight();

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            RunnerState rs = runners.get(uuid);
            if (rs == null || rs.isFinished()) continue;
            if (p.getLocation().getY() < minY + 5) {
                handleDeath(p);
            }
        }
    }

    // ----------------------------------------------------------------
    // Checkpoint hits — called by listener
    // ----------------------------------------------------------------

    public void handleCheckpointEntry(Player p, Checkpoint cp) {
        if (state != State.ACTIVE) return;
        RunnerState rs = runners.get(p.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        // Already passed?
        if (cp.getIndex() <= rs.getHighestCheckpoint()) return;

        // Out of order? Players may discover checkpoints in any order if the
        // map allows it; we accept any forward jump but log the gap.
        boolean newProgress = rs.reachCheckpoint(cp.getIndex(), cp.getPoints());
        if (!newProgress) return;

        // Award points immediately (logged to point_awards table)
        plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), cp.getPoints());

        // Is this the finish?
        Checkpoint finish = plugin.getCourseManager().getFinish();
        boolean isFinish = finish != null && cp.getIndex() == finish.getIndex();

        if (isFinish) {
            handleFinish(p, rs);
        } else {
            String msg = ChatColor.AQUA + "✔ " + cp.getDisplayName()
                    + ChatColor.GRAY + " (+" + cp.getPoints() + " pts)";
            p.sendActionBar(net.kyori.adventure.text.Component.text(msg));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }

        updateBossBar();
    }

    private void handleFinish(Player p, RunnerState rs) {
        int placement = finishOrder.size() + 1;
        rs.markFinished(placement);
        finishOrder.add(p.getUniqueId());

        int finishBonus = plugin.getConfig().getInt("game.finish-bonus", 100);
        if (finishBonus > 0) {
            plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), finishBonus);
        }

        long elapsedMs = System.currentTimeMillis() - gameStartMs;
        String time = formatMs(elapsedMs);

        broadcast("&6[Parkour] &e" + p.getName()
                + " &bfinisht als &6#" + placement + " &7(" + time + ")");

        p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "#" + placement,
                ChatColor.YELLOW + "Tijd: " + time, 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        // Move to spectator so they can watch others
        p.setGameMode(GameMode.SPECTATOR);

        // End early?
        if (plugin.getConfig().getBoolean("game.end-on-first-finish", false)) {
            endGame("first_finish");
            return;
        }

        // Did everyone finish?
        if (finishOrder.size() >= runners.size()) {
            endGame("all_finished");
        }
    }

    // ----------------------------------------------------------------
    // Death / fall handling
    // ----------------------------------------------------------------

    public void handleDeath(Player p) {
        if (state != State.ACTIVE) return;
        RunnerState rs = runners.get(p.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        rs.recordDeath();

        Location respawn = computeRespawnFor(rs);
        p.teleport(respawn);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFallDistance(0);
        for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
        // Re-apply base speed
        if (plugin.getConfig().getBoolean("game.base-speed", true)) {
            PotionEffectType st = speed();
            if (st != null) p.addPotionEffect(new PotionEffect(st, Integer.MAX_VALUE, 0, true, false, false));
        }

        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.7f);

        // Skip availability check
        boolean skipEnabled = plugin.getConfig().getBoolean("skip.enabled", true);
        int failsRequired  = plugin.getConfig().getInt("skip.fails-required", 3);
        if (skipEnabled && rs.getCurrentStageFailCount() >= failsRequired) {
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.YELLOW + "Vast? Typ " + ChatColor.GOLD + "/pkw skip"
                    + ChatColor.YELLOW + " om dit checkpoint over te slaan (geen punten)"));
        } else if (skipEnabled) {
            int remaining = failsRequired - rs.getCurrentStageFailCount();
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.RED + "✘ Dood! "
                    + ChatColor.GRAY + "Skip beschikbaar na " + remaining + " meer fail(s)"));
        }
    }

    private Location computeRespawnFor(RunnerState rs) {
        int idx = rs.getHighestCheckpoint();
        if (idx <= 0) return plugin.getCourseManager().getStartSpawn();
        Checkpoint cp = plugin.getCourseManager().getCheckpoint(idx);
        return cp != null ? cp.getRespawn() : plugin.getCourseManager().getStartSpawn();
    }

    // ----------------------------------------------------------------
    // Skip command
    // ----------------------------------------------------------------

    public String trySkip(Player p) {
        if (state != State.ACTIVE) return "Geen game actief.";
        if (!plugin.getConfig().getBoolean("skip.enabled", true))
            return "Skip is uitgeschakeld.";

        RunnerState rs = runners.get(p.getUniqueId());
        if (rs == null) return "Je doet niet mee aan de race.";
        if (rs.isFinished()) return "Je bent al gefinisht!";

        int failsRequired = plugin.getConfig().getInt("skip.fails-required", 3);
        if (rs.getCurrentStageFailCount() < failsRequired) {
            int remaining = failsRequired - rs.getCurrentStageFailCount();
            return "Je hebt nog " + remaining + " meer fail(s) nodig om te skippen.";
        }

        int nextIdx = rs.getHighestCheckpoint() + 1;
        Checkpoint next = plugin.getCourseManager().getCheckpoint(nextIdx);
        if (next == null) return "Geen volgend checkpoint om te skippen.";

        // Mark skipped — no points awarded
        rs.skipCheckpoint(nextIdx);
        p.teleport(next.getRespawn());
        p.setFallDistance(0);

        broadcast("&7" + p.getName() + " &8sloeg checkpoint &7" + next.getDisplayName()
                + " &8over (geen punten)");

        // Did they skip the finish? Treat as a no-points finish
        Checkpoint finish = plugin.getCourseManager().getFinish();
        if (finish != null && nextIdx == finish.getIndex()) {
            // Mark as finished but with skip flag
            int placement = finishOrder.size() + 1;
            rs.markFinished(placement);
            finishOrder.add(p.getUniqueId());
            broadcast("&7" + p.getName() + " &7finisht via skip als &6#" + placement);
            p.setGameMode(GameMode.SPECTATOR);
            if (finishOrder.size() >= runners.size()) endGame("all_finished");
        }

        return null;
    }

    // ----------------------------------------------------------------
    // Powerup pickup
    // ----------------------------------------------------------------

    public void applyPowerup(Player p, nl.kmc.parkour.models.Powerup pu) {
        if (state != State.ACTIVE) return;
        PotionEffectType t = switch (pu.getType()) {
            case SPEED -> speed();
            case JUMP  -> jumpBoost();
        };
        if (t == null) return;
        int ticks = pu.getDurationSeconds() * 20;
        p.addPotionEffect(new PotionEffect(t, ticks, pu.getAmplifier(), true, false, true));
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.5f);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.GREEN + "✦ " + (pu.getType() == nl.kmc.parkour.models.Powerup.Type.SPEED
                        ? "Speed" : "Jump") + " Boost!"));
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();

        // Final ranking — by points desc, tiebreak by finish time asc, then deaths asc
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> {
            if (a.getTotalPoints() != b.getTotalPoints())
                return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
            if (a.isFinished() && b.isFinished())
                return Long.compare(a.getFinishTimeMs(), b.getFinishTimeMs());
            if (a.isFinished()) return -1;
            if (b.isFinished()) return 1;
            return Integer.compare(a.getTotalDeaths(), b.getTotalDeaths());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lParkour Warrior — Uitslag");
        broadcast("&7Reden: " + (reason.equals("time_limit") ? "&eTijd op"
                : reason.equals("first_finish") ? "&aEerste speler gefinisht"
                : reason.equals("all_finished") ? "&aIedereen gefinisht"
                : "&7Beëindigd"));
        broadcast("&6═══════════════════════════════════");

        // Top 3 individual placement bonuses
        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"first-place", "second-place", "third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            String finishStr = rs.isFinished() ? " &7(finisht)" : "";
            String skipStr = rs.getSkippedCheckpoints().isEmpty() ? ""
                    : " &7[" + rs.getSkippedCheckpoints().size() + " skips]";
            broadcast("  " + medal + " &f" + rs.getName()
                    + " &8- &e" + rs.getTotalPoints() + " punten"
                    + finishStr + skipStr);

            // Bonus for top 3
            int bonus;
            if (i < placeKeys.length)
                bonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                bonus = plugin.getConfig().getInt("points.participation", 25);
            if (bonus > 0) api.givePoints(rs.getUuid(), bonus);

            if (i == 0) winnerName = rs.getName();
        }

        // Team totals (informational — KMCCore already aggregates)
        Map<String, Integer> teamTotals = new HashMap<>();
        Map<String, KMCTeam> teamLookup = new HashMap<>();
        for (RunnerState rs : ranked) {
            KMCTeam t = plugin.getKmcCore().getTeamManager().getTeamByPlayer(rs.getUuid());
            if (t == null) continue;
            teamTotals.merge(t.getId(), rs.getTotalPoints(), Integer::sum);
            teamLookup.put(t.getId(), t);
        }
        if (!teamTotals.isEmpty()) {
            broadcast("&6═══ Team Totalen ═══");
            teamTotals.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        KMCTeam t = teamLookup.get(e.getKey());
                        broadcast("  " + t.getColor() + t.getDisplayName()
                                + " &8- &e" + e.getValue() + " punten");
                    });
        }
        broadcast("&6═══════════════════════════════════");

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint Parkour Warrior!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("parkour");
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
            if (lobby != null) p.teleport(lobby);
        }

        runners.clear();
        finishOrder.clear();
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame("force_stop");
    }

    private void cancelTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (tickTask      != null) { tickTask.cancel();      tickTask = null; }
    }

    // ----------------------------------------------------------------
    // BossBar
    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        int total = plugin.getCourseManager().getCheckpointCount();
        int finishedCount = finishOrder.size();
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&aParkour Warrior &8| &e" + finishedCount + "/" + runners.size() + " finisht "
                + "&8| &b" + String.format("%02d:%02d", min, sec)));
        if (state == State.ACTIVE) {
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 720);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingSeconds / totalSec)));
        }
    }

    private static String formatMs(long ms) {
        long m = ms / 60000;
        long s = (ms % 60000) / 1000;
        return String.format("%02d:%02d", m, s);
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    // ----------------------------------------------------------------

    public State                  getState()    { return state; }
    public boolean                isActive()    { return state == State.ACTIVE; }
    public Map<UUID, RunnerState> getRunners()  { return Collections.unmodifiableMap(runners); }
    public RunnerState            get(UUID u)   { return runners.get(u); }
}
