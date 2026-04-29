package nl.kmc.elytra.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.elytra.ElytraEndriumPlugin;
import nl.kmc.elytra.models.BoostHoop;
import nl.kmc.elytra.models.Checkpoint;
import nl.kmc.elytra.models.RunnerState;
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
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Elytra Endrium race orchestrator.
 *
 * <p>States: IDLE → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * <p>Win conditions:
 * <ul>
 *   <li>First player reaches the FINISH checkpoint (highest-index)</li>
 *   <li>Timer expires — most checkpoints reached wins, then by progress</li>
 * </ul>
 *
 * <p>Crash detection: every 10 ticks (twice per second), check each
 * runner's elytra-glide state. If they've been on the ground or in
 * water for more than the configured grace period, treat as crash:
 * brief spectator → teleport to last checkpoint with elytra deployed +
 * a forward velocity push.
 */
public class GameManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "elytra_endrium";

    private final ElytraEndriumPlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, RunnerState> runners     = new LinkedHashMap<>();
    private final Map<UUID, Long>        groundedAt  = new HashMap<>();
    private final Map<UUID, Long>        boostCooldown = new HashMap<>();
    private final Map<UUID, Long>        cpCooldown    = new HashMap<>();
    private final List<UUID>             finishOrder   = new ArrayList<>();

    /** First team to fully finish — only awarded once per game. */
    private String firstTeamFinishedId = null;

    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private BukkitTask crashCheckTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    public GameManager(ElytraEndriumPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public State getState() { return state; }
    public boolean isActive() { return state == State.ACTIVE; }
    public RunnerState get(UUID uuid) { return runners.get(uuid); }
    public Map<UUID, RunnerState> getRunners() { return Collections.unmodifiableMap(runners); }

    private PotionEffectType slow() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("slowness")); }
        catch (Exception e) { return null; }
    }
    private PotionEffectType jump() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); }
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

    public String startCountdown() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getCourseManager().isReady())
            return "Course niet klaar:\n" + plugin.getCourseManager().getReadinessReport();

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        runners.clear();
        finishOrder.clear();
        firstTeamFinishedId = null;
        groundedAt.clear();
        boostCooldown.clear();
        cpCooldown.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            runners.put(p.getUniqueId(), new RunnerState(p.getUniqueId(), p.getName()));
        }
        if (runners.isEmpty()) { state = State.IDLE; return "Geen spelers online."; }

        plugin.getKmcCore().getApi().acquireScoreboard("elytra");

        // Teleport to launch + freeze + give elytra
        Location launch = plugin.getCourseManager().getLaunchSpawn();
        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jump();

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.teleport(launch);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.getInventory().clear();
            equipElytra(p);
            // Team-colored boots (chest is occupied by elytra, no helmet/legs by design)
            try { nl.kmc.kmccore.util.TeamArmor.applyBoots(p); } catch (Throwable ignored) {}
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
        }

        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Elytra start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Elytra Endrium] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "Elytra start over " + countdownSeconds + "s");

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                bossBar.setColor(BarColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "" + countdownSeconds,
                            ChatColor.YELLOW + "Maak je klaar om te vliegen!", 0, 25, 5);
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

    private void equipElytra(Player p) {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        var meta = elytra.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            elytra.setItemMeta(meta);
        }
        p.getInventory().setChestplate(elytra);
    }

    private void launch() {
        state = State.ACTIVE;
        gameStartMs = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 480);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jump();

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            if (jumpType != null) p.removePotionEffect(jumpType);

            // Initial forward + upward push to start the glide
            Vector facing = p.getLocation().getDirection();
            Vector launchVel = facing.normalize()
                    .multiply(plugin.getConfig().getDouble("game.launch-speed", 1.5));
            launchVel.setY(Math.max(launchVel.getY(), 0.5));
            p.setVelocity(launchVel);

            // Force glide on so they don't have to manually trigger it
            p.setGliding(true);

            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Vlieg door de hoops!", 0, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }

        broadcast("&a&l[Elytra Endrium] &eGO! &7Vlieg door alle hoops in volgorde!");

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) endGame("time_limit");
        }, 20L, 20L);

        // Crash check every 10 ticks (½ second)
        crashCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAllCrashes, 10L, 10L);
    }

    // ----------------------------------------------------------------
    // Crash detection
    // ----------------------------------------------------------------

    private void checkAllCrashes() {
        if (state != State.ACTIVE) return;
        long now = System.currentTimeMillis();
        long graceMs = plugin.getConfig().getLong("game.ground-grace-ms", 1500);

        for (UUID uuid : runners.keySet()) {
            RunnerState rs = runners.get(uuid);
            if (rs == null || rs.isFinished()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || p.getGameMode() == GameMode.SPECTATOR) continue;

            boolean grounded = p.isOnGround() || p.isInWater() || p.isInLava();
            if (grounded) {
                Long since = groundedAt.get(uuid);
                if (since == null) {
                    groundedAt.put(uuid, now);
                } else if (now - since >= graceMs) {
                    handleCrash(p, rs);
                    groundedAt.remove(uuid);
                }
            } else {
                groundedAt.remove(uuid);
                // Also keep gliding turned on (sometimes it disables)
                if (!p.isGliding() && p.getInventory().getChestplate() != null
                        && p.getInventory().getChestplate().getType() == Material.ELYTRA) {
                    p.setGliding(true);
                }
            }
        }
    }

    private void handleCrash(Player p, RunnerState rs) {
        rs.recordCrash();

        Location respawn = computeRespawnFor(rs);
        if (respawn == null) respawn = plugin.getCourseManager().getLaunchSpawn();

        // Phase 1 — instant spectator
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Crash!",
                ChatColor.GRAY + "Re-launch over 1s", 0, 25, 5);
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);

        // Phase 2 — 1s later: teleport, equip, push
        final Location finalRespawn = respawn;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (this.state != State.ACTIVE) return;
            if (rs.isFinished()) return;
            p.teleport(finalRespawn);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.setFireTicks(0);
            p.setFallDistance(0);
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            equipElytra(p);

            // Brief invuln so they don't crash again immediately if respawn
            // is mid-air and they need a frame to glide
            PotionEffectType regenType = regen();
            if (regenType != null) {
                p.addPotionEffect(new PotionEffect(regenType, 40, 4, true, false, false));
            }

            // Auto-launch: forward push in the direction the respawn is facing
            Vector facing = finalRespawn.getDirection().normalize();
            Vector relaunch = facing.multiply(
                    plugin.getConfig().getDouble("game.relaunch-speed", 1.2));
            relaunch.setY(Math.max(relaunch.getY(), 0.4));
            p.setVelocity(relaunch);
            p.setGliding(true);
        }, 20L);
    }

    private Location computeRespawnFor(RunnerState rs) {
        if (rs.getHighestCheckpoint() <= 0) return plugin.getCourseManager().getLaunchSpawn();
        Checkpoint cp = plugin.getCourseManager().getCheckpoint(rs.getHighestCheckpoint());
        return cp != null ? cp.getRespawn() : plugin.getCourseManager().getLaunchSpawn();
    }

    // ----------------------------------------------------------------
    // Checkpoint / boost hit handling
    // ----------------------------------------------------------------

    /** Called by listener when a player enters a checkpoint trigger box. */
    public void handleCheckpointEntry(Player p, Checkpoint cp) {
        if (state != State.ACTIVE) return;
        RunnerState rs = runners.get(p.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        // Race-mode strict ordering: must hit checkpoints in order
        int expectedNext = rs.getHighestCheckpoint() + 1;
        if (cp.getIndex() != expectedNext) {
            // Wrong checkpoint — silently ignore, player should hit them in order
            return;
        }

        // Cooldown to prevent retrigger when standing in the box
        long now = System.currentTimeMillis();
        Long expire = cpCooldown.get(p.getUniqueId());
        if (expire != null && now < expire) return;
        cpCooldown.put(p.getUniqueId(), now + 1500);

        boolean newProgress = rs.reach(cp.getIndex(), cp.getPoints());
        if (!newProgress) return;

        plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), cp.getPoints());

        Checkpoint finish = plugin.getCourseManager().getFinish();
        boolean isFinish = finish != null && cp.getIndex() == finish.getIndex();

        if (isFinish) {
            handleFinish(p, rs);
        } else {
            String msg = ChatColor.AQUA + "✔ " + cp.getDisplayName()
                    + ChatColor.GRAY + " (+" + cp.getPoints() + " pts)";
            p.sendActionBar(net.kyori.adventure.text.Component.text(msg));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation(), 20,
                    0.5, 0.5, 0.5, 0.05);
        }
        updateBossBar();
    }

    /** Called by listener when a player enters a boost hoop. */
    public void handleBoostEntry(Player p, BoostHoop hoop) {
        if (state != State.ACTIVE) return;
        if (runners.get(p.getUniqueId()) == null) return;

        // Cooldown — same hoop can't double-trigger
        long now = System.currentTimeMillis();
        Long expire = boostCooldown.get(p.getUniqueId());
        if (expire != null && now < expire) return;
        boostCooldown.put(p.getUniqueId(), now + 1000);

        Vector boost = hoop.computeBoostVelocity(p.getLocation().getDirection());
        // Add to existing velocity rather than replace — so boosts stack with momentum
        p.setVelocity(p.getVelocity().add(boost));
        p.setGliding(true);

        // Visual + sound
        p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation(), 30,
                0.5, 0.5, 0.5, 0.2);
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.5f);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.GOLD + "✦ " + (hoop.getType() == BoostHoop.Type.FORWARD
                        ? "Boost!" : "Lift!")));
    }

    private void handleFinish(Player p, RunnerState rs) {
        int placement = finishOrder.size() + 1;
        rs.markFinished(placement);
        finishOrder.add(p.getUniqueId());

        // Per-placement bonus — 1st = 320, -10 per place, floor 0.
        int finishBonus = readPlacement("points.placement", placement);
        if (finishBonus > 0) plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), finishBonus);

        long elapsedMs = System.currentTimeMillis() - gameStartMs;
        String time = formatMs(elapsedMs);

        broadcast("&6[Elytra Endrium] &e" + p.getName()
                + " &bfinisht als &6#" + placement + " &7(" + time + ", +" + finishBonus + ")");
        p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "#" + placement,
                ChatColor.YELLOW + "Tijd: " + time, 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.setGameMode(GameMode.SPECTATOR);

        // First-team-finish bonus: did this finish complete a team?
        if (firstTeamFinishedId == null) {
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (team != null) {
                boolean teamDone = true;
                for (UUID memberId : team.getMembers()) {
                    if (!runners.containsKey(memberId)) continue;  // not in game
                    if (!finishOrder.contains(memberId)) { teamDone = false; break; }
                }
                if (teamDone && !team.getMembers().isEmpty()) {
                    firstTeamFinishedId = team.getId();
                    int bonus = plugin.getConfig().getInt("points.first-team-finish-bonus", 10);
                    if (bonus > 0) {
                        for (UUID memberId : team.getMembers()) {
                            plugin.getKmcCore().getApi().givePoints(memberId, bonus);
                        }
                        broadcast("&6&l✦ Team " + team.getDisplayName()
                                + " &eis het eerste team dat finished! &7(+" + bonus + " elk)");
                    }
                }
            }
        }

        if (plugin.getConfig().getBoolean("game.end-on-first-finish", false)) {
            endGame("first_finish"); return;
        }
        if (finishOrder.size() >= runners.size()) endGame("all_finished");
    }

    // ----------------------------------------------------------------
    // End game
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();

        // Rank by points desc, finished first, then by finish time, then crashes asc
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> {
            if (a.getTotalPoints() != b.getTotalPoints())
                return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
            if (a.isFinished() && b.isFinished())
                return Long.compare(a.getFinishTimeMs(), b.getFinishTimeMs());
            if (a.isFinished()) return -1;
            if (b.isFinished()) return 1;
            return Integer.compare(a.getCrashes(), b.getCrashes());
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lElytra Endrium — Uitslag");
        broadcast("&7Reden: " + (reason.equals("time_limit") ? "&eTijd op"
                : reason.equals("first_finish") ? "&aEerste speler gefinisht"
                : reason.equals("all_finished") ? "&aIedereen gefinisht"
                : "&7Beëindigd"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String[] placeKeys = {"first-place", "second-place", "third-place"};
        String winnerName = "Niemand";

        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(rs.getUuid());
            String teamColor = team != null ? team.getColor().toString() : "";

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            String finishStr = rs.isFinished() ? " &a✔" : "";
            broadcast("  " + medal + " " + teamColor + rs.getName()
                    + " &8- &e" + rs.getTotalPoints() + " pts"
                    + finishStr + " &8(" + rs.getCrashes() + " crashes)");

            int bonus;
            if (i < placeKeys.length)
                bonus = plugin.getConfig().getInt("points." + placeKeys[i], 0);
            else
                bonus = plugin.getConfig().getInt("points.participation", 25);
            if (bonus > 0) api.givePoints(rs.getUuid(), bonus);

            api.recordGameParticipation(rs.getUuid(), rs.getName(), GAME_ID, i == 0);

            if (i == 0) winnerName = teamColor + rs.getName();
        }

        // Team aggregate footer
        Map<String, Integer> teamTotals = new HashMap<>();
        Map<String, KMCTeam> teamLookup = new HashMap<>();
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
                    ChatColor.translateAlternateColorCodes('&', "&7wint Elytra Endrium!"),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinner), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("elytra");
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
            p.setGliding(false);
            if (lobby != null) p.teleport(lobby);
        }

        runners.clear();
        finishOrder.clear();
        groundedAt.clear();
        boostCooldown.clear();
        cpCooldown.clear();
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame("force_stop");
    }

    private void cancelTasks() {
        if (countdownTask  != null) { countdownTask.cancel();  countdownTask = null; }
        if (gameTimerTask  != null) { gameTimerTask.cancel();  gameTimerTask = null; }
        if (crashCheckTask != null) { crashCheckTask.cancel(); crashCheckTask = null; }
    }

    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        int total = plugin.getCourseManager().getCheckpoints().size();
        int finishedCount = finishOrder.size();
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&aElytra Endrium &8| &e" + finishedCount + "/" + runners.size() + " finisht "
                + "&8| &b" + String.format("%02d:%02d", min, sec)));
        if (state == State.ACTIVE) {
            int totalSec = plugin.getConfig().getInt("game.max-duration-seconds", 480);
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

    /**
     * Reads a tiered placement value from config. Falls back to
     * "{section}.default" if the specific placement key is absent.
     * Special "dnf" key for didn't-finish, otherwise default = 0.
     */
    private int readPlacement(String section, int placement) {
        int explicit = plugin.getConfig().getInt(section + "." + placement, -1);
        if (explicit >= 0) return explicit;
        return plugin.getConfig().getInt(section + ".default", 0);
    }
}
