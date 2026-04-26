package nl.kmc.tnttag.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.PlayerState;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * TNT Tag round orchestrator.
 *
 * <p>States: IDLE → COUNTDOWN → ROUND_ACTIVE → ROUND_END → ENDED
 *
 * <p>Game flow:
 * <ol>
 *   <li>All players spawn in the arena.</li>
 *   <li>N random players become "it" (default 1 per 5 players, min 1).</li>
 *   <li>30s round timer counts down. "It" players try to pass the
 *       bomb by touching others.</li>
 *   <li>Timer expires → all current "it" players explode + are
 *       eliminated. Survivors get points.</li>
 *   <li>Brief intermission → next round with new random "it"s.</li>
 *   <li>When 1 (or 0) alive players remain, game ends.</li>
 * </ol>
 *
 * <p>Tag pass mechanic: when an "it" player gets within 1.5 blocks
 * of a non-"it" alive player AND has been "it" for more than the
 * tag-cooldown (so you can't pass back instantly), the bomb passes.
 */
public class GameManager {

    public enum State { IDLE, COUNTDOWN, ROUND_ACTIVE, ROUND_END, ENDED }

    public static final String GAME_ID = "tnt_tag";

    private final TNTTagPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();

    private BukkitTask countdownTask;
    private BukkitTask roundTimerTask;
    private BukkitTask tagCheckTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingRoundSeconds;
    private int  currentRound;
    private int  eliminationCounter;

    public GameManager(TNTTagPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State getState() { return state; }
    public boolean isActive() {
        return state == State.ROUND_ACTIVE || state == State.COUNTDOWN
            || state == State.ROUND_END;
    }
    public boolean isRoundActive() { return state == State.ROUND_ACTIVE; }
    public PlayerState get(UUID uuid) { return players.get(uuid); }
    public Map<UUID, PlayerState> getPlayers() { return Collections.unmodifiableMap(players); }
    public int getCurrentRound() { return currentRound; }

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
    private PotionEffectType speed() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("speed")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public String startGame() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getArenaManager().getArena().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getArena().getReadinessReport();

        players.clear();
        eliminationCounter = 0;
        currentRound = 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
        }
        if (players.size() < 2) return "Minimaal 2 spelers nodig.";

        plugin.getKmcCore().getApi().acquireScoreboard("tnttag");
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 10);

        // TP everyone to spawns + freeze
        var spawns = new ArrayList<>(plugin.getArenaManager().getArena().getSpawns());
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
            p.setFallDistance(0);
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            i++;
        }

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "TNT Tag start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[TNT Tag] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 10));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "TNT Tag start over " + countdownSeconds + "s");

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
                startRound();
            }
        }, 20L, 20L);
        return null;
    }

    // ----------------------------------------------------------------
    // Round flow
    // ----------------------------------------------------------------

    private void startRound() {
        currentRound++;
        state = State.ROUND_ACTIVE;
        remainingRoundSeconds = plugin.getConfig().getInt("game.round-seconds", 30);

        // Clear "it" status from everyone (defensive)
        for (PlayerState ps : players.values()) {
            ps.setIt(false);
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p != null) clearItVisuals(p);
        }

        // Pick "it" players
        List<PlayerState> alive = new ArrayList<>();
        for (PlayerState ps : players.values()) {
            if (ps.isAlive()) alive.add(ps);
        }
        if (alive.size() <= 1) {
            endGame();
            return;
        }

        int ratio = plugin.getConfig().getInt("game.it-ratio", 5);  // 1 it per N alive
        int itCount = Math.max(1, alive.size() / ratio);
        // Cap at half of alive minus 1 so survivors > "it"
        itCount = Math.min(itCount, Math.max(1, (alive.size() - 1) / 2));

        Collections.shuffle(alive);
        for (int i = 0; i < itCount && i < alive.size(); i++) {
            assignIt(alive.get(i));
        }

        // Lift slowness, give all alive players a speed boost
        PotionEffectType slowType = slow();
        PotionEffectType speedType = speed();
        for (PlayerState ps : players.values()) {
            if (!ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            if (speedType != null) p.addPotionEffect(new PotionEffect(speedType,
                    Integer.MAX_VALUE, 1, true, false, false));
        }

        bossBar.setColor(BarColor.GREEN);
        bossBar.setProgress(1.0);
        updateBossBar();

        broadcast("&c&l[Round " + currentRound + "] &eTNT! &7" + itCount + " spelers hebben de bom — "
                + remainingRoundSeconds + "s op de klok!");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Round " + currentRound,
                    ChatColor.YELLOW + "RUN!", 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
        }

        // Round timer
        roundTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingRoundSeconds--;
            updateBossBar();
            // Tick sound + visual on "it" players
            tickItPulse();
            if (remainingRoundSeconds <= 0) {
                detonateRound();
            }
        }, 20L, 20L);

        // Tag check — every 5 ticks (4× per second)
        tagCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTagPasses, 5L, 5L);

        // Void check — every 5 ticks
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoidFalls, 5L, 5L);
    }

    /** Mark a player as "it", apply visuals, store cooldown. */
    private void assignIt(PlayerState ps) {
        ps.setIt(true);
        ps.recordTagPass();   // start their cooldown so they can't immediately pass back
        Player p = Bukkit.getPlayer(ps.getUuid());
        if (p == null) return;
        applyItVisuals(p);
        p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "TNT!",
                ChatColor.YELLOW + "Pas hem door of ontplof!", 5, 30, 5);
        p.playSound(p.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1f, 1f);
    }

    /** Apply "it" indicators: TNT helmet + glowing + dramatic effects. */
    private void applyItVisuals(Player p) {
        if (plugin.getConfig().getBoolean("game.tnt-helmet", true)) {
            p.getInventory().setHelmet(new ItemStack(Material.TNT));
        }
        PotionEffectType glow = glowing();
        if (glow != null && plugin.getConfig().getBoolean("game.glowing", true)) {
            p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
        }
    }

    private void clearItVisuals(Player p) {
        if (p.getInventory().getHelmet() != null
                && p.getInventory().getHelmet().getType() == Material.TNT) {
            p.getInventory().setHelmet(null);
        }
        PotionEffectType glow = glowing();
        if (glow != null) p.removePotionEffect(glow);
    }

    /** Pulse particles + sound under "it" players each second. */
    private void tickItPulse() {
        for (PlayerState ps : players.values()) {
            if (!ps.isIt()) continue;
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            // Particle ring at feet
            p.getWorld().spawnParticle(Particle.SMOKE,
                    p.getLocation().add(0, 0.1, 0), 8, 0.4, 0.05, 0.4, 0.01);
            // Sound — pitch rises as time runs out (urgency)
            float pitch = 1.0f + (1.0f - (float) remainingRoundSeconds /
                    plugin.getConfig().getInt("game.round-seconds", 30));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, pitch);
        }
    }

    // ----------------------------------------------------------------
    // Tag passing — proximity check
    // ----------------------------------------------------------------

    private void checkTagPasses() {
        if (state != State.ROUND_ACTIVE) return;
        long now = System.currentTimeMillis();
        long cooldownMs = plugin.getConfig().getLong("game.tag-cooldown-ms", 1500);
        double range = plugin.getConfig().getDouble("game.tag-range", 1.5);
        double rangeSq = range * range;

        // Snapshot of "it" players to iterate over (so passing during loop is safe)
        List<PlayerState> itPlayers = new ArrayList<>();
        for (PlayerState ps : players.values()) {
            if (ps.isAlive() && ps.isIt()) itPlayers.add(ps);
        }

        for (PlayerState itPs : itPlayers) {
            // Cooldown check
            if (now - itPs.getLastTagMs() < cooldownMs) continue;

            Player itPlayer = Bukkit.getPlayer(itPs.getUuid());
            if (itPlayer == null) continue;

            // Look for closest non-"it" alive player within range
            PlayerState closest = null;
            double closestDistSq = rangeSq;
            for (PlayerState other : players.values()) {
                if (!other.isAlive() || other.isIt()) continue;
                if (other.getUuid().equals(itPs.getUuid())) continue;
                Player otherPlayer = Bukkit.getPlayer(other.getUuid());
                if (otherPlayer == null) continue;
                if (!otherPlayer.getWorld().equals(itPlayer.getWorld())) continue;
                // Don't tag spectators
                if (otherPlayer.getGameMode() == GameMode.SPECTATOR) continue;
                // Cooldown on the receiving player too — don't re-tag instantly
                if (now - other.getLastTagMs() < cooldownMs) continue;

                double distSq = otherPlayer.getLocation().distanceSquared(itPlayer.getLocation());
                if (distSq < closestDistSq) {
                    closest = other;
                    closestDistSq = distSq;
                }
            }
            if (closest != null) {
                passTag(itPs, closest);
            }
        }
    }

    private void passTag(PlayerState from, PlayerState to) {
        from.setIt(false);
        from.incrementTags();
        from.recordTagPass();

        to.setIt(true);
        to.recordTagPass();

        Player fromPlayer = Bukkit.getPlayer(from.getUuid());
        Player toPlayer   = Bukkit.getPlayer(to.getUuid());

        if (fromPlayer != null) {
            clearItVisuals(fromPlayer);
            fromPlayer.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.GREEN + "✓ Doorgegeven aan " + to.getName() + "!"));
            fromPlayer.playSound(fromPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        }
        if (toPlayer != null) {
            applyItVisuals(toPlayer);
            toPlayer.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Je hebt de TNT!",
                    ChatColor.YELLOW + "Pas hem door!", 0, 25, 5);
            toPlayer.playSound(toPlayer.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1f, 1f);
        }

        broadcast("&7• &e" + from.getName() + " &8→ &c" + to.getName());
    }

    // ----------------------------------------------------------------
    // Void / fall detection
    // ----------------------------------------------------------------

    private void checkVoidFalls() {
        if (state != State.ROUND_ACTIVE) return;
        int voidY = plugin.getArenaManager().getArena().getVoidYLevel();
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            PlayerState ps = players.get(uuid);
            if (ps == null || !ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (p.getLocation().getY() < voidY) {
                eliminate(p, ps, "viel in de void");
            }
        }
    }

    // ----------------------------------------------------------------
    // Detonation — round end
    // ----------------------------------------------------------------

    private void detonateRound() {
        if (state != State.ROUND_ACTIVE) return;
        state = State.ROUND_END;
        cancelRoundTasks();

        broadcast("&c&l[BOOM!] &eDe TNT ontploft!");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Eliminate all current "it" players
            int survivePoints = plugin.getConfig().getInt("points.round-survive", 25);
            List<PlayerState> toExplode = new ArrayList<>();
            for (PlayerState ps : players.values()) {
                if (ps.isAlive() && ps.isIt()) toExplode.add(ps);
            }

            // Visual: explosion at each "it" player
            for (PlayerState ps : toExplode) {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p == null) continue;
                p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,
                        p.getLocation().add(0, 1, 0), 1);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
            }

            // Award survival points to all alive non-"it" players
            for (PlayerState ps : players.values()) {
                if (!ps.isAlive()) continue;
                if (ps.isIt()) continue;
                ps.incrementRoundsSurvived();
                ps.addPoints(survivePoints);
                if (survivePoints > 0) {
                    plugin.getKmcCore().getApi().givePoints(ps.getUuid(), survivePoints);
                }
            }

            // Eliminate
            for (PlayerState ps : toExplode) {
                Player p = Bukkit.getPlayer(ps.getUuid());
                String reason = "tnt ging af";
                if (p != null) eliminate(p, ps, reason);
            }

            // Round summary
            long aliveCount = players.values().stream().filter(PlayerState::isAlive).count();
            broadcast("&6═══ Round " + currentRound + " einde &7— "
                    + aliveCount + " spelers nog over &6═══");

            if (aliveCount <= 1) {
                Bukkit.getScheduler().runTaskLater(plugin, this::endGame, 60L);
                return;
            }

            // Intermission then next round
            int intermissionSec = plugin.getConfig().getInt("game.intermission-seconds", 5);
            broadcast("&7Volgende round over &e" + intermissionSec + " &7seconden...");
            Bukkit.getScheduler().runTaskLater(plugin, this::startRound, intermissionSec * 20L);
        }, 30L);
    }

    /**
     * Eliminate a player. Spectator mode, "it" cleared, broadcast.
     */
    public void eliminate(Player p, PlayerState ps, String reason) {
        if (!ps.isAlive()) return;
        ps.eliminate(currentRound);
        eliminationCounter++;
        clearItVisuals(p);
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
        p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Eliminated!",
                ChatColor.YELLOW + reason, 10, 50, 10);

        broadcast("&c☠ &7" + p.getName() + " &7" + reason
                + " &8(round " + currentRound + ", " + ps.getRoundsSurvived() + " survived)");
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame() {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;
        cancelRoundTasks();

        // Rank: alive first (by points desc), then by elim round desc
        // (lasted longer = ranks higher), then by tags landed asc (fewer tags = better play)
        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.getRoundsSurvived() != b.getRoundsSurvived())
                return Integer.compare(b.getRoundsSurvived(), a.getRoundsSurvived());
            if (a.getEliminatedAtRound() != b.getEliminatedAtRound())
                return Integer.compare(b.getEliminatedAtRound(), a.getEliminatedAtRound());
            return Integer.compare(a.getTagsLanded(), b.getTagsLanded());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lTNT Tag — Uitslag");
        broadcast("&7Game eindigde na &6" + currentRound + " &7rounds");
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"first-place", "second-place", "third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            String teamColor = team != null ? team.getColor().toString() : "";

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            String aliveStr = ps.isAlive() ? " &a✔ alive" : " &c✘ R" + ps.getEliminatedAtRound();
            broadcast("  " + medal + " " + teamColor + ps.getName()
                    + aliveStr + " &8- &e" + ps.getRoundsSurvived() + " survived"
                    + " &7(" + ps.getTagsLanded() + " tags)");

            int placeBonus;
            if (i < placeKeys.length)
                placeBonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                placeBonus = plugin.getConfig().getInt("points.participation", 25);
            if (placeBonus > 0) api.givePoints(ps.getUuid(), placeBonus);

            api.recordGameParticipation(ps.getUuid(), ps.getName(), GAME_ID, i == 0);

            if (i == 0) winnerName = teamColor + ps.getName();
        }

        // Team aggregate footer
        Map<String, Integer> teamSurvivals = new HashMap<>();
        Map<String, KMCTeam> teamLookup = new HashMap<>();
        for (PlayerState ps : ranked) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(ps.getUuid());
            if (team == null) continue;
            teamSurvivals.merge(team.getId(), ps.getRoundsSurvived(), Integer::sum);
            teamLookup.put(team.getId(), team);
        }
        if (!teamSurvivals.isEmpty()) {
            broadcast("&6═══ Team Totalen ═══");
            teamSurvivals.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        KMCTeam t = teamLookup.get(e.getKey());
                        broadcast("  " + t.getColor() + t.getDisplayName()
                                + " &8- &e" + e.getValue() + " survived rounds");
                    });
        }
        broadcast("&6═══════════════════════════════════");

        final String finalWinner = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinner),
                    ChatColor.translateAlternateColorCodes('&', "&7wint TNT Tag!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("tnttag");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            clearItVisuals(p);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            p.setHealth(20);
            p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        }

        players.clear();
        state = State.IDLE;
        currentRound = 0;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame();
    }

    private void cancelRoundTasks() {
        if (countdownTask  != null) { countdownTask.cancel();  countdownTask = null; }
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (tagCheckTask   != null) { tagCheckTask.cancel();   tagCheckTask = null; }
        if (voidCheckTask  != null) { voidCheckTask.cancel();  voidCheckTask = null; }
    }

    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        long itCount = players.values().stream().filter(p -> p.isAlive() && p.isIt()).count();
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&cR" + currentRound + " &8| &e" + alive + "/" + players.size()
                + " over &8| &c" + itCount + " IT &8| &b" + remainingRoundSeconds + "s"));
        if (state == State.ROUND_ACTIVE) {
            int totalSec = plugin.getConfig().getInt("game.round-seconds", 30);
            bossBar.setProgress(Math.max(0, Math.min(1, (double) remainingRoundSeconds / totalSec)));
            // Color shifts to red as time runs out
            if (remainingRoundSeconds <= 5) bossBar.setColor(BarColor.RED);
            else if (remainingRoundSeconds <= 10) bossBar.setColor(BarColor.YELLOW);
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }
}
