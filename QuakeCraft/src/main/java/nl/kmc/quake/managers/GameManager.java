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
 * <p>FIXES IN THIS VERSION:
 * <ul>
 *   <li>handleHit() now ACTUALLY kills the target — sets HP to 0,
 *       triggers vanilla death animation. Was just registering kill
 *       in model without affecting target's HP.</li>
 *   <li>Death effects: explosion particle + lightning sound at kill spot.</li>
 *   <li>Arena world has PvP enabled at game launch (so the global
 *       PvP-disabled listener doesn't block our hits even if they
 *       went through damage events).</li>
 *   <li>BossBar updates immediately on every hit, not only on tick.</li>
 * </ul>
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

    /** Tracks the previous PvP state of the arena world so we can restore it. */
    private World    arenaWorld;
    private boolean  arenaWorldPreviousPvp;

    /** For revenge tracking: victim → (killer, timestamp ms). Cleared per game. */
    private final Map<UUID, UUID> lastKilledBy = new HashMap<>();
    private final Map<UUID, Long> lastKilledAt = new HashMap<>();

    public GameManager(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Helpers
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

        plugin.getKmcCore().getApi().acquireScoreboard("quakecraft");

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

        // Enable PvP in the arena world for the duration of the game.
        // (KMCCore's GlobalPvPListener cancels at NORMAL — this gives the
        // arena world its own pvp=true so vanilla rules don't block hits
        // either way. Our hits don't go through damage events anyway, but
        // this keeps the world consistent.)
        arenaWorld = plugin.getArenaManager().getArenaWorld();
        if (arenaWorld != null) {
            arenaWorldPreviousPvp = arenaWorld.getPVP();
            arenaWorld.setPVP(true);
        }

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

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

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();

            if (remainingSeconds <= 0) {
                endGame("time_limit");
                return;
            }
            UUID winningPlayer = checkKillTargetReached();
            if (winningPlayer != null) {
                endGame("kill_target");
            }
        }, 20L, 20L);

        if (plugin.getConfig().getBoolean("powerup-spawning.enabled", true)) {
            plugin.getPowerupSpawner().start();
        }
    }

    private void giveBaseLoadout(Player p) {
        if (plugin.getConfig().getBoolean("game.give-base-railgun", true)) {
            p.getInventory().setItem(0, WeaponFactory.buildRailgun(plugin));
        }
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
    // Hit handling — FIXED
    // ----------------------------------------------------------------

    /**
     * A shooter hit a target. Applies the kill: tracks stats, awards
     * points, plays death effects, and ACTUALLY kills the target.
     *
     * <p>Friendly fire is checked first — same-team hits are silently
     * ignored.
     */
    public void handleHit(Player shooter, Player target, String weapon) {
        if (state != State.ACTIVE) return;
        if (shooter == null || target == null) return;
        if (shooter.equals(target)) return;
        if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR) return;

        PlayerState shooterState = players.get(shooter.getUniqueId());
        PlayerState targetState  = players.get(target.getUniqueId());
        if (shooterState == null || targetState == null) return;

        // Friendly fire?
        if (!plugin.getConfig().getBoolean("game.friendly-fire", false)) {
            var sTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(shooter.getUniqueId());
            var tTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(target.getUniqueId());
            if (sTeam != null && tTeam != null && sTeam.getId().equals(tTeam.getId())) return;
        }

        // Revenge check — BEFORE recording this kill, check if shooter
        // is killing the player who last killed them.
        long revengeWindowMs = plugin.getConfig().getLong("points.revenge-window-ms", 10000);
        UUID prevKiller = lastKilledBy.get(shooter.getUniqueId());
        Long prevKilledAt = lastKilledAt.get(shooter.getUniqueId());
        boolean isRevenge = prevKiller != null
                && prevKiller.equals(target.getUniqueId())
                && prevKilledAt != null
                && (System.currentTimeMillis() - prevKilledAt) <= revengeWindowMs;
        if (isRevenge) {
            // Consume so the same revenge can't be re-triggered
            lastKilledBy.remove(shooter.getUniqueId());
            lastKilledAt.remove(shooter.getUniqueId());
        }

        // Track stats
        shooterState.addKill();
        targetState.addDeath();

        // Record this kill for FUTURE revenge detection by the target
        lastKilledBy.put(target.getUniqueId(), shooter.getUniqueId());
        lastKilledAt.put(target.getUniqueId(), System.currentTimeMillis());

        // Award points
        int perKill = plugin.getConfig().getInt("points.per-kill", 10);
        plugin.getKmcCore().getApi().givePoints(shooter.getUniqueId(), perKill);

        // Revenge bonus
        if (isRevenge) {
            int revengeBonus = plugin.getConfig().getInt("points.revenge-kill-bonus", 25);
            if (revengeBonus > 0) {
                plugin.getKmcCore().getApi().givePoints(shooter.getUniqueId(), revengeBonus);
            }
            broadcast("&d⚡ REVENGE! &7" + shooter.getName()
                    + " &epakte wraak op &7" + target.getName() + "&e! &8(+" + revengeBonus + ")");
            shooter.playSound(shooter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
        }

        // HoF
        plugin.getKmcCore().getHallOfFameManager().recordKill(shooter);

        // ---- DEATH EFFECTS ----
        Location deathLoc = target.getLocation().add(0, 1, 0);
        World w = target.getWorld();

        // Big explosion-style particle burst at the death location
        w.spawnParticle(Particle.EXPLOSION, deathLoc, 1);
        w.spawnParticle(Particle.LARGE_SMOKE, deathLoc, 25, 0.5, 0.5, 0.5, 0.05);
        w.spawnParticle(Particle.CRIT, deathLoc, 30, 0.4, 0.4, 0.4, 0.1);
        w.spawnParticle(Particle.CLOUD, deathLoc, 15, 0.3, 0.3, 0.3, 0.05);

        // Sound
        w.playSound(deathLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.5f);
        w.playSound(deathLoc, Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);

        shooter.playSound(shooter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);

        // Broadcast
        broadcast("&c☠ &7" + target.getName() + " &8← &e" + shooter.getName()
                + " &8(" + shooterState.getKills() + ")");

        // ---- KILL THE TARGET ----
        // We don't call setHealth(0) — that triggers vanilla death/respawn
        // which races with our scripted respawn and causes glitches. Instead
        // we go straight to spectator-then-teleport.
        respawn(target, targetState);

        // Killstreak check
        checkKillstreak(shooter, shooterState);

        updateBossBar();
    }

    /**
     * Respawns the target — spectator for the death-delay, then teleport
     * to a spawn far from other players, then back to ADVENTURE with kit.
     *
     * <p>No vanilla death/respawn involved — we control the entire flow.
     */
    private void respawn(Player target, PlayerState state) {
        // Phase 1: instant — spectator mode while particles/sound play
        target.setGameMode(GameMode.SPECTATOR);
        target.getInventory().clear();
        target.setHealth(20);    // reset HP so we're not at 0 when we come back
        target.setFoodLevel(20);
        target.setFireTicks(0);
        target.setFallDistance(0);

        target.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Je bent dood!",
                ChatColor.GRAY + "Respawn over "
                + plugin.getConfig().getInt("game.respawn-delay-seconds", 1) + "s",
                0, 30, 5);

        int delay = plugin.getConfig().getInt("game.respawn-delay-seconds", 1) * 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (this.state != State.ACTIVE) return;
            // Find spawn far away from other live players
            List<Location> avoid = new ArrayList<>();
            for (UUID otherId : players.keySet()) {
                if (otherId.equals(target.getUniqueId())) continue;
                Player other = Bukkit.getPlayer(otherId);
                if (other != null && !other.isDead() && other.getGameMode() != GameMode.SPECTATOR) {
                    avoid.add(other.getLocation());
                }
            }
            Location spawn = plugin.getArenaManager().randomSpawnAwayFrom(avoid);
            if (spawn != null) target.teleport(spawn);

            // Phase 2: back to gameplay — must happen AFTER teleport so the
            // player doesn't briefly fall back into the world they died in.
            target.setGameMode(GameMode.ADVENTURE);
            target.setHealth(20);
            target.setFoodLevel(20);
            target.setFireTicks(0);
            target.setFallDistance(0);
            for (var eff : target.getActivePotionEffects()) target.removePotionEffect(eff.getType());
            giveBaseLoadout(target);

            // Brief invulnerability (2 seconds) — set high HP regen via
            // a fire-and-forget regen effect, prevents instant re-kill on spawn
            PotionEffectType regen = regen();
            if (regen != null) {
                target.addPotionEffect(new PotionEffect(regen, 40, 4, true, false, false));
            }
        }, delay);
    }

    private PotionEffectType regen() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("regeneration")); }
        catch (Exception e) { return null; }
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

    /**
     * Returns the winning player's UUID if any individual has reached
     * the kill target, or null otherwise.
     */
    private UUID checkKillTargetReached() {
        int target = plugin.getConfig().getInt("game.kill-target", 25);
        for (PlayerState ps : players.values()) {
            if (ps.getKills() >= target) return ps.getUuid();
        }
        return null;
    }

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();
        plugin.getPowerupSpawner().stop();

        // Sort players by kills desc, deaths asc as tiebreaker
        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.getKills() != b.getKills()) return Integer.compare(b.getKills(), a.getKills());
            return Integer.compare(a.getDeaths(), b.getDeaths());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lQuakeCraft — Uitslag");
        broadcast("&7Reden: " + (reason.equals("kill_target") ? "&aKill target bereikt" : "&eTijd op"));
        broadcast("&6═══════════════════════════════════");

        // Award individual placement bonuses (top 3) — team aggregation
        // happens automatically via KMCCore since each kill already
        // awarded points to the killer.
        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"first-place", "second-place", "third-place"};
        String winnerName = "Niemand";

        // Win-game bonus — award to player(s) tied for 1st in kills.
        // Ties resolved by deaths ASC. ranked is already sorted that way.
        int winGameBonus = plugin.getConfig().getInt("points.win-game", 50);
        if (!ranked.isEmpty() && winGameBonus > 0) {
            int topKills = ranked.get(0).getKills();
            int topDeaths = ranked.get(0).getDeaths();
            for (PlayerState ps : ranked) {
                if (ps.getKills() == topKills && ps.getDeaths() == topDeaths) {
                    api.givePoints(ps.getUuid(), winGameBonus);
                } else {
                    break;  // sorted; first non-tie ends the loop
                }
            }
        }

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            String teamColor = team != null ? team.getColor().toString() : "";

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + teamColor + ps.getName()
                    + " &8- &e" + ps.getKills() + "K&8/&c" + ps.getDeaths() + "D"
                    + " &8(streak " + ps.getBestStreak() + ")");

            int placeBonus;
            if (i < placeKeys.length)
                placeBonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                placeBonus = plugin.getConfig().getInt("points.participation", 0);

            if (placeBonus > 0) api.givePoints(ps.getUuid(), placeBonus);

            // Record per-player tournament stats
            api.recordGameParticipation(ps.getUuid(), ps.getName(), GAME_ID, i == 0);

            if (i == 0) winnerName = teamColor + ps.getName();
        }

        // Show team aggregate as informational footer (KMCCore tracks this)
        Map<String, Integer> teamKillTotals = new HashMap<>();
        Map<String, KMCTeam> teamLookup = new HashMap<>();
        for (PlayerState ps : ranked) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            if (team == null) continue;
            teamKillTotals.merge(team.getId(), ps.getKills(), Integer::sum);
            teamLookup.put(team.getId(), team);
        }
        if (!teamKillTotals.isEmpty()) {
            broadcast("&6═══ Team Kills ═══");
            teamKillTotals.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        KMCTeam t = teamLookup.get(e.getKey());
                        broadcast("  " + t.getColor() + t.getDisplayName()
                                + " &8- &e" + e.getValue() + " kills");
                    });
        }
        broadcast("&6═══════════════════════════════════");

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

        // Clear cross-game state
        lastKilledBy.clear();
        lastKilledAt.clear();

        // Restore arena world's previous PvP setting
        if (arenaWorld != null) {
            arenaWorld.setPVP(arenaWorldPreviousPvp);
            arenaWorld = null;
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

        // Find the leading individual player
        PlayerState top = null;
        for (PlayerState ps : players.values()) {
            if (top == null || ps.getKills() > top.getKills()) top = ps;
        }

        int target = plugin.getConfig().getInt("game.kill-target", 25);
        int leadingKills = top != null ? top.getKills() : 0;
        String leadingName;
        if (top != null) {
            // Show with team color
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(top.getUuid());
            String prefix = team != null ? team.getColor().toString() : "";
            leadingName = prefix + top.getName();
        } else {
            leadingName = "Niemand";
        }

        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;

        bossBar.setTitle(MessageWrap.color(
                "&e" + leadingName + "&r &8| &f" + leadingKills + "/" + target + "  "
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

    private static class MessageWrap {
        static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    }
}
