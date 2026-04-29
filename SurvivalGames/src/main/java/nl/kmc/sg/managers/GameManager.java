package nl.kmc.sg.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.sg.SurvivalGamesPlugin;
import nl.kmc.sg.models.PlayerStats;
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
import java.util.Objects;

/**
 * Survival Games match orchestrator.
 *
 * <p>States: IDLE → PREPARING → COUNTDOWN → GRACE → ACTIVE → DEATHMATCH → ENDED
 *
 * <p>Match flow:
 * <ol>
 *   <li>Plugin async-stocks chests across the entire arena (PREPARING)</li>
 *   <li>Players placed on spawn pedestals (COUNTDOWN, 30s frozen)</li>
 *   <li>Bloodbath grace period — 10s where PvP is on but pedestals are protected</li>
 *   <li>Active phase — full map, 8 minutes default. World border = arena
 *       border radius. Players outside the border take damage.</li>
 *   <li>Deathmatch — last 2 minutes, world border shrinks toward
 *       cornucopia center to force fights. Glowing on all alive.</li>
 *   <li>Last alive wins.</li>
 * </ol>
 *
 * <p>1 life per player, no respawns. Solo only.
 */
public class GameManager {

    public enum State { IDLE, PREPARING, COUNTDOWN, GRACE, ACTIVE, DEATHMATCH, ENDED }

    public static final String GAME_ID = "survival_games";

    private final SurvivalGamesPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private int eliminationCounter;

    private BukkitTask countdownTask;
    private BukkitTask graceTask;
    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  graceSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Long> lastAttackerMs = new HashMap<>();

    /** Saved world border state to restore on cleanup. */
    private double  savedBorderSize  = -1;
    private double  savedBorderCx    = 0;
    private double  savedBorderCz    = 0;

    public GameManager(SurvivalGamesPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State   getState()   { return state; }
    public boolean isInMatch()  { return state != State.IDLE && state != State.ENDED; }
    public boolean isActive()   { return state == State.ACTIVE || state == State.DEATHMATCH; }
    public boolean isPvpAllowed() { return state == State.GRACE || state == State.ACTIVE || state == State.DEATHMATCH; }
    public PlayerStats get(UUID uuid) { return stats.get(uuid); }
    public Map<UUID, PlayerStats> getStats() { return Collections.unmodifiableMap(stats); }

    private PotionEffectType slow() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("slowness")); }
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
        var arena = plugin.getArenaManager().getArena();
        if (!arena.isReady()) return "Arena niet klaar:\n" + arena.getReadinessReport();

        stats.clear();
        eliminationCounter = 0;
        lastAttacker.clear();
        lastAttackerMs.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            stats.put(p.getUniqueId(), new PlayerStats(p.getUniqueId(), p.getName()));
        }
        if (stats.size() < 2) return "Minimaal 2 spelers nodig.";

        plugin.getKmcCore().getApi().acquireScoreboard("sg");
        state = State.PREPARING;

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Survival Games — voorbereiden...",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Survival Games] &eVoorraden worden in chests geplaatst...");
        plugin.getChestStocker().stockAllAsync(this::beginCountdown);
        return null;
    }

    private void beginCountdown() {
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 30);

        var arena = plugin.getArenaManager().getArena();

        // Save world border state before we override
        var world = arena.getWorld();
        var border = world.getWorldBorder();
        savedBorderSize = border.getSize();
        savedBorderCx = border.getCenter().getX();
        savedBorderCz = border.getCenter().getZ();

        // Set border to match the arena
        var cor = arena.getCornucopiaCenter();
        border.setCenter(cor.getX(), cor.getZ());
        border.setSize(arena.getBorderRadius() * 2);
        border.setDamageBuffer(0);
        border.setDamageAmount(2.0);
        border.setWarningDistance(5);

        // TP players to spawn pedestals + freeze
        var pedestals = new ArrayList<>(arena.getSpawnPedestals());
        Collections.shuffle(pedestals);
        int i = 0;
        PotionEffectType slowType = slow();
        for (UUID uuid : stats.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (i >= pedestals.size()) {
                // Not enough pedestals — TP to cornucopia center
                p.teleport(cor);
            } else {
                p.teleport(pedestals.get(i));
            }
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setFallDistance(0);
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            i++;
        }

        bossBar.setColor(BarColor.YELLOW);
        bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + "Bloodbath start over " + countdownSeconds + "s");

        broadcast("&6[Survival Games] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 30));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + "Bloodbath start over " + countdownSeconds + "s");

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

        // Start void check now (in case someone manages to fall during countdown)
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoidFalls, 5L, 5L);
    }

    private void beginGrace() {
        state = State.GRACE;
        graceSeconds = plugin.getConfig().getInt("game.grace-seconds", 10);

        var world = plugin.getArenaManager().getArena().getWorld();
        if (world != null) world.setPVP(true);

        PotionEffectType slowType = slow();
        for (PlayerStats ps : stats.values()) {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "BLOODBATH!",
                    ChatColor.YELLOW + "Naar de cornucopia!", 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }
        bossBar.setColor(BarColor.RED);
        bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "BLOODBATH — " + graceSeconds + "s");
        broadcast("&c&l[BLOODBATH] &eVecht voor de cornucopia!");

        graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            graceSeconds--;
            bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "BLOODBATH — " + graceSeconds + "s");
            if (graceSeconds <= 0) {
                graceTask.cancel();
                beginActive();
            }
        }, 20L, 20L);
    }

    private void beginActive() {
        state = State.ACTIVE;
        gameStartMs = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 600);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (stats.get(p.getUniqueId()) == null) continue;
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "Verspreid!",
                    ChatColor.YELLOW + "Verken het terrein", 0, 30, 10);
        }
        broadcast("&a&l[SG] &eDe bloodbath is voorbij. Verspreid en zoek loot!");

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();

            // Trigger deathmatch when configured time elapsed
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 600);
            int dmTrigger = plugin.getConfig().getInt("game.deathmatch-trigger-seconds", 360);
            if (state == State.ACTIVE && remainingSeconds <= totalSec - dmTrigger) {
                beginDeathmatch();
            }

            if (remainingSeconds <= 0) endGame("time_limit");
        }, 20L, 20L);
    }

    private void beginDeathmatch() {
        if (state != State.ACTIVE) return;
        state = State.DEATHMATCH;

        broadcast("&c&l[DEATHMATCH] &eDe border krimpt!");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "DEATHMATCH",
                    ChatColor.YELLOW + "Border krimpt — vind elkaar!",
                    10, 60, 20);
            p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
        }

        // Apply Glowing
        PotionEffectType glow = glowing();
        if (glow != null) {
            for (PlayerStats ps : stats.values()) {
                if (!ps.isAlive()) continue;
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p == null) continue;
                p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
            }
        }

        // Shrink the world border
        var arena = plugin.getArenaManager().getArena();
        var border = arena.getWorld().getWorldBorder();
        double minRadius = arena.getBorderMinRadius();
        if (minRadius <= 0) minRadius = 30;  // fallback
        long shrinkSeconds = plugin.getConfig().getLong("game.deathmatch-shrink-seconds", 90);
        border.setSize(minRadius * 2, shrinkSeconds);
    }

    // ----------------------------------------------------------------
    // Damage attribution
    // ----------------------------------------------------------------

    public void recordAttack(UUID victim, UUID attacker) {
        if (victim.equals(attacker)) return;
        lastAttacker.put(victim, attacker);
        lastAttackerMs.put(victim, System.currentTimeMillis());
    }

    public Player getRecentAttacker(UUID victim) {
        Long when = lastAttackerMs.get(victim);
        if (when == null || System.currentTimeMillis() - when > 10000) return null;
        UUID attackerId = lastAttacker.get(victim);
        return attackerId != null ? Bukkit.getPlayer(attackerId) : null;
    }

    // ----------------------------------------------------------------
    // Void / death
    // ----------------------------------------------------------------

    private void checkVoidFalls() {
        if (state == State.IDLE || state == State.ENDED) return;
        int voidY = plugin.getArenaManager().getArena().getVoidYLevel();
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
            int killPts = plugin.getConfig().getInt("points.per-kill", 50);
            if (killPts > 0) plugin.getKmcCore().getApi().givePoints(killer.getUniqueId(), killPts);
            plugin.getKmcCore().getHallOfFameManager().recordKill(killer);
            broadcast("&c☠ &7" + victim.getName() + " &8← &e" + killer.getName());
        } else {
            broadcast("&c☠ &7" + victim.getName() + " &7" + reason);
        }

        victim.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Eliminated!",
                ChatColor.YELLOW + reason, 10, 50, 10);

        // Living-while-someone-dies: every still-alive player (except
        // the dying one) gets this bonus. Replaces the old
        // "survival-bonus" key — renamed for clarity.
        int livingBonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (livingBonus > 0) {
            KMCApi api = plugin.getKmcCore().getApi();
            for (PlayerStats other : stats.values()) {
                if (!other.isAlive()) continue;
                if (other.getUuid().equals(victim.getUniqueId())) continue;
                api.givePoints(other.getUuid(), livingBonus);
                Player op = Bukkit.getPlayer(other.getUuid());
                if (op != null) {
                    op.sendActionBar(net.kyori.adventure.text.Component.text(
                            ChatColor.AQUA + "+" + livingBonus + " survivor bonus"));
                    op.playSound(op.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                }
            }
        }

        // Living-while-team-dies: when the dying player was the LAST
        // member of their team alive, every still-alive player on
        // OTHER teams gets the team-elimination bonus.
        var deadTeam = plugin.getKmcCore().getTeamManager()
                .getTeamByPlayer(victim.getUniqueId());
        if (deadTeam != null) {
            boolean teamFullyDead = stats.values().stream()
                    .filter(PlayerStats::isAlive)
                    .map(alivePs -> plugin.getKmcCore().getTeamManager()
                            .getTeamByPlayer(alivePs.getUuid()))
                    .filter(Objects::nonNull)
                    .noneMatch(t -> t.getId().equals(deadTeam.getId()));

            if (teamFullyDead) {
                int teamBonus = plugin.getConfig()
                        .getInt("points.living-while-team-dies", 20);
                if (teamBonus > 0) {
                    KMCApi api = plugin.getKmcCore().getApi();
                    broadcast("&c☠ &7Team &f" + deadTeam.getDisplayName()
                            + " &7is volledig uitgeschakeld!");
                    for (PlayerStats other : stats.values()) {
                        if (!other.isAlive()) continue;
                        var otherTeam = plugin.getKmcCore().getTeamManager()
                                .getTeamByPlayer(other.getUuid());
                        if (otherTeam != null && otherTeam.getId().equals(deadTeam.getId())) continue;
                        api.givePoints(other.getUuid(), teamBonus);
                        Player op = Bukkit.getPlayer(other.getUuid());
                        if (op != null) {
                            op.sendActionBar(net.kyori.adventure.text.Component.text(
                                    ChatColor.GOLD + "+" + teamBonus + " team-elim bonus"));
                            op.playSound(op.getLocation(),
                                    Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.8f);
                        }
                    }
                }
            }
        }

        long aliveCount = stats.values().stream().filter(PlayerStats::isAlive).count();
        if (aliveCount <= 1) endGame("last_alive");
        updateBossBar();
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;
        cancelTasks();
        plugin.getChestStocker().cancelTasks();

        // Restore world border
        var world = plugin.getArenaManager().getArena().getWorld();
        if (world != null && savedBorderSize > 0) {
            var border = world.getWorldBorder();
            border.setCenter(savedBorderCx, savedBorderCz);
            border.setSize(savedBorderSize);
        }
        if (world != null) world.setPVP(false);

        // Rank: alive first, then by elimination order desc, then by kills desc
        List<PlayerStats> ranked = new ArrayList<>(stats.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.getEliminationOrder() != b.getEliminationOrder())
                return Integer.compare(b.getEliminationOrder(), a.getEliminationOrder());
            return Integer.compare(b.getKills(), a.getKills());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lSurvival Games — Uitslag");
        broadcast("&7Reden: " + (reason.equals("last_alive") ? "&aLaatste speler over"
                : reason.equals("time_limit") ? "&eTijd op" : "&7Beëindigd"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String winnerName = "Niemand";

        // Win bonus to the last-alive player (rank 1, if alive)
        if (!ranked.isEmpty() && ranked.get(0).isAlive()) {
            int winBonus = plugin.getConfig().getInt("points.win", 200);
            if (winBonus > 0) {
                api.givePoints(ranked.get(0).getUuid(), winBonus);
            }
        }

        for (int i = 0; i < ranked.size(); i++) {
            PlayerStats ps = ranked.get(i);
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            String teamColor = team != null ? team.getColor().toString() : "";

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            String aliveStr = ps.isAlive() ? " &a✔ alive" : " &c✘";
            broadcast("  " + medal + " " + teamColor + ps.getName()
                    + aliveStr + " &8- &e" + ps.getKills() + " kills");

            // Tiered placement: 250 for 1st, -10 each, floor 0.
            int placement = i + 1;
            int placeBonus = readPlacement("points.placement", placement);
            if (placeBonus > 0) api.givePoints(ps.getUuid(), placeBonus);

            api.recordGameParticipation(ps.getUuid(), ps.getName(), GAME_ID, i == 0);

            if (i == 0) winnerName = teamColor + ps.getName();
        }

        // Team aggregate
        Map<String, Integer> teamKills = new HashMap<>();
        Map<String, KMCTeam> teamLookup = new HashMap<>();
        for (PlayerStats ps : ranked) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            if (team == null) continue;
            teamKills.merge(team.getId(), ps.getKills(), Integer::sum);
            teamLookup.put(team.getId(), team);
        }
        if (!teamKills.isEmpty()) {
            broadcast("&6═══ Team Totalen (kills) ═══");
            teamKills.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        KMCTeam t = teamLookup.get(e.getKey());
                        broadcast("  " + t.getColor() + t.getDisplayName()
                                + " &8- &e" + e.getValue() + " kills");
                    });
        }
        broadcast("&6═══════════════════════════════════");

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint Survival Games!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("sg");
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
        lastAttacker.clear();
        lastAttackerMs.clear();
        savedBorderSize = -1;
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
        if (graceTask     != null) { graceTask.cancel();     graceTask = null; }
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (voidCheckTask != null) { voidCheckTask.cancel(); voidCheckTask = null; }
    }

    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = stats.values().stream().filter(PlayerStats::isAlive).count();
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        String stateName = state == State.GRACE ? "&cBLOODBATH"
                : state == State.ACTIVE ? "&aPVP"
                : state == State.DEATHMATCH ? "&4DEATHMATCH" : state.name();
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                stateName + " &8| &e" + alive + "/" + stats.size() + " over"
                + " &8| &b" + String.format("%02d:%02d", min, sec)));
        if (state == State.ACTIVE || state == State.DEATHMATCH) {
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 600);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingSeconds / totalSec)));
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    /**
     * Reads a tiered placement value from config. Falls back to
     * "{section}.default" if the specific placement key is absent.
     * Returns 0 if neither exists.
     */
    private int readPlacement(String section, int placement) {
        int explicit = plugin.getConfig().getInt(section + "." + placement, -1);
        if (explicit >= 0) return explicit;
        return plugin.getConfig().getInt(section + ".default", 0);
    }
}
