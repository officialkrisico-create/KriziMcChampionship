package nl.kmc.bridge.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.models.BridgeTeam;
import nl.kmc.bridge.models.PlayerStats;
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
 * The Bridge match orchestrator.
 *
 * <p>States: IDLE → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * <p>Win conditions:
 * <ul>
 *   <li>First team to N goals wins (default 5)</li>
 *   <li>Timer expires → most goals wins (tiebreak: most kills)</li>
 * </ul>
 *
 * <p>Player flow:
 * - Spawn at team's spawn with kit
 * - Walk/build across to opponent's goal
 * - Jump in opponent's goal hole = score for your team
 * - After scoring: TP back to team spawn, kit refilled
 * - Death (PvP, void, lava): TP back to team spawn, brief invuln
 */
public class GameManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "the_bridge";

    private final TheBridgePlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();

    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    /** Per-player goal-cooldown so they don't double-trigger from a held jump. */
    private final Map<UUID, Long> goalCooldown = new HashMap<>();

    public GameManager(TheBridgePlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State getState() { return state; }
    public boolean isActive() { return state == State.ACTIVE; }
    public PlayerStats get(UUID uuid) { return stats.get(uuid); }
    public Map<UUID, PlayerStats> getStats() { return Collections.unmodifiableMap(stats); }

    private PotionEffectType slow() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("slowness")); }
        catch (Exception e) { return null; }
    }
    private PotionEffectType regen() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("regeneration")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public String startGame() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getArenaManager().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getReadinessReport();

        stats.clear();
        goalCooldown.clear();

        // Reset team goal counts + member sets
        for (BridgeTeam t : plugin.getArenaManager().getTeams().values()) {
            t.resetGoals();
            t.getMembers().clear();
        }

        // Distribute online players into Bridge teams.
        // Strategy: try to map KMCCore teams → Bridge teams by id match.
        // Fall back to round-robin distribution for any remaining players.
        var bridgeTeams = new ArrayList<>(plugin.getArenaManager().getTeams().values());
        int bridgeIdx = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            // First try matching by KMCCore team id
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            BridgeTeam target = null;
            if (kmcTeam != null) target = plugin.getArenaManager().getTeam(kmcTeam.getId());
            if (target == null) {
                // Fall back to round-robin
                target = bridgeTeams.get(bridgeIdx % bridgeTeams.size());
                bridgeIdx++;
            }
            target.addMember(p.getUniqueId());
            stats.put(p.getUniqueId(), new PlayerStats(p.getUniqueId(), p.getName(), target.getId()));
        }

        if (stats.isEmpty()) return "Geen spelers online.";

        plugin.getKmcCore().getApi().acquireScoreboard("bridge");
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        // TP everyone to their team spawn + freeze
        PotionEffectType slowType = slow();
        for (var entry : plugin.getArenaManager().getTeams().entrySet()) {
            BridgeTeam team = entry.getValue();
            for (UUID uuid : team.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                p.teleport(team.getSpawn());
                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                p.setHealth(20);
                p.setFoodLevel(20);
                p.setFallDistance(0);
                int ticks = countdownSeconds * 20;
                if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            }
        }

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "The Bridge start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[The Bridge] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "The Bridge start over " + countdownSeconds + "s");

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
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 480);

        // Enable PvP in arena world
        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(true);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        for (BridgeTeam team : plugin.getArenaManager().getTeams().values()) {
            for (UUID uuid : team.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                if (slowType != null) p.removePotionEffect(slowType);
                plugin.getKitManager().giveKit(p, team);
                // Override the kit's iron armor with team-colored leather (no helmet)
                try {
                    nl.kmc.kmccore.util.TeamArmor.applyChestLegsBoots(p);
                    p.getInventory().setHelmet(null);  // face stays visible
                } catch (Throwable ignored) {}
                p.sendTitle(team.getChatColor() + "" + ChatColor.BOLD + "GO!",
                        ChatColor.YELLOW + "Score in tegenstander's hole!", 0, 30, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
            }
        }
        broadcast("&a&l[The Bridge] &eGO! &7Eerste team naar &6"
                + plugin.getConfig().getInt("game.goals-to-win", 5) + " &7goals wint!");

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) endGame("time_limit");
        }, 20L, 20L);

        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoidFalls, 5L, 5L);
    }

    // ----------------------------------------------------------------
    // Movement detection
    // ----------------------------------------------------------------

    /** Called on PlayerMoveEvent. Checks if player entered any goal region. */
    public void handleMovement(Player p, Location to) {
        if (state != State.ACTIVE) return;
        PlayerStats ps = stats.get(p.getUniqueId());
        if (ps == null) return;

        BridgeTeam playerTeam = plugin.getArenaManager().getTeam(ps.getTeamId());
        if (playerTeam == null) return;

        BridgeTeam goalTeam = plugin.getArenaManager().findGoalTeam(to);
        if (goalTeam == null) return;

        // You can't score in your own goal
        if (goalTeam.getId().equals(playerTeam.getId())) return;

        // Cooldown to prevent double-scoring on the same dive
        long now = System.currentTimeMillis();
        Long expire = goalCooldown.get(p.getUniqueId());
        if (expire != null && now < expire) return;
        goalCooldown.put(p.getUniqueId(), now + 3000);

        scoreGoal(p, ps, playerTeam, goalTeam);
    }

    private void scoreGoal(Player p, PlayerStats ps, BridgeTeam scoringTeam, BridgeTeam goalTeam) {
        scoringTeam.addGoal();
        ps.addGoal();

        int goalPoints = plugin.getConfig().getInt("points.per-goal", 50);
        plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), goalPoints);

        // Per-round-win: each goal counts as a "round win" — every member
        // of the scoring team gets a small bonus.
        int roundWinBonus = plugin.getConfig().getInt("points.per-round-win", 10);
        if (roundWinBonus > 0) {
            for (UUID memberId : scoringTeam.getMembers()) {
                if (memberId.equals(p.getUniqueId())) continue;  // scorer already got per-goal
                plugin.getKmcCore().getApi().givePoints(memberId, roundWinBonus);
            }
            // Scorer also gets the round-win bonus on top of per-goal
            plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), roundWinBonus);
        }

        broadcast("&6⚽ " + scoringTeam.getChatColor() + p.getName()
                + " &fscoorde voor &6" + scoringTeam.getChatColor() + scoringTeam.getDisplayName()
                + "&f! &7(" + scoringTeam.getGoalsScored() + "/" + plugin.getConfig().getInt("game.goals-to-win", 5) + ")");

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendTitle(scoringTeam.getChatColor() + "" + ChatColor.BOLD + "GOAL!",
                    ChatColor.YELLOW + p.getName() + " scoorde!",
                    5, 40, 10);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
        }

        // TP scorer back to spawn + refill kit
        respawnPlayer(p, scoringTeam, false);

        updateBossBar();

        // Win check
        int target = plugin.getConfig().getInt("game.goals-to-win", 5);
        if (scoringTeam.getGoalsScored() >= target) {
            endGame("goal_target");
        }
    }

    /**
     * Respawn flow — TP to team spawn, refresh kit, brief invuln.
     * Used both for goal scoring and death.
     */
    private void respawnPlayer(Player p, BridgeTeam team, boolean wasDeath) {
        p.setFallDistance(0);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
        p.teleport(team.getSpawn());
        p.setGameMode(GameMode.ADVENTURE);
        plugin.getKitManager().giveKit(p, team);
        // Override iron armor with team-colored leather (consistent with launch())
        try {
            nl.kmc.kmccore.util.TeamArmor.applyChestLegsBoots(p);
            p.getInventory().setHelmet(null);
        } catch (Throwable ignored) {}

        // Brief invuln so spawncamping isn't instant-kill
        PotionEffectType regenType = regen();
        if (regenType != null) {
            p.addPotionEffect(new PotionEffect(regenType, 60, 4, true, false, false));
        }

        if (wasDeath) {
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Dood!",
                    ChatColor.GRAY + "Respawned bij " + team.getChatColor() + team.getDisplayName(),
                    0, 25, 5);
        }
    }

    private void checkVoidFalls() {
        if (state != State.ACTIVE) return;
        int voidY = plugin.getArenaManager().getVoidYLevel();
        for (UUID uuid : new ArrayList<>(stats.keySet())) {
            PlayerStats ps = stats.get(uuid);
            if (ps == null) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getLocation().getY() < voidY) {
                handleDeath(p, null);  // null = void death, no killer
            }
        }
    }

    /**
     * Player died (void fall, killed by another player, etc).
     * Records stats, respawns at team spawn.
     */
    public void handleDeath(Player p, Player killer) {
        if (state != State.ACTIVE) return;
        PlayerStats ps = stats.get(p.getUniqueId());
        if (ps == null) return;
        BridgeTeam team = plugin.getArenaManager().getTeam(ps.getTeamId());
        if (team == null) return;

        ps.addDeath();

        if (killer != null) {
            PlayerStats killerStats = stats.get(killer.getUniqueId());
            if (killerStats != null) {
                killerStats.addKill();
                int killPoints = plugin.getConfig().getInt("points.per-kill", 8);
                plugin.getKmcCore().getApi().givePoints(killer.getUniqueId(), killPoints);
                plugin.getKmcCore().getHallOfFameManager().recordKill(killer);
            }

            // Assist credit: if someone else hit the victim recently
            // (and isn't the killer), they get assist points.
            AssistManager am = plugin.getAssistManager();
            if (am != null) {
                long windowMs = plugin.getConfig().getLong("points.assist-window-seconds", 8) * 1000L;
                UUID assistUuid = am.consumeAssist(p.getUniqueId(), killer.getUniqueId(), windowMs);
                if (assistUuid != null) {
                    int assistPts = plugin.getConfig().getInt("points.per-assist", 20);
                    if (assistPts > 0) {
                        plugin.getKmcCore().getApi().givePoints(assistUuid, assistPts);
                        Player assister = Bukkit.getPlayer(assistUuid);
                        if (assister != null) {
                            assister.sendActionBar(net.kyori.adventure.text.Component.text(
                                    ChatColor.AQUA + "✦ Assist! +" + assistPts));
                            assister.playSound(assister.getLocation(),
                                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.5f);
                        }
                    }
                }
            }

            broadcast("&c☠ &7" + p.getName() + " &8← &e" + killer.getName());
        } else {
            broadcast("&c☠ &7" + p.getName() + " &8viel in de void");
            // Void death — also clear assist tracking so a stale hit doesn't
            // get awarded incorrectly later.
            if (plugin.getAssistManager() != null) {
                plugin.getAssistManager().clearVictim(p.getUniqueId());
            }
        }

        respawnPlayer(p, team, true);
    }

    public void onBlockPlaced(Player p) {
        if (state != State.ACTIVE) return;
        PlayerStats ps = stats.get(p.getUniqueId());
        if (ps != null) ps.addBlockPlaced();
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();

        // Disable PvP in arena world (restore to default)
        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(false);

        // Rank teams by goals desc, then kills desc
        var teamList = new ArrayList<>(plugin.getArenaManager().getTeams().values());
        teamList.sort((a, b) -> {
            if (a.getGoalsScored() != b.getGoalsScored())
                return Integer.compare(b.getGoalsScored(), a.getGoalsScored());
            int aKills = teamKills(a);
            int bKills = teamKills(b);
            return Integer.compare(bKills, aKills);
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lThe Bridge — Uitslag");
        broadcast("&7Reden: " + (reason.equals("goal_target") ? "&aTeam bereikte goal target"
                : reason.equals("time_limit") ? "&eTijd op" : "&7Beëindigd"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"team-first-place", "team-second-place", "team-third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < teamList.size(); i++) {
            BridgeTeam team = teamList.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + team.getChatColor() + team.getDisplayName()
                    + " &8- &e" + team.getGoalsScored() + " goals &7(" + teamKills(team) + " kills)");

            int placeBonus;
            if (i < placeKeys.length)
                placeBonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                placeBonus = plugin.getConfig().getInt("points.team-participation", 25);

            for (UUID uuid : team.getMembers()) {
                if (placeBonus > 0) api.givePoints(uuid, placeBonus);
                PlayerStats ps = stats.get(uuid);
                String name = ps != null ? ps.getName() : uuid.toString();
                api.recordGameParticipation(uuid, name, GAME_ID, i == 0);
            }

            if (i == 0) winnerName = team.getChatColor() + team.getDisplayName();
        }
        broadcast("&6═══════════════════════════════════");

        // Top players (kills + goals)
        broadcast("&6Top spelers:");
        var topPlayers = new ArrayList<>(stats.values());
        topPlayers.sort((a, b) -> {
            int scoreA = a.getKills() * 2 + a.getGoals() * 5;
            int scoreB = b.getKills() * 2 + b.getGoals() * 5;
            return Integer.compare(scoreB, scoreA);
        });
        for (int i = 0; i < Math.min(3, topPlayers.size()); i++) {
            PlayerStats ps = topPlayers.get(i);
            broadcast("  &e#" + (i + 1) + " &f" + ps.getName()
                    + " &8- &e" + ps.getKills() + "K/" + ps.getDeaths() + "D &7| &6"
                    + ps.getGoals() + " goals");
        }

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint The Bridge!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private int teamKills(BridgeTeam team) {
        int total = 0;
        for (UUID uuid : team.getMembers()) {
            PlayerStats ps = stats.get(uuid);
            if (ps != null) total += ps.getKills();
        }
        return total;
    }



    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("bridge");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID uuid : stats.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            p.setHealth(20);
            p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        }

        // Async clear placed blocks
        var world = plugin.getArenaManager().getWorld();
        if (world != null) {
            plugin.getBlockTracker().clearAllAsync(world, () -> {});
        }

        // Reset team state
        for (BridgeTeam t : plugin.getArenaManager().getTeams().values()) {
            t.resetGoals();
            t.getMembers().clear();
        }

        stats.clear();
        goalCooldown.clear();
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
        if (voidCheckTask != null) { voidCheckTask.cancel(); voidCheckTask = null; }
    }

    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        StringBuilder sb = new StringBuilder();
        int target = plugin.getConfig().getInt("game.goals-to-win", 5);
        boolean first = true;
        for (BridgeTeam t : plugin.getArenaManager().getTeams().values()) {
            if (!first) sb.append(" &8| ");
            sb.append(t.getChatColor()).append(t.getDisplayName())
                    .append("&f: ").append(t.getGoalsScored()).append("/").append(target);
            first = false;
        }
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        sb.append(" &8| &b").append(String.format("%02d:%02d", min, sec));
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&', sb.toString()));

        if (state == State.ACTIVE) {
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 480);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingSeconds / totalSec)));
        }
    }


    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }
}
