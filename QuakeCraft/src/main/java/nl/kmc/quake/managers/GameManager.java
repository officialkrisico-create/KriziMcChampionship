package nl.kmc.quake.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.models.PowerupType;
import nl.kmc.quake.util.WeaponFactory;
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
 * QuakeCraft game state machine.
 *
 * <p>States: IDLE → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * <p>Win conditions:
 * <ul>
 *   <li>First team to {@code team-kill-target} kills wins</li>
 *   <li>If timer runs out, team with most kills wins</li>
 * </ul>
 *
 * <p>Per-player state lives in {@link PlayerState}; per-team kill totals
 * are computed live by summing player kills.
 */
public class GameManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "quake_craft";

    private final QuakeCraftPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final Set<UUID>              spectators = new HashSet<>();

    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    public GameManager(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Helpers — Paper 1.21 compatible
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
        if (!plugin.getArenaManager().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getReadinessReport();

        var teams = plugin.getKmcCore().getTeamManager().getAllTeams();
        if (teams.size() < 2) return "Minimaal 2 teams nodig.";

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        players.clear();
        spectators.clear();

        // Register everyone with a team as a player; everyone else as spectator
        for (Player p : Bukkit.getOnlinePlayers()) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (team != null) {
                players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
            } else {
                spectators.add(p.getUniqueId());
            }
        }

        if (players.isEmpty()) {
            state = State.IDLE;
            return "Geen spelers met een team — geen game gestart.";
        }

        // Acquire scoreboard lock
        plugin.getKmcCore().getApi().acquireScoreboard("quakecraft");

        // Spread players across spawns + freeze
        teleportAllToSpawns();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
        }

        for (UUID uuid : spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.setGameMode(GameMode.SPECTATOR);
        }

        // BossBar
        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "QuakeCraft start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[QuakeCraft] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "QuakeCraft start over " + countdownSeconds + "s");

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
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 600);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        // Unfreeze + give base railgun
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            if (jumpType != null) p.removePotionEffect(jumpType);
            giveBaseLoadout(p);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Eerste team naar 25 kills wint!", 0, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }

        broadcast("&a&l[QuakeCraft] &eGO! &7Eerste team naar &625 &7kills wint!");

        // Game timer
        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();

            // Time up?
            if (remainingSeconds <= 0) {
                endGame("time_limit");
                return;
            }

            // Kill target reached?
            String winningTeam = checkKillTargetReached();
            if (winningTeam != null) {
                endGame("kill_target");
            }
        }, 20L, 20L);

        // Start powerup spawner
        if (plugin.getConfig().getBoolean("powerup-spawning.enabled", true)) {
            plugin.getPowerupSpawner().start();
        }
    }

    private void giveBaseLoadout(Player p) {
        if (plugin.getConfig().getBoolean("game.give-base-railgun", true)) {
            p.getInventory().setItem(0, WeaponFactory.buildRailgun(plugin));
        }
        // Apply base speed boost
        if (plugin.getConfig().getBoolean("game.base-speed-boost", true)) {
            PotionEffectType speedType = speed();
            if (speedType != null) {
                p.addPotionEffect(new PotionEffect(speedType,
                        Integer.MAX_VALUE, 0, true, false, false));
            }
        }
    }

    private void teleportAllToSpawns() {
        var spawns = new ArrayList<>(plugin.getArenaManager().getSpawns());
        Collections.shuffle(spawns);
        int i = 0;
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            Location spawn = spawns.get(i % spawns.size());
            p.teleport(spawn);
            i++;
        }
    }

    // ----------------------------------------------------------------
    // Hit handling — called by RailgunWeapon and GrenadeWeapon
    // ----------------------------------------------------------------

    /**
     * A shooter hit a target. Decide whether to count it as a kill.
     * Refuses team kills (friendly fire off by default).
     */
    public void handleHit(Player shooter, Player target, String weapon) {
        if (state != State.ACTIVE) return;
        if (shooter == null || target == null) return;
        if (shooter.equals(target)) return;

        PlayerState shooterState = players.get(shooter.getUniqueId());
        PlayerState targetState  = players.get(target.getUniqueId());
        if (shooterState == null || targetState == null) return;

        // Friendly fire?
        if (!plugin.getConfig().getBoolean("game.friendly-fire", false)) {
            var sTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(shooter.getUniqueId());
            var tTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(target.getUniqueId());
            if (sTeam != null && tTeam != null && sTeam.getId().equals(tTeam.getId())) return;
        }

        // Register the kill
        shooterState.addKill();
        targetState.addDeath();

        // Award points to the shooter (logs to point_awards table)
        int perKill = plugin.getConfig().getInt("points.per-kill", 10);
        plugin.getKmcCore().getApi().givePoints(shooter.getUniqueId(), perKill);

        // HoF tracking
        plugin.getKmcCore().getHallOfFameManager().recordKill(shooter);

        // Killstreak
        checkKillstreak(shooter, shooterState);

        // Death effects + respawn
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);

        broadcast("&c☠ &7" + target.getName() + " &8← &e" + shooter.getName()
                + " &8(" + shooterState.getKills() + ")");

        respawn(target, targetState);
        updateBossBar();
    }

    private void respawn(Player target, PlayerState state) {
        target.setGameMode(GameMode.SPECTATOR);
        target.getInventory().clear();
        target.sendTitle(ChatColor.RED + "Je bent dood!",
                ChatColor.GRAY + "Respawn over " + plugin.getConfig().getInt("game.respawn-delay-seconds", 1) + "s",
                0, 20, 5);

        int delay = plugin.getConfig().getInt("game.respawn-delay-seconds", 1) * 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (this.state != State.ACTIVE) return;
            // Find spawn far away from other players
            List<Location> avoid = new ArrayList<>();
            for (UUID otherId : players.keySet()) {
                if (otherId.equals(target.getUniqueId())) continue;
                Player other = Bukkit.getPlayer(otherId);
                if (other != null && !other.isDead()) avoid.add(other.getLocation());
            }
            Location spawn = plugin.getArenaManager().randomSpawnAwayFrom(avoid);
            if (spawn != null) target.teleport(spawn);
            target.setGameMode(GameMode.ADVENTURE);
            target.setHealth(20);
            target.setFoodLevel(20);
            for (var eff : target.getActivePotionEffects()) target.removePotionEffect(eff.getType());
            giveBaseLoadout(target);
        }, delay);
    }

    private void checkKillstreak(Player shooter, PlayerState state) {
        if (!plugin.getConfig().getBoolean("killstreaks.enabled", true)) return;
        var rewards = plugin.getConfig().getConfigurationSection("killstreaks.rewards");
        if (rewards == null) return;

        String key = String.valueOf(state.getCurrentStreak());
        if (!rewards.contains(key)) return;

        var section = rewards.getConfigurationSection(key);
        if (section == null) return;

        int bonusPoints = section.getInt("bonus-points", 0);
        if (bonusPoints > 0) {
            plugin.getKmcCore().getApi().givePoints(shooter.getUniqueId(), bonusPoints);
        }

        int speedSec = section.getInt("speed-seconds", 0);
        if (speedSec > 0) {
            PotionEffectType speedType = speed();
            if (speedType != null) {
                shooter.addPotionEffect(new PotionEffect(
                        speedType, speedSec * 20, 1, true, false, false));
            }
        }

        String msg = section.getString("message", "");
        if (!msg.isBlank()) {
            broadcast(msg.replace("{player}", shooter.getName()));
        }
    }

    // ----------------------------------------------------------------
    // Win check + end
    // ----------------------------------------------------------------

    /** Returns winning team's id if any team has reached the kill target. */
    private String checkKillTargetReached() {
        int target = plugin.getConfig().getInt("game.team-kill-target", 25);
        Map<String, Integer> totals = computeTeamKills();
        for (var e : totals.entrySet()) {
            if (e.getValue() >= target) return e.getKey();
        }
        return null;
    }

    private Map<String, Integer> computeTeamKills() {
        Map<String, Integer> totals = new HashMap<>();
        for (PlayerState ps : players.values()) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            if (team == null) continue;
            totals.merge(team.getId(), ps.getKills(), Integer::sum);
        }
        return totals;
    }

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();
        plugin.getPowerupSpawner().stop();

        // Sort teams by kills
        Map<String, Integer> totals = computeTeamKills();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lQuakeCraft — Uitslag");
        broadcast("&7Reden: " + (reason.equals("kill_target") ? "&aKill target bereikt" : "&eTijd op"));
        broadcast("&6═══════════════════════════════════");

        // Display + award
        KMCApi api = plugin.getKmcCore().getApi();
        String[] placePts = {"team-first-place", "team-second-place", "team-third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < sorted.size(); i++) {
            String teamId = sorted.get(i).getKey();
            int teamKills = sorted.get(i).getValue();
            KMCTeam team = plugin.getKmcCore().getTeamManager().getTeam(teamId);
            if (team == null) continue;

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + team.getColor() + team.getDisplayName()
                    + " &8- &e" + teamKills + " kills");

            // Award team placement points (per-player so KMCCore tracks correctly)
            int placePoints;
            if (i < placePts.length) {
                placePoints = plugin.getConfig().getInt("points." + placePts[i], 0);
            } else {
                placePoints = plugin.getConfig().getInt("points.team-participation", 25);
            }
            for (UUID memberId : team.getMembers()) {
                if (placePoints > 0) api.givePoints(memberId, placePoints);
            }

            if (i == 0) winnerName = team.getColor() + team.getDisplayName();
        }

        broadcast("&6═══════════════════════════════════");
        broadcast("&6Top spelers:");
        var topPlayers = new ArrayList<>(players.values());
        topPlayers.sort((a, b) -> Integer.compare(b.getKills(), a.getKills()));
        for (int i = 0; i < Math.min(3, topPlayers.size()); i++) {
            PlayerState ps = topPlayers.get(i);
            broadcast("  &e#" + (i + 1) + " &f" + ps.getName()
                    + " &8- &e" + ps.getKills() + "K/" + ps.getDeaths() + "D"
                    + " &8(streak " + ps.getBestStreak() + ")");
        }
        broadcast("&6═══════════════════════════════════");

        // End sequence: 5 seconds of victory titles, then cleanup
        final String finalWinnerName = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(MessageWrap.color("&6&l🏆 " + finalWinnerName),
                    MessageWrap.color("&7wint QuakeCraft!"), 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinnerName), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("quakecraft");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            if (lobby != null) p.teleport(lobby);
        }

        players.clear();
        spectators.clear();
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame("force_stop");
        cancelTasks();
    }

    private void cancelTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
    }

    // ----------------------------------------------------------------
    // BossBar
    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        Map<String, Integer> totals = computeTeamKills();
        var top = totals.entrySet().stream()
                .max(Map.Entry.comparingByValue());

        int target = plugin.getConfig().getInt("game.team-kill-target", 25);
        int leadingKills = top.map(Map.Entry::getValue).orElse(0);
        String leadingTeam = top
                .map(e -> plugin.getKmcCore().getTeamManager().getTeam(e.getKey()))
                .map(t -> t != null ? t.getColor() + t.getDisplayName() : "?")
                .orElse("Niemand");

        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;

        bossBar.setTitle(MessageWrap.color(
                "&e" + leadingTeam + "&r &8| &f" + leadingKills + "/" + target + "  "
                + "&8| &b" + String.format("%02d:%02d", min, sec)));
        bossBar.setProgress(Math.max(0, Math.min(1, (double) leadingKills / target)));
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    // ----------------------------------------------------------------

    public State                  getState()    { return state; }
    public boolean                isActive()    { return state == State.ACTIVE; }
    public Map<UUID, PlayerState> getPlayers()  { return Collections.unmodifiableMap(players); }
    public PlayerState            get(UUID uuid){ return players.get(uuid); }

    /** Helper local color util to avoid pulling in MessageUtil. */
    private static class MessageWrap {
        static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    }
}
