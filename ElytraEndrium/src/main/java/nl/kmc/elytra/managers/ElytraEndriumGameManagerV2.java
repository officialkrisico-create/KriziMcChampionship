package nl.kmc.elytra.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.elytra.ElytraEndriumPlugin;
import nl.kmc.elytra.models.BoostHoop;
import nl.kmc.elytra.models.Checkpoint;
import nl.kmc.elytra.models.RunnerState;
import nl.kmc.stats.service.StatisticsService;
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
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import java.util.*;

/**
 * V2 Elytra Endrium manager — players race through checkpoints while gliding.
 *
 * <p>Crash detection respawns players at their last checkpoint. Points awarded
 * per checkpoint hit and for finishing. First to complete all checkpoints wins,
 * or highest score when time runs out.
 */
public final class ElytraEndriumGameManagerV2 extends BaseGameManager {

    private final ElytraEndriumPlugin plugin;

    private final Map<UUID, RunnerState> runners      = new LinkedHashMap<>();
    private final Map<UUID, Long>        groundedAt   = new HashMap<>();
    private final Map<UUID, Long>        boostCooldown = new HashMap<>();
    private final Map<UUID, Long>        cpCooldown    = new HashMap<>();
    private final List<UUID>             finishOrder   = new ArrayList<>();

    private BukkitTask gameTimerTask;
    private BukkitTask crashCheckTask;
    private BossBar    bossBar;
    private int        remainingSeconds;
    private long       gameStartMs;

    public ElytraEndriumGameManagerV2(ElytraEndriumPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        runners.clear();
        finishOrder.clear();
        groundedAt.clear();
        boostCooldown.clear();
        cpCooldown.clear();

        Location launch = plugin.getCourseManager().getLaunchSpawn();
        PotionEffectType jumpType;
        try { jumpType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); } catch (Exception e) { jumpType = null; }
        int countdownSec = plugin.getConfig().getInt("game.countdown-seconds", 15);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.teleport(launch != null ? launch : p.getLocation());
            GamePlayerUtil.resetPlayer(p);
            equipElytra(p);
            int ticks = countdownSec * 20;
            GamePlayerUtil.freezePlayer(p, ticks);
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
            runners.put(p.getUniqueId(), new RunnerState(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.YELLOW + "" + ChatColor.BOLD + "Elytra Endrium",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§6§l[Elytra Endrium] §eFly through all checkpoints in order!");
    }

    @Override
    protected void onGameStart() {
        gameStartMs      = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 480);

        PotionEffectType jumpType;
        try { jumpType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); } catch (Exception e) { jumpType = null; }

        for (UUID uuid : runners.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            GamePlayerUtil.unfreezePlayer(p);
            if (jumpType != null) p.removePotionEffect(jumpType);

            Vector facing = p.getLocation().getDirection();
            Vector launchVel = facing.normalize()
                    .multiply(plugin.getConfig().getDouble("game.launch-speed", 1.5));
            launchVel.setY(Math.max(launchVel.getY(), 0.5));
            p.setVelocity(launchVel);
            p.setGliding(true);

            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Fly through the hoops!", 0, 40, 10);
        }

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);

        crashCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAllCrashes, 10L, 10L);
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask  != null) { gameTimerTask.cancel();  gameTimerTask  = null; }
        if (crashCheckTask != null) { crashCheckTask.cancel(); crashCheckTask = null; }
        if (bossBar        != null) { bossBar.removeAll();     bossBar        = null; }

        // Rank: finished players by time, then unfinished by checkpoint count then total points
        List<RunnerState> ranked = new ArrayList<>(runners.values());
        ranked.sort((a, b) -> {
            if (a.isFinished() != b.isFinished()) return a.isFinished() ? -1 : 1;
            if (a.isFinished()) return Long.compare(a.getFinishTimeMs(), b.getFinishTimeMs());
            if (a.getHighestCheckpoint() != b.getHighestCheckpoint())
                return Integer.compare(b.getHighestCheckpoint(), a.getHighestCheckpoint());
            return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
        });

        List<UUID> orderedUUIDs = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = ranked.isEmpty() ? null : ranked.get(0).getUuid();
        String mvpName = ranked.isEmpty() ? null : ranked.get(0).getName();

        for (int i = 0; i < ranked.size(); i++) {
            RunnerState rs = ranked.get(i);
            orderedUUIDs.add(rs.getUuid());
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
            s.extra.put("checkpoints", rs.getHighestCheckpoint());
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
        player.sendMessage("§6[Elytra] State restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(org.bukkit.entity.Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.common.time", String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)));
        int totalCp = plugin.getCourseManager().getCheckpoints().size();
        RunnerState me = runners.get(id);
        if (me != null) {
            l.add("");
            l.add(totalCp > 0 ? api.tr(id, "sb.elytra.checkpoints-of", me.getHighestCheckpoint(), totalCp)
                              : api.tr(id, "sb.elytra.checkpoints", me.getHighestCheckpoint()));
            l.add(me.isFinished() ? api.tr(id, "sb.elytra.finished") : api.tr(id, "sb.elytra.flying"));
            l.add(api.tr(id, "sb.common.points", me.getTotalPoints()));
        }
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Elytra Endrium"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getCourseManager().isReady())
                    r.addError("Elytra course not ready: " + plugin.getCourseManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void handleCheckpointEntry(Player p, Checkpoint cp) {
        if (!getState().isRunning()) return;
        RunnerState rs = runners.get(p.getUniqueId());
        if (rs == null || rs.isFinished()) return;

        int expectedNext = rs.getHighestCheckpoint() + 1;
        if (cp.getIndex() != expectedNext) return;

        long now = System.currentTimeMillis();
        Long expire = cpCooldown.get(p.getUniqueId());
        if (expire != null && now < expire) return;
        cpCooldown.put(p.getUniqueId(), now + 1500);

        boolean newProgress = rs.reach(cp.getIndex(), cp.getPoints());
        if (!newProgress) return;

        api.points().givePoints(p.getUniqueId(), cp.getPoints(), PointAward.Reason.OBJECTIVE, registration.getId());

        Checkpoint finish = plugin.getCourseManager().getFinish();
        if (finish != null && cp.getIndex() == finish.getIndex()) {
            handleFinish(p, rs);
        } else {
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.AQUA + "✔ " + cp.getDisplayName() + ChatColor.GRAY + " (+" + cp.getPoints() + " pts)"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }
        updateBossBar();
    }

    public void handleBoostEntry(Player p, BoostHoop hoop) {
        if (!getState().isRunning()) return;
        if (runners.get(p.getUniqueId()) == null) return;

        long now = System.currentTimeMillis();
        Long expire = boostCooldown.get(p.getUniqueId());
        if (expire != null && now < expire) return;
        boostCooldown.put(p.getUniqueId(), now + 1000);

        Vector boost = hoop.computeBoostVelocity(p.getLocation().getDirection());
        p.setVelocity(p.getVelocity().add(boost));
        p.setGliding(true);
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.5f);
    }

    public Map<UUID, RunnerState> getRunnersMap() { return Collections.unmodifiableMap(runners); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void handleFinish(Player p, RunnerState rs) {
        int placement = finishOrder.size() + 1;
        rs.markFinished(placement);
        finishOrder.add(p.getUniqueId());

        long elapsedMs = System.currentTimeMillis() - gameStartMs;
        int finishBonus = plugin.getConfig().getInt("points.finish-bonus", 200);
        api.points().givePoints(p.getUniqueId(), finishBonus, PointAward.Reason.OBJECTIVE, registration.getId());

        broadcast("§6[Elytra] §e" + p.getName() + " §bfinished #" + placement
                + " §7(" + formatMs(elapsedMs) + ", +" + finishBonus + " pts)");
        p.sendTitle("§6§l#" + placement, "§7Time: " + formatMs(elapsedMs), 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.setGameMode(GameMode.SPECTATOR);

        if (plugin.getConfig().getBoolean("game.end-on-first-finish", false)) { end(); return; }
        if (finishOrder.size() >= runners.size()) end();
    }

    private void checkAllCrashes() {
        if (!getState().isRunning()) return;
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
                if (!p.isGliding() && p.getInventory().getChestplate() != null
                        && p.getInventory().getChestplate().getType() == Material.ELYTRA) {
                    p.setGliding(true);
                }
            }
        }
    }

    private void handleCrash(Player p, RunnerState rs) {
        rs.recordCrash();
        Location respawn = rs.getHighestCheckpoint() <= 0 ? plugin.getCourseManager().getLaunchSpawn()
                : plugin.getCourseManager().getCheckpoint(rs.getHighestCheckpoint()) != null
                ? plugin.getCourseManager().getCheckpoint(rs.getHighestCheckpoint()).getRespawn()
                : plugin.getCourseManager().getLaunchSpawn();

        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        p.setHealth(20); p.setFoodLevel(20);
        p.setFireTicks(0); p.setFallDistance(0);
        p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Crash!", ChatColor.GRAY + "Respawning...", 0, 25, 5);

        final Location finalRespawn = respawn;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!getState().isRunning() || rs.isFinished()) return;
            p.teleport(finalRespawn != null ? finalRespawn : p.getLocation());
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20); p.setFoodLevel(20);
            p.setFireTicks(0); p.setFallDistance(0);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            equipElytra(p);

            Vector facing = finalRespawn != null ? finalRespawn.getDirection().normalize() : new Vector(0, 1, 0);
            Vector relaunch = facing.multiply(plugin.getConfig().getDouble("game.relaunch-speed", 1.2));
            relaunch.setY(Math.max(relaunch.getY(), 0.4));
            p.setVelocity(relaunch);
            p.setGliding(true);
        }, 20L);
    }

    private void equipElytra(Player p) {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        var meta = elytra.getItemMeta();
        if (meta != null) { meta.setUnbreakable(true); elytra.setItemMeta(meta); }
        p.getInventory().setChestplate(elytra);
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        int finished = finishOrder.size();
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        int total = plugin.getConfig().getInt("game.max-duration-seconds", 480);
        bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD + "Elytra Endrium §8| §e"
                + finished + "/" + runners.size() + " finished §8| §b" + String.format("%02d:%02d", min, sec));
        bossBar.setProgress(Math.max(0, Math.min(1.0, (double) remainingSeconds / total)));
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        runners.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            GamePlayerUtil.resetPlayer(p);
            p.setGliding(false);
            if (lobby != null) p.teleport(lobby);
        });
    }

    private static String formatMs(long ms) {
        return String.format("%02d:%02d", ms / 60000, (ms % 60000) / 1000);
    }

}
