package nl.kmc.spleef.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.spleef.SpleefPlugin;
import nl.kmc.spleef.models.PlayerState;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Spleef game lifecycle.
 *
 * <p>States: IDLE → PREPARING_FLOOR → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * <p>Win conditions:
 * <ul>
 *   <li>Solo: last alive player wins</li>
 *   <li>Teams: last team with any alive member wins</li>
 *   <li>Timer expires: most-blocks-broken wins (tiebreak: most still alive)</li>
 * </ul>
 *
 * <p>Both individual ranking (elimination order, reverse) and team
 * aggregate are awarded — same pattern as Parkour Warrior.
 */
public class GameManager {

    public enum State { IDLE, PREPARING_FLOOR, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "spleef_teams";

    private final SpleefPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private int eliminationCounter;

    /** Tracks most recent block-breaker under each player's column for kill credit. */
    private final Map<UUID, UUID> lastBreakerNearVictim = new HashMap<>();
    private final Map<UUID, Long> lastBreakerTimeMs = new HashMap<>();

    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    public GameManager(SpleefPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State getState() { return state; }
    public boolean isActive() { return state == State.ACTIVE; }
    public PlayerState get(UUID uuid) { return players.get(uuid); }
    public Map<UUID, PlayerState> getPlayers() { return Collections.unmodifiableMap(players); }

    private PotionEffectType slow() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("slowness")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public String startGame() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getArenaManager().getArena().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getArena().getReadinessReport();

        // Build participant list
        players.clear();
        eliminationCounter = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
        }
        if (players.isEmpty()) return "Geen spelers online.";
        if (players.size() < 2) return "Minimaal 2 spelers nodig.";

        plugin.getKmcCore().getApi().acquireScoreboard("spleef");
        state = State.PREPARING_FLOOR;
        broadcast("&6[Spleef] &eDe vloer wordt gelegd...");

        // Place floor async, then start countdown
        plugin.getFloorManager().placeFloorAsync(
                plugin.getArenaManager().getArena(),
                this::beginCountdown);

        return null;
    }

    private void beginCountdown() {
        if (state != State.PREPARING_FLOOR) return;
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        // Teleport players to spawns + freeze
        var spawns = new ArrayList<>(plugin.getArenaManager().getArena().getPlayerSpawns());
        Collections.shuffle(spawns);
        int i = 0;
        PotionEffectType slowType = slow();
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            Location spawn = spawns.get(i % spawns.size());
            p.teleport(spawn);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            // Team-colored boots so teammates can identify each other
            try { nl.kmc.kmccore.util.TeamArmor.applyBoots(p); } catch (Throwable ignored) {}
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            i++;
        }

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Spleef start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Spleef] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "Spleef start over " + countdownSeconds + "s");

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
    }

    private void launch() {
        state = State.ACTIVE;
        gameStartMs = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 300);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            giveShovel(p);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Breek de vloer!", 0, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }

        broadcast("&a&l[Spleef] &eGO! &7Breek de vloer onder je tegenstanders!");

        // Start powerup spawner if enabled
        plugin.getPowerupSpawner().start(plugin.getArenaManager().getArena());

        // Game timer
        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) {
                endGame("time_limit");
            }
        }, 20L, 20L);

        // Void check — every 5 ticks (4× per second) to catch falls fast
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoidFalls, 5L, 5L);
    }

    private void giveShovel(Player p) {
        Material shovelMat = parseMaterial(
                plugin.getConfig().getString("game.shovel-material", "DIAMOND_SHOVEL"),
                Material.DIAMOND_SHOVEL);
        ItemStack shovel = new ItemStack(shovelMat);
        ItemMeta meta = shovel.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(net.kyori.adventure.text.Component.text(
                    ChatColor.AQUA + "" + ChatColor.BOLD + "Spleef Shovel"));
            shovel.setItemMeta(meta);
        }
        if (plugin.getConfig().getBoolean("game.shovel-efficiency", true)) {
            shovel.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY, 5);
        }
        p.getInventory().setItem(0, shovel);
    }

    // ----------------------------------------------------------------
    // Void detection — players who fall below voidY get eliminated
    // ----------------------------------------------------------------

    private void checkVoidFalls() {
        if (state != State.ACTIVE) return;
        int voidY = plugin.getArenaManager().getArena().getVoidYLevel();
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            PlayerState ps = players.get(uuid);
            if (ps == null || !ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (p.getLocation().getY() < voidY) {
                eliminate(p, "viel in de void");
            }
        }
    }

    /**
     * Eliminate a player from the round.
     */
    public void eliminate(Player p, String reason) {
        if (state != State.ACTIVE) return;
        PlayerState ps = players.get(p.getUniqueId());
        if (ps == null || !ps.isAlive()) return;

        ps.eliminate(eliminationCounter++);

        broadcast("&c☠ &7" + p.getName() + " &7" + reason
                + " &8(" + ps.getBlocksBroken() + " blokken kapot)");

        // Per-elimination kill credit: if a player broke a block under
        // this victim within the last 2 seconds, they get the kill.
        UUID killerUuid = lastBreakerNearVictim.get(p.getUniqueId());
        Long breakMs = lastBreakerTimeMs.get(p.getUniqueId());
        if (killerUuid != null && breakMs != null
                && (System.currentTimeMillis() - breakMs) < 2000
                && !killerUuid.equals(p.getUniqueId())) {
            int elimPts = plugin.getConfig().getInt("points.per-elimination", 35);
            if (elimPts > 0) {
                plugin.getKmcCore().getApi().givePoints(killerUuid, elimPts);
                Player killer = Bukkit.getPlayer(killerUuid);
                if (killer != null) {
                    killer.sendActionBar(net.kyori.adventure.text.Component.text(
                            ChatColor.GREEN + "+" + elimPts + " elimination!"));
                    killer.playSound(killer.getLocation(),
                            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                }
            }
        }
        // Clear the tracker for this victim
        lastBreakerNearVictim.remove(p.getUniqueId());
        lastBreakerTimeMs.remove(p.getUniqueId());

        // Living-while-someone-dies — every still-alive player gets a small bonus
        int livingBonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (livingBonus > 0) {
            KMCApi api = plugin.getKmcCore().getApi();
            for (PlayerState other : players.values()) {
                if (!other.isAlive()) continue;
                if (other.getUuid().equals(p.getUniqueId())) continue;
                api.givePoints(other.getUuid(), livingBonus);
                Player op = Bukkit.getPlayer(other.getUuid());
                if (op != null) {
                    op.sendActionBar(net.kyori.adventure.text.Component.text(
                            ChatColor.AQUA + "+" + livingBonus + " survivor bonus"));
                    op.playSound(op.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                }
            }
        }

        // Spectator
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
        p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Eliminated!",
                ChatColor.YELLOW + reason, 10, 50, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_DEATH, 1f, 0.7f);

        checkWinCondition();
        updateBossBar();
    }

    /**
     * Called by SpleefListener whenever a player breaks a floor block.
     * Records the breaker → so if any player falls into the void shortly
     * after, the breaker gets the elimination credit.
     */
    public void recordBlockBreakNearby(Player breaker, org.bukkit.block.Block block) {
        // For each currently-alive player, check if they're directly above
        // the broken block (within 4 blocks vertically).
        for (PlayerState ps : players.values()) {
            if (!ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            if (p.getUniqueId().equals(breaker.getUniqueId())) continue;
            org.bukkit.Location pl = p.getLocation();
            if (pl.getWorld() != block.getWorld()) continue;
            if (pl.getBlockX() == block.getX() && pl.getBlockZ() == block.getZ()
                    && pl.getY() > block.getY() && pl.getY() - block.getY() < 5) {
                lastBreakerNearVictim.put(p.getUniqueId(), breaker.getUniqueId());
                lastBreakerTimeMs.put(p.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    /** Called by listener when a player breaks a floor block. */
    public void onFloorBlockBroken(Player breaker) {
        PlayerState ps = players.get(breaker.getUniqueId());
        if (ps != null && ps.isAlive()) {
            ps.incrementBlocksBroken();
        }
        updateBossBar();
    }

    // ----------------------------------------------------------------
    // Win check
    // ----------------------------------------------------------------

    private void checkWinCondition() {
        // Count alive players
        long aliveCount = players.values().stream().filter(PlayerState::isAlive).count();
        if (aliveCount <= 1) {
            endGame(aliveCount == 1 ? "last_standing" : "no_survivors");
            return;
        }

        // For team mode: check if only one team has any alive members
        Set<String> aliveTeams = new HashSet<>();
        for (PlayerState ps : players.values()) {
            if (!ps.isAlive()) continue;
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            if (team != null) aliveTeams.add(team.getId());
            else aliveTeams.add("__solo_" + ps.getUuid());
        }
        if (aliveTeams.size() == 1) {
            endGame("last_team_standing");
        }
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();
        plugin.getPowerupSpawner().stop();

        // Rank: alive first (by blocks broken desc), then dead by reverse
        // elimination order (last to die ranks higher)
        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.isAlive() && b.isAlive())
                return Integer.compare(b.getBlocksBroken(), a.getBlocksBroken());
            // Both dead: higher elimination order = died later = ranks higher
            return Integer.compare(b.getEliminationOrder(), a.getEliminationOrder());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lSpleef — Uitslag");
        broadcast("&7Reden: " + (reason.equals("last_standing") ? "&aLaatste speler over"
                : reason.equals("last_team_standing") ? "&aLaatste team over"
                : reason.equals("time_limit") ? "&eTijd op"
                : "&7Geen overlevenden"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String winnerName = "Niemand";

        // Win bonus to the last alive player (rank 1, if alive)
        if (!ranked.isEmpty() && ranked.get(0).isAlive()) {
            int winBonus = plugin.getConfig().getInt("points.win", 200);
            if (winBonus > 0) {
                api.givePoints(ranked.get(0).getUuid(), winBonus);
            }
        }

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            String teamColor = team != null ? team.getColor().toString() : "";

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            String aliveStr = ps.isAlive() ? " &a✔" : " &c✘";
            broadcast("  " + medal + " " + teamColor + ps.getName()
                    + aliveStr + " &8- &e" + ps.getBlocksBroken() + " blokken");

            // Tiered personal placement: 250 for 1st, -10 each, floor 0.
            int placement = i + 1;
            int placeBonus = readPlacement("points.placement", placement);
            if (placeBonus > 0) api.givePoints(ps.getUuid(), placeBonus);

            // Standardized end-of-game stats
            api.recordGameParticipation(ps.getUuid(), ps.getName(), GAME_ID, i == 0);

            if (i == 0) winnerName = teamColor + ps.getName();
        }

        // Team aggregate footer
        Map<String, Integer> teamBlockTotals = new HashMap<>();
        Map<String, KMCTeam> teamLookup = new HashMap<>();
        for (PlayerState ps : ranked) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            if (team == null) continue;
            teamBlockTotals.merge(team.getId(), ps.getBlocksBroken(), Integer::sum);
            teamLookup.put(team.getId(), team);
        }
        if (!teamBlockTotals.isEmpty()) {
            broadcast("&6═══ Team Totalen ═══");
            teamBlockTotals.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        KMCTeam t = teamLookup.get(e.getKey());
                        broadcast("  " + t.getColor() + t.getDisplayName()
                                + " &8- &e" + e.getValue() + " blokken");
                    });
        }
        broadcast("&6═══════════════════════════════════");

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint Spleef!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("spleef");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            p.setHealth(20);
            p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        }

        // Clear the floor (any remaining blocks)
        plugin.getFloorManager().clearFloorAsync(
                plugin.getArenaManager().getArena(),
                () -> { /* done */ });

        players.clear();
        lastBreakerNearVictim.clear();
        lastBreakerTimeMs.clear();
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
        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&aSpleef &8| &e" + alive + "/" + players.size() + " over &8| &b"
                + String.format("%02d:%02d", min, sec)));
        if (state == State.ACTIVE) {
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 300);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingSeconds / totalSec)));
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    private static Material parseMaterial(String name, Material fallback) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (Exception e) { return fallback; }
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
