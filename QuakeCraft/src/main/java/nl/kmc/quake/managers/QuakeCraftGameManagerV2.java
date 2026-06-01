package nl.kmc.quake.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.WeaponFactory;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import java.util.*;

/**
 * V2 QuakeCraft manager — railgun FPS, first to kill-target or highest kills wins.
 *
 * <p>No elimination: players respawn after death. Team-friendly-fire configurable.
 * Points per kill, killstreak bonuses, and revenge bonuses all apply.
 */
public final class QuakeCraftGameManagerV2 extends BaseGameManager {

    private final QuakeCraftPlugin plugin;

    private final Map<UUID, PlayerState> players    = new LinkedHashMap<>();
    private final Map<UUID, UUID>        lastKilledBy = new HashMap<>();
    private final Map<UUID, Long>        lastKilledAt = new HashMap<>();

    private BukkitTask gameTimerTask;
    private BossBar    bossBar;
    private int        remainingSeconds;

    private World   arenaWorld;
    private boolean arenaWorldPreviousPvp;

    public QuakeCraftGameManagerV2(QuakeCraftPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        players.clear();
        lastKilledBy.clear();
        lastKilledAt.clear();

        List<Location> spawns = new ArrayList<>(plugin.getArenaManager().getSpawns());
        Collections.shuffle(spawns);
        int i = 0;
        PotionEffectType jumpType;
        try { jumpType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); } catch (Exception e) { jumpType = null; }
        int countdownSec = plugin.getConfig().getInt("game.countdown-seconds", 15);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location spawn = spawns.isEmpty() ? p.getLocation() : spawns.get(i % spawns.size());
            p.teleport(spawn);
            GamePlayerUtil.resetPlayer(p);
            int ticks = countdownSec * 20;
            GamePlayerUtil.freezePlayer(p, ticks);
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));

            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
            i++;
        }

        bossBar = Bukkit.createBossBar(ChatColor.RED + "" + ChatColor.BOLD + "QuakeCraft",
                BarColor.RED, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§c§l[QuakeCraft] §eFirst to " + plugin.getConfig().getInt("game.kill-target", 25) + " kills wins!");
    }

    @Override
    protected void onGameStart() {
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 600);

        arenaWorld = plugin.getArenaManager().getArenaWorld();
        if (arenaWorld != null) {
            arenaWorldPreviousPvp = arenaWorld.getPVP();
            arenaWorld.setPVP(true);
        }

        PotionEffectType jumpType;
        try { jumpType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); } catch (Exception e) { jumpType = null; }
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            GamePlayerUtil.unfreezePlayer(p);
            if (jumpType != null) p.removePotionEffect(jumpType);
            giveBaseLoadout(p);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "First to " + plugin.getConfig().getInt("game.kill-target", 25) + " kills!", 0, 40, 10);
        }

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) { end(); return; }
            if (checkKillTargetReached()) end();
        }, 20L, 20L);

        if (plugin.getConfig().getBoolean("powerup-spawning.enabled", true)) {
            plugin.getPowerupSpawner().start();
        }
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (bossBar       != null) { bossBar.removeAll();   bossBar       = null; }

        plugin.getPowerupSpawner().stop();

        if (arenaWorld != null) {
            arenaWorld.setPVP(arenaWorldPreviousPvp);
            arenaWorld = null;
        }

        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.getKills() != b.getKills()) return Integer.compare(b.getKills(), a.getKills());
            return Integer.compare(a.getDeaths(), b.getDeaths());
        });

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null; int topKills = 0;

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getKills() > topKills) { topKills = ps.getKills(); mvpUuid = ps.getUuid(); mvpName = ps.getName(); }
        }

        returnToLobby();
        players.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, finishOrder);
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
        PlayerState ps = players.get(player.getUniqueId());
        if (ps != null) { s.extra.put("kills", ps.getKills()); s.extra.put("deaths", ps.getDeaths()); }
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§c[QuakeCraft] State restored!");
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "QuakeCraft"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("QuakeCraft arena not ready: " + plugin.getArenaManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void handleHit(Player shooter, Player target, String weapon) {
        if (!getState().isRunning()) return;
        if (shooter == null || target == null || shooter.equals(target)) return;
        if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR) return;

        PlayerState shooterState = players.get(shooter.getUniqueId());
        PlayerState targetState  = players.get(target.getUniqueId());
        if (shooterState == null || targetState == null) return;

        if (!plugin.getConfig().getBoolean("game.friendly-fire", false)) {
            var sTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(shooter.getUniqueId());
            var tTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(target.getUniqueId());
            if (sTeam != null && tTeam != null && sTeam.getId().equals(tTeam.getId())) return;
        }

        long revengeWindowMs = plugin.getConfig().getLong("points.revenge-window-ms", 10000);
        UUID prevKiller = lastKilledBy.get(shooter.getUniqueId());
        Long prevKilledAt = lastKilledAt.get(shooter.getUniqueId());
        boolean isRevenge = prevKiller != null && prevKiller.equals(target.getUniqueId())
                && prevKilledAt != null && (System.currentTimeMillis() - prevKilledAt) <= revengeWindowMs;
        if (isRevenge) {
            lastKilledBy.remove(shooter.getUniqueId());
            lastKilledAt.remove(shooter.getUniqueId());
        }

        shooterState.addKill();
        targetState.addDeath();
        lastKilledBy.put(target.getUniqueId(), shooter.getUniqueId());
        lastKilledAt.put(target.getUniqueId(), System.currentTimeMillis());

        int perKill = plugin.getConfig().getInt("points.per-kill", 10);
        api.points().givePoints(shooter.getUniqueId(), perKill, PointAward.Reason.KILL, registration.getId());

        if (isRevenge) {
            int revengeBonus = plugin.getConfig().getInt("points.revenge-kill-bonus", 25);
            if (revengeBonus > 0)
                api.points().givePoints(shooter.getUniqueId(), revengeBonus, PointAward.Reason.BONUS, registration.getId());
            broadcast("§d⚡ REVENGE! §7" + shooter.getName() + " §egot revenge on §7" + target.getName() + "§e!");
        }

        Location deathLoc = target.getLocation().add(0, 1, 0);
        target.getWorld().spawnParticle(Particle.EXPLOSION, deathLoc, 1);
        target.getWorld().playSound(deathLoc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f);
        broadcast("§c☠ §7" + target.getName() + " §8← §e" + shooter.getName() + " §8(" + shooterState.getKills() + ")");

        respawnAfterDeath(target);
        checkKillstreak(shooter, shooterState);
        updateBossBar();
    }

    public Map<UUID, PlayerState> getPlayersMap() { return Collections.unmodifiableMap(players); }
    public boolean isPvpAllowed() { return getState().isRunning(); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private boolean checkKillTargetReached() {
        int target = plugin.getConfig().getInt("game.kill-target", 25);
        return players.values().stream().anyMatch(ps -> ps.getKills() >= target);
    }

    private void respawnAfterDeath(Player target) {
        target.setGameMode(GameMode.SPECTATOR);
        target.getInventory().clear();
        target.setHealth(20); target.setFoodLevel(20);
        target.setFireTicks(0); target.setFallDistance(0);

        int delay = plugin.getConfig().getInt("game.respawn-delay-seconds", 1) * 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!getState().isRunning()) return;
            List<Location> avoid = new ArrayList<>();
            for (UUID id : players.keySet()) {
                Player other = Bukkit.getPlayer(id);
                if (other != null && !other.equals(target) && other.getGameMode() != GameMode.SPECTATOR)
                    avoid.add(other.getLocation());
            }
            Location spawn = plugin.getArenaManager().randomSpawnAwayFrom(avoid);
            if (spawn != null) target.teleport(spawn);
            target.setGameMode(GameMode.ADVENTURE);
            target.setHealth(20); target.setFoodLevel(20);
            target.setFireTicks(0); target.setFallDistance(0);
            target.getActivePotionEffects().forEach(e -> target.removePotionEffect(e.getType()));
            giveBaseLoadout(target);
        }, delay);
    }

    private void giveBaseLoadout(Player p) {
        if (plugin.getConfig().getBoolean("game.give-base-railgun", true))
            p.getInventory().setItem(0, WeaponFactory.buildRailgun(plugin));
        if (plugin.getConfig().getBoolean("game.base-speed-boost", true)) {
            PotionEffectType speedType;
            try { speedType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft("speed")); } catch (Exception e) { speedType = null; }
            if (speedType != null)
                p.addPotionEffect(new PotionEffect(speedType, Integer.MAX_VALUE, 0, true, false, false));
        }
    }

    private void checkKillstreak(Player shooter, PlayerState ps) {
        if (!plugin.getConfig().getBoolean("killstreaks.enabled", true)) return;
        var rewards = plugin.getConfig().getConfigurationSection("killstreaks.rewards");
        if (rewards == null) return;
        String key = String.valueOf(ps.getCurrentStreak());
        if (!rewards.contains(key)) return;
        var section = rewards.getConfigurationSection(key);
        if (section == null) return;
        int bonusPoints = section.getInt("bonus-points", 0);
        if (bonusPoints > 0)
            api.points().givePoints(shooter.getUniqueId(), bonusPoints, PointAward.Reason.BONUS, registration.getId());
        String msg = section.getString("message", "");
        if (!msg.isBlank()) broadcast(msg.replace("{player}", shooter.getName()));
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        PlayerState top = players.values().stream().max(Comparator.comparingInt(PlayerState::getKills)).orElse(null);
        int target = plugin.getConfig().getInt("game.kill-target", 25);
        int topKills = top != null ? top.getKills() : 0;
        String leadName = top != null ? top.getName() : "Nobody";
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "QuakeCraft §8| §e" + leadName
                + " §f" + topKills + "/" + target + " §8| §b" + String.format("%02d:%02d", min, sec));
        bossBar.setProgress(Math.max(0, Math.min(1.0, (double) topKills / target)));
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        players.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            GamePlayerUtil.resetPlayer(p);
            if (lobby != null) p.teleport(lobby);
        });
    }

}
