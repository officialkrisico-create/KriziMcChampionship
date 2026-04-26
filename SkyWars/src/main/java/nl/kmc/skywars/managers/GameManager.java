package nl.kmc.skywars.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.skywars.SkyWarsPlugin;
import nl.kmc.skywars.models.Island;
import nl.kmc.skywars.models.PlayerStats;
import nl.kmc.skywars.models.Team;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * SkyWars match orchestrator.
 *
 * <p>States: IDLE → PREPARING → COUNTDOWN → GRACE → ACTIVE → DEATHMATCH → ENDED
 *
 * <p>Match flow:
 * <ol>
 *   <li>Players assigned to SW teams (mapped from KMCCore teams)</li>
 *   <li>Each team TPed to its island spawn</li>
 *   <li>15s countdown — players frozen</li>
 *   <li>10s grace — chests open, glass barriers down (no PvP yet — done via damage cancel)</li>
 *   <li>Active phase — full PvP, no respawns</li>
 *   <li>After 6 minutes (or last 25%), DEATHMATCH — players given Glowing,
 *       and a particle ring around the play area shrinks toward middle</li>
 *   <li>Last team alive wins</li>
 * </ol>
 *
 * <p>1 life per player. Death = spectator. Team eliminated when all members dead.
 */
public class GameManager {

    public enum State { IDLE, PREPARING, COUNTDOWN, GRACE, ACTIVE, DEATHMATCH, ENDED }

    public static final String GAME_ID = "team_skywars";

    private final SkyWarsPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private final Map<String, Team>      teams = new LinkedHashMap<>();
    private int eliminationCounter;

    private BukkitTask countdownTask;
    private BukkitTask graceTask;
    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BukkitTask deathmatchRingTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  graceSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    /** Killer attribution: when a player gets damaged by another, remember who hit them last (within 10s). */
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Long> lastAttackerMs = new HashMap<>();

    public GameManager(SkyWarsPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State   getState()   { return state; }
    public boolean isActive()   { return state == State.ACTIVE || state == State.DEATHMATCH; }
    public boolean isInMatch()  { return state != State.IDLE && state != State.ENDED; }
    public boolean isPvpAllowed(){ return state == State.ACTIVE || state == State.DEATHMATCH; }
    public PlayerStats get(UUID uuid) { return stats.get(uuid); }
    public Map<UUID, PlayerStats> getStats() { return Collections.unmodifiableMap(stats); }
    public Map<String, Team> getTeams() { return Collections.unmodifiableMap(teams); }

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
    private PotionEffectType glowing() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("glowing")); }
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
        teams.clear();
        eliminationCounter = 0;
        lastAttacker.clear();
        lastAttackerMs.clear();

        // Map online players → SW teams using KMCCore teams as basis
        // Build SW Teams: one SW Team per island, mapped to a KMCCore team
        Map<String, KMCTeam> kmcByPlayer = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var t = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (t != null) kmcByPlayer.put(p.getUniqueId().toString(), t);
        }

        // Distribute KMCCore teams over available islands
        var islands = new ArrayList<>(plugin.getArenaManager().getIslands().values());
        if (islands.size() < 2) return "Minimaal 2 islands geconfigureerd.";

        // First, find unique KMCCore teams that have at least one online player
        Set<String> kmcTeamsWithPlayers = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var t = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (t != null) kmcTeamsWithPlayers.add(t.getId());
        }

        // Assign each KMC team to one island (round-robin)
        int islandIdx = 0;
        Map<String, Team> kmcToSwTeam = new HashMap<>();
        for (String kmcId : kmcTeamsWithPlayers) {
            if (islandIdx >= islands.size()) break;
            Island island = islands.get(islandIdx++);
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeam(kmcId);
            if (kmcTeam == null) continue;
            Team swTeam = new Team(kmcId, kmcTeam.getDisplayName(), kmcTeam.getColor(), island);
            teams.put(kmcId, swTeam);
            kmcToSwTeam.put(kmcId, swTeam);
        }

        // For players without a KMCCore team, distribute round-robin across remaining islands
        // (or wrap around if all islands taken)
        List<Team> teamList = new ArrayList<>(teams.values());
        int wrapIdx = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            Team target;
            if (kmcTeam != null && kmcToSwTeam.containsKey(kmcTeam.getId())) {
                target = kmcToSwTeam.get(kmcTeam.getId());
            } else if (!teamList.isEmpty()) {
                target = teamList.get(wrapIdx++ % teamList.size());
            } else {
                continue;
            }
            target.addMember(p.getUniqueId());
            stats.put(p.getUniqueId(), new PlayerStats(p.getUniqueId(), p.getName(), target.getId()));
        }

        if (stats.isEmpty()) return "Geen spelers online.";
        if (teams.size() < 2) return "Minimaal 2 teams nodig (heb je KMCCore teams ingesteld?)";

        plugin.getKmcCore().getApi().acquireScoreboard("skywars");

        // Stock chests
        state = State.PREPARING;
        broadcast("&6[SkyWars] &eVoorraden worden in chests geplaatst...");
        int stocked = plugin.getChestStocker().stockAll();
        broadcast("&7" + stocked + " chests gevuld met loot.");

        beginCountdown();
        return null;
    }

    private void beginCountdown() {
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        // TP each player to their team's island spawn + freeze
        PotionEffectType slowType = slow();
        for (Team t : teams.values()) {
            for (UUID uuid : t.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                p.teleport(t.getIsland().getSpawn());
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
                ChatColor.YELLOW + "" + ChatColor.BOLD + "SkyWars start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[SkyWars] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "SkyWars start over " + countdownSeconds + "s");

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
                beginGrace();
            }
        }, 20L, 20L);
    }

    private void beginGrace() {
        state = State.GRACE;
        graceSeconds = plugin.getConfig().getInt("game.grace-seconds", 10);

        // Lift slowness, enable PvE-only mode
        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(true);

        PotionEffectType slowType = slow();
        for (PlayerStats ps : stats.values()) {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "OPEN CHESTS!",
                    ChatColor.YELLOW + "Geen PvP voor " + graceSeconds + "s",
                    0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }
        bossBar.setColor(BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GRACE — geen PvP — " + graceSeconds + "s");
        broadcast("&a&l[SkyWars] &eOpen chests! &7Geen PvP voor &6" + graceSeconds + " &7seconden.");

        graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            graceSeconds--;
            bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD
                    + "GRACE — geen PvP — " + graceSeconds + "s");
            if (graceSeconds <= 0) {
                graceTask.cancel();
                beginActive();
            }
        }, 20L, 20L);

        // Void check is active even in grace (someone could fall off their island)
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoidFalls, 5L, 5L);
    }

    private void beginActive() {
        state = State.ACTIVE;
        gameStartMs = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 600);

        bossBar.setColor(BarColor.RED);
        updateBossBar();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (stats.get(p.getUniqueId()) == null) continue;
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "PvP!",
                    ChatColor.YELLOW + "Vecht!", 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1.5f);
        }
        broadcast("&c&l[SkyWars] &ePvP is aan! &7Last team standing wins!");

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            // Trigger deathmatch when 25% time remains or 6 min elapsed
            if (state == State.ACTIVE) {
                int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 600);
                int dmTrigger = plugin.getConfig().getInt("game.deathmatch-trigger-seconds", 360);
                if (remainingSeconds <= totalSec - dmTrigger) {
                    beginDeathmatch();
                }
            }
            if (remainingSeconds <= 0) endGame("time_limit");
        }, 20L, 20L);
    }

    private void beginDeathmatch() {
        if (state != State.ACTIVE) return;
        state = State.DEATHMATCH;

        broadcast("&c&l[DEATHMATCH] &eDe ring sluit zich!");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "DEATHMATCH",
                    ChatColor.YELLOW + "Glowing actief — vind elkaar!",
                    10, 60, 20);
            p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
        }

        // Apply Glowing to all alive players so they're visible through walls
        PotionEffectType glow = glowing();
        if (glow != null) {
            for (PlayerStats ps : stats.values()) {
                if (!ps.isAlive()) continue;
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p == null) continue;
                p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
            }
        }

        // Particle ring shrinking around middle (cosmetic — actual elimination
        // still by void / PvP)
        deathmatchRingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderShrinkingRing, 20L, 20L);
    }

    private double currentRingRadius = 0;

    private void renderShrinkingRing() {
        var middle = plugin.getArenaManager().getMiddleSpawn();
        if (middle == null) return;
        if (currentRingRadius <= 0) {
            currentRingRadius = plugin.getConfig().getDouble("game.deathmatch-ring-start", 40);
        }
        // Shrink rate
        double shrinkPerSec = plugin.getConfig().getDouble("game.deathmatch-ring-shrink-per-sec", 0.5);
        currentRingRadius = Math.max(8, currentRingRadius - shrinkPerSec);

        // Draw ring
        for (int deg = 0; deg < 360; deg += 5) {
            double rad = Math.toRadians(deg);
            double x = middle.getX() + currentRingRadius * Math.cos(rad);
            double z = middle.getZ() + currentRingRadius * Math.sin(rad);
            for (double y = middle.getY(); y < middle.getY() + 5; y += 1.5) {
                middle.getWorld().spawnParticle(Particle.FLAME,
                        x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    // ----------------------------------------------------------------
    // Damage attribution
    // ----------------------------------------------------------------

    public void recordAttack(UUID victim, UUID attacker) {
        if (victim.equals(attacker)) return;
        lastAttacker.put(victim, attacker);
        lastAttackerMs.put(victim, System.currentTimeMillis());
    }

    /** Get the player who last hit `victim` within the last 10s (or null). */
    public Player getRecentAttacker(UUID victim) {
        Long when = lastAttackerMs.get(victim);
        if (when == null || System.currentTimeMillis() - when > 10000) return null;
        UUID attackerId = lastAttacker.get(victim);
        return attackerId != null ? Bukkit.getPlayer(attackerId) : null;
    }

    // ----------------------------------------------------------------
    // Void / death handling
    // ----------------------------------------------------------------

    private void checkVoidFalls() {
        if (state == State.IDLE || state == State.ENDED) return;
        int voidY = plugin.getArenaManager().getVoidYLevel();
        for (UUID uuid : new ArrayList<>(stats.keySet())) {
            PlayerStats ps = stats.get(uuid);
            if (ps == null || !ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getLocation().getY() < voidY) {
                handleDeath(p, getRecentAttacker(uuid), "viel in de void");
            }
        }
    }

    /**
     * Player died. Records stats, awards kill if attributable, eliminates.
     * Then checks win condition.
     */
    public void handleDeath(Player victim, Player killer, String reason) {
        if (state == State.IDLE || state == State.ENDED) return;
        PlayerStats ps = stats.get(victim.getUniqueId());
        if (ps == null || !ps.isAlive()) return;

        ps.eliminate(eliminationCounter++);
        victim.setGameMode(GameMode.SPECTATOR);
        victim.getInventory().clear();
        for (var eff : victim.getActivePotionEffects()) victim.removePotionEffect(eff.getType());
        victim.setHealth(20);
        victim.setFoodLevel(20);

        if (killer != null && killer != victim) {
            PlayerStats killerStats = stats.get(killer.getUniqueId());
            if (killerStats != null) killerStats.incrementKills();
            int killPts = plugin.getConfig().getInt("points.per-kill", 25);
            if (killPts > 0) plugin.getKmcCore().getApi().givePoints(killer.getUniqueId(), killPts);
            plugin.getKmcCore().getHallOfFameManager().recordKill(killer);
            broadcast("&c☠ &7" + victim.getName() + " &8← &e" + killer.getName());
        } else {
            broadcast("&c☠ &7" + victim.getName() + " &7" + reason);
        }

        victim.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Eliminated!",
                ChatColor.YELLOW + reason, 10, 50, 10);

        checkWinCondition();
        updateBossBar();
    }

    private void checkWinCondition() {
        // How many teams still have at least one alive member?
        Set<String> aliveTeams = new HashSet<>();
        for (PlayerStats ps : stats.values()) {
            if (!ps.isAlive()) continue;
            aliveTeams.add(ps.getTeamId());
        }
        if (aliveTeams.size() <= 1) {
            endGame(aliveTeams.size() == 1 ? "last_team_standing" : "no_survivors");
        }
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;
        cancelTasks();

        // Restore world PvP
        var world = plugin.getArenaManager().getWorld();
        if (world != null) world.setPVP(false);

        // Rank teams: alive count desc, then total kills desc
        var teamList = new ArrayList<>(teams.values());
        teamList.sort((a, b) -> {
            int aAlive = countAlive(a);
            int bAlive = countAlive(b);
            if (aAlive != bAlive) return Integer.compare(bAlive, aAlive);
            int aKills = teamKills(a);
            int bKills = teamKills(b);
            if (aKills != bKills) return Integer.compare(bKills, aKills);
            // Tiebreak: average elimination order (later = better)
            return Integer.compare(avgElimOrder(b), avgElimOrder(a));
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lSkyWars — Uitslag");
        broadcast("&7Reden: " + (reason.equals("last_team_standing") ? "&aLaatste team over"
                : reason.equals("time_limit") ? "&eTijd op" : "&7Beëindigd"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"team-first-place", "team-second-place", "team-third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < teamList.size(); i++) {
            Team team = teamList.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + team.getChatColor() + team.getDisplayName()
                    + " &8- &e" + countAlive(team) + " alive &7(" + teamKills(team) + " kills)");

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

        // Top killers footer
        broadcast("&6Top kills:");
        var topKillers = new ArrayList<>(stats.values());
        topKillers.sort((a, b) -> Integer.compare(b.getKills(), a.getKills()));
        for (int i = 0; i < Math.min(3, topKillers.size()); i++) {
            PlayerStats ps = topKillers.get(i);
            if (ps.getKills() == 0) break;
            broadcast("  &e#" + (i + 1) + " &f" + ps.getName() + " &8- &e" + ps.getKills() + " kills");
        }

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint SkyWars!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private int countAlive(Team team) {
        int n = 0;
        for (UUID uuid : team.getMembers()) {
            PlayerStats ps = stats.get(uuid);
            if (ps != null && ps.isAlive()) n++;
        }
        return n;
    }

    private int teamKills(Team team) {
        int n = 0;
        for (UUID uuid : team.getMembers()) {
            PlayerStats ps = stats.get(uuid);
            if (ps != null) n += ps.getKills();
        }
        return n;
    }

    private int avgElimOrder(Team team) {
        int total = 0; int count = 0;
        for (UUID uuid : team.getMembers()) {
            PlayerStats ps = stats.get(uuid);
            if (ps != null && !ps.isAlive()) {
                total += ps.getEliminationOrder();
                count++;
            }
        }
        return count > 0 ? total / count : 0;
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("skywars");
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

        stats.clear();
        teams.clear();
        lastAttacker.clear();
        lastAttackerMs.clear();
        currentRingRadius = 0;
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame("force_stop");
    }

    private void cancelTasks() {
        if (countdownTask      != null) { countdownTask.cancel();      countdownTask = null; }
        if (graceTask          != null) { graceTask.cancel();          graceTask = null; }
        if (gameTimerTask      != null) { gameTimerTask.cancel();      gameTimerTask = null; }
        if (voidCheckTask      != null) { voidCheckTask.cancel();      voidCheckTask = null; }
        if (deathmatchRingTask != null) { deathmatchRingTask.cancel(); deathmatchRingTask = null; }
    }

    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        int aliveTeams = 0;
        int alivePlayers = 0;
        for (Team t : teams.values()) {
            int alive = countAlive(t);
            if (alive > 0) {
                aliveTeams++;
                alivePlayers += alive;
            }
        }
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        String stateName = state == State.GRACE ? "&aGRACE" : state == State.ACTIVE ? "&cPVP"
                : state == State.DEATHMATCH ? "&4DEATHMATCH" : state.name();
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                stateName + " &8| &e" + aliveTeams + " teams (" + alivePlayers + " alive)"
                + " &8| &b" + String.format("%02d:%02d", min, sec)));
        if (state == State.ACTIVE || state == State.DEATHMATCH) {
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 600);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingSeconds / totalSec)));
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }
}
