package nl.kmc.parkour.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.parkour.ParkourWarriorPlugin;
import nl.kmc.parkour.models.Checkpoint;
import nl.kmc.parkour.models.RunnerState;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 Parkour Warrior manager.
 *
 * <p>Players race through checkpoints in a fixed-time window. Points are
 * awarded per checkpoint and for finishing. Winner = highest score / fastest.
 */
public final class ParkourGameManagerV2 extends BaseGameManager {

    private final ParkourWarriorPlugin plugin;

    private final Map<UUID, RunnerState> runners    = new LinkedHashMap<>();
    private final List<UUID>             finishOrder = new ArrayList<>();

    private BukkitTask gameTimerTask;
    private BukkitTask tickTask;
    private BossBar    bossBar;
    private int        remainingSeconds;
    private long       gameStartMs;

    public ParkourGameManagerV2(ParkourWarriorPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        runners.clear();
        finishOrder.clear();

        Location start = plugin.getCourseManager().getStartSpawn();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.teleport(start != null ? start : p.getLocation());
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
            runners.put(p.getUniqueId(), new RunnerState(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.GREEN + "" + ChatColor.BOLD + "Parkour Warrior",
                BarColor.GREEN, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§a§l[Parkour] §eRun! Reach as many checkpoints as possible!");
    }

    @Override
    protected void onGameStart() {
        gameStartMs      = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.duration-seconds", 180);

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);

        // Proximity tick: check if runners reached new checkpoints
        List<Checkpoint> checkpoints = plugin.getCourseManager().getCheckpoints();
        if (!checkpoints.isEmpty()) {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                    runners.forEach((uuid, rs) -> {
                        if (!rs.isFinished()) checkCheckpoints(uuid, rs, checkpoints);
                    }), 5L, 5L);
        }
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (tickTask      != null) { tickTask.cancel();      tickTask      = null; }
        if (bossBar       != null) { bossBar.removeAll();    bossBar       = null; }

        // Rank by: finished first (by time), then by checkpoints reached, then by score
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> {
            if (a.isFinished() != b.isFinished()) return a.isFinished() ? -1 : 1;
            if (a.isFinished())
                return Long.compare(a.getFinishTimeMs(), b.getFinishTimeMs());
            if (a.getStagesReached().size() != b.getStagesReached().size())
                return Integer.compare(b.getStagesReached().size(), a.getStagesReached().size());
            return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
        });

        // Build finishOrder from ranked (remaining players who never finished already in ranked)
        List<UUID> orderedUUIDs = new ArrayList<>();
        ranked.forEach(rs -> orderedUUIDs.add(rs.getUuid()));

        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null;
        if (!ranked.isEmpty()) {
            mvpUuid = ranked.get(0).getUuid();
            mvpName = ranked.get(0).getName();
        }

        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            api.points().awardPlayerPlacement(rs.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(rs.getUuid(), rs.getName(), registration.getId(), i == 0);
        }

        returnToLobby();
        runners.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, orderedUUIDs);
    }

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState s = new PlayerGameState();
        s.inventory = player.getInventory().getContents().clone();
        s.armor     = player.getInventory().getArmorContents().clone();
        s.health    = player.getHealth();
        s.maxHealth = 20;
        s.location  = player.getLocation();
        s.effects   = new ArrayList<>(player.getActivePotionEffects());
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs != null) {
            s.extra.put("checkpointsReached", rs.getStagesReached().size());
            s.extra.put("score", rs.getTotalPoints());
        }
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§a[Parkour] State restored!");
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Parkour Warrior"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getCourseManager().isReady())
                    r.addError("Parkour arena not ready: " + plugin.getCourseManager().getReadinessReport());
                if (plugin.getCourseManager().getCheckpoints().isEmpty())
                    r.addWarning("No checkpoints configured.");
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true when the game is in the ACTIVE running state. */
    public boolean isActive() { return getState().isRunning(); }

    /** Returns the RunnerState for a player, or null if not participating. */
    public RunnerState getRunner(java.util.UUID uuid) { return runners.get(uuid); }

    /**
     * Called by the movement listener when a player enters a checkpoint zone.
     * Awards points and checks for finish.
     */
    public void handleCheckpointEntry(Player player, Checkpoint checkpoint) {
        if (!isActive()) return;
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        // Award points for reaching a new checkpoint in sequence
        boolean newProgress = rs.reachCheckpoint(checkpoint.getIndex(), checkpoint.getStage(),
                checkpoint.getAwardedPoints());
        if (!newProgress) return;

        int pts = checkpoint.getAwardedPoints();
        api.points().givePoints(player.getUniqueId(), pts,
                nl.kmc.core.domain.PointAward.Reason.OBJECTIVE, registration.getId());
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.GREEN + "Checkpoint (stage " + checkpoint.getStage()
                + ") +" + pts + " pts"));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);

        // Check if this was the finish
        Checkpoint finish = plugin.getCourseManager().getFinish();
        if (finish != null && checkpoint.getStage() == finish.getStage()) onPlayerFinish(player);
    }

    /**
     * Applies a powerup effect to the player.
     */
    public void applyPowerup(Player player, nl.kmc.parkour.models.Powerup pu) {
        if (!isActive()) return;
        org.bukkit.potion.PotionEffectType type;
        try {
            io.papermc.paper.registry.RegistryAccess ra = io.papermc.paper.registry.RegistryAccess.registryAccess();
            String key = pu.getType() == nl.kmc.parkour.models.Powerup.Type.SPEED ? "speed" : "jump_boost";
            type = ra.getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                    .get(org.bukkit.NamespacedKey.minecraft(key));
        } catch (Exception e) { type = null; }
        if (type == null) return;
        int ticks = pu.getDurationSeconds() * 20;
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks, pu.getAmplifier(), true, false, true));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.5f);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.GREEN + "✦ "
                + (pu.getType() == nl.kmc.parkour.models.Powerup.Type.SPEED ? "Speed" : "Jump") + " Boost!"));
    }

    /**
     * Handles player death / fall — teleports to last checkpoint respawn.
     */
    public void handleDeath(Player player) {
        if (!isActive()) return;
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        rs.recordDeath();
        int cpIdx = rs.getLastCheckpointIndex();
        org.bukkit.Location respawn = null;
        if (cpIdx > 0) {
            Checkpoint cp = plugin.getCourseManager().getCheckpoint(cpIdx);
            if (cp != null) respawn = cp.getRespawn();
        }
        if (respawn == null) respawn = plugin.getCourseManager().getStartSpawn();
        if (respawn == null) respawn = player.getLocation();
        player.teleport(respawn);
        player.setHealth(20); player.setFoodLevel(20); player.setFallDistance(0);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.RED + "✘ Respawned!"));
    }

    /**
     * Attempts to skip the current checkpoint (if enough failures).
     * @return null on success, or error message
     */
    public String trySkip(Player player) {
        if (!isActive()) return "Geen game actief.";
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null) return "Je doet niet mee aan de race.";
        if (rs.isFinished()) return "Je bent al gefinisht!";

        int failsRequired = plugin.getConfig().getInt("skip.fails-required", 3);
        if (rs.getCurrentStageFailCount() < failsRequired) {
            int remaining = failsRequired - rs.getCurrentStageFailCount();
            return "Je hebt nog " + remaining + " meer fail(s) nodig om te skippen.";
        }

        int nextStage = rs.getHighestStage() + 1;
        List<Checkpoint> stageCps = plugin.getCourseManager().getCheckpointsByStage(nextStage);
        if (stageCps.isEmpty()) return "Geen volgend checkpoint om te skippen.";

        Checkpoint next = stageCps.get(0);
        rs.skipStage(nextStage);
        player.teleport(next.getRespawn());
        player.setFallDistance(0);
        broadcast("§7" + player.getName() + " §7sloeg stage §e" + nextStage + " §7over (geen punten)");

        // Check if they skipped to the finish stage
        Checkpoint finish = plugin.getCourseManager().getFinish();
        if (finish != null && nextStage == finish.getStage()) onPlayerFinish(player);
        return null;
    }

    public void onPlayerFinish(Player player) {
        if (!getState().isRunning()) return;
        RunnerState rs = runners.get(player.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        rs.markFinished(finishOrder.size() + 1);
        finishOrder.add(player.getUniqueId());

        int finishPts = plugin.getConfig().getInt("points.finish-bonus", 200);
        api.points().givePoints(player.getUniqueId(), finishPts, PointAward.Reason.OBJECTIVE, registration.getId());
        broadcast("§a§l[Parkour] §e" + player.getName() + " §afinished!");
        player.sendTitle("§a§lFINISHED!", "§7Great run!", 5, 40, 10);
        player.setGameMode(GameMode.SPECTATOR);

        if (plugin.getConfig().getBoolean("game.end-on-first-finish", false)) end();
    }

    public Map<UUID, RunnerState> getRunnersMap() { return Collections.unmodifiableMap(runners); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void checkCheckpoints(UUID uuid, RunnerState rs, List<Checkpoint> checkpoints) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        int next = rs.getStagesReached().size();
        if (next >= checkpoints.size()) return;
        Checkpoint cp = checkpoints.get(next);
        if (cp.contains(p.getLocation())) {
            int pts = plugin.getConfig().getInt("points.per-checkpoint", 25);
            rs.reachCheckpoint(cp.getIndex(), cp.getStage(), pts);
            api.points().givePoints(uuid, pts, PointAward.Reason.OBJECTIVE, registration.getId());
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.GREEN + "Checkpoint " + rs.getStagesReached().size() + " / " + checkpoints.size()));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long finished = runners.values().stream().filter(RunnerState::isFinished).count();
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "Parkour §8| §e" + finished
                + "/" + runners.size() + " finished §8| §b" + String.format("%02d:%02d", min, sec));
        int total = plugin.getConfig().getInt("game.duration-seconds", 180);
        bossBar.setProgress(Math.max(0, Math.min(1.0, (double) remainingSeconds / total)));
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        runners.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        });
    }
}
