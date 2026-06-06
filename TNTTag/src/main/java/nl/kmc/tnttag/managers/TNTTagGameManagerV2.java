package nl.kmc.tnttag.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.PlayerState;
import nl.kmc.stats.service.StatisticsService;
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
 * V2 TNT Tag manager — tag mechanic, rounds, last alive wins.
 *
 * "It" players must pass the bomb by touching others. When round time
 * expires, all current "it" players are eliminated.
 */
public final class TNTTagGameManagerV2 extends BaseGameManager {

    private final TNTTagPlugin plugin;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private int eliminationCounter;
    private int currentRound;

    private BukkitTask roundTimerTask;
    private BukkitTask tagCheckTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;
    private int remainingRoundSeconds;

    private static final double TAG_RADIUS = 1.5;
    private static final long   TAG_COOLDOWN_MS = 2000;

    public TNTTagGameManagerV2(TNTTagPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        players.clear();
        eliminationCounter = 0;
        currentRound       = 0;

        Location spawn = plugin.getArenaManager().getArena().getSpawns().isEmpty()
                ? null : plugin.getArenaManager().getArena().getSpawns().get(0);
        if (spawn == null) { log.severe("[TNTTag] No spawn location set — cannot prepare."); end(); return; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.teleport(spawn);
            GamePlayerUtil.resetPlayer(p);
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.RED + "" + ChatColor.BOLD + "TNT Tag",
                BarColor.RED, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§c§l[TNT Tag] §eGet ready! The bomb will be assigned!");
    }

    @Override
    protected void onGameStart() {
        beginRound();
    }

    @Override
    protected void onGameEnd() {
        cancelTasks();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            return Integer.compare(b.getEliminatedAtRound(), a.getEliminatedAtRound());
        });

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null; int topSurvivals = 0;

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getRoundsSurvived() > topSurvivals) {
                topSurvivals = ps.getRoundsSurvived();
                mvpUuid = ps.getUuid(); mvpName = ps.getName();
            }
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
        if (ps != null) s.extra.put("isIt", ps.isIt());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§c[TNT Tag] State restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.tnttag.round", currentRound));
        l.add(api.tr(id, "sb.tnttag.time", Math.max(0, remainingRoundSeconds)));
        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        l.add(api.tr(id, "sb.common.players-left", alive));
        PlayerState me = players.get(id);
        if (me != null) {
            l.add("");
            if (!me.isAlive())      l.add(api.tr(id, "sb.tnttag.out"));
            else if (me.isIt())     l.add(api.tr(id, "sb.tnttag.it"));
            else                    l.add(api.tr(id, "sb.tnttag.safe"));
        }
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "TNT Tag"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().getArena().isReady())
                    r.addError("TNT Tag arena not ready: " + plugin.getArenaManager().getArena().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Map<UUID, PlayerState> getPlayersMap() { return Collections.unmodifiableMap(players); }
    public boolean isRoundActive() { return getState().isRunning() && roundTimerTask != null; }

    /** Called from listener when an "it" player touches another — attempt tag pass. */
    public void attemptTagPass(UUID itPlayer, UUID target) {
        if (!getState().isRunning()) return;
        PlayerState itPs  = players.get(itPlayer);
        PlayerState tgPs  = players.get(target);
        if (itPs == null || !itPs.isIt() || !itPs.isAlive()) return;
        if (tgPs == null || tgPs.isIt() || !tgPs.isAlive()) return;
        if (System.currentTimeMillis() - itPs.getLastTagMs() < TAG_COOLDOWN_MS) return;

        itPs.setIt(false);
        tgPs.setIt(true);
        tgPs.recordTagPass();
        updateVisuals();

        Player itP  = Bukkit.getPlayer(itPlayer);
        Player tgP  = Bukkit.getPlayer(target);
        if (itP != null) itP.sendTitle("§a§lSafe!", "§7Bomb passed on!", 5, 20, 5);
        if (tgP != null) tgP.sendTitle("§c§lYOU HAVE THE BOMB!", "§7Pass it on!", 5, 30, 5);
    }

    // ── Round management ──────────────────────────────────────────────────────

    private void beginRound() {
        currentRound++;
        long aliveCount = players.values().stream().filter(PlayerState::isAlive).count();
        if (aliveCount <= 1) { end(); return; }

        // Assign "it" players: 1 per 5 alive, min 1
        players.values().forEach(ps -> ps.setIt(false));
        int itCount = Math.max(1, (int)(aliveCount / 5));
        List<PlayerState> alive = players.values().stream().filter(PlayerState::isAlive).toList();
        Collections.shuffle(new ArrayList<>(alive));
        for (int i = 0; i < Math.min(itCount, alive.size()); i++) {
            PlayerState ps = alive.get(i);
            ps.setIt(true);
            ps.recordTagPass();
        }

        remainingRoundSeconds = plugin.getConfig().getInt("game.round-duration-seconds", 30);
        broadcast("§c§l[TNT Tag] §eRound " + currentRound + " — bomb assigned!");
        updateVisuals();

        roundTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingRoundSeconds--;
            updateBossBar();
            if (remainingRoundSeconds <= 0) endRound();
        }, 20L, 20L);

        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoid, 5L, 5L);
        tagCheckTask  = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTagProximity, 2L, 2L);
    }

    private void endRound() {
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (tagCheckTask   != null) { tagCheckTask.cancel();   tagCheckTask   = null; }
        if (voidCheckTask  != null) { voidCheckTask.cancel();  voidCheckTask  = null; }

        // Eliminate all "it" players
        List<PlayerState> itPlayers = players.values().stream()
                .filter(ps -> ps.isAlive() && ps.isIt()).toList();
        for (PlayerState ps : itPlayers) {
            ps.eliminate(eliminationCounter++);
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p != null) {
                p.setGameMode(GameMode.SPECTATOR);
                p.getInventory().clear();
                p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
                p.sendTitle("§c§lBOOM!", "§7You had the bomb!", 5, 40, 10);
            }
            broadcast("§c💣 §7" + ps.getName() + " §7exploded!");
        }

        // Award round survival points
        int survivalPts = plugin.getConfig().getInt("points.per-round-survived", 20);
        if (survivalPts > 0) {
            players.values().stream().filter(PlayerState::isAlive).forEach(ps -> {
                ps.incrementRoundsSurvived();
                api.points().givePoints(ps.getUuid(), survivalPts, PointAward.Reason.SURVIVAL_BONUS, registration.getId());
            });
        }

        long aliveCount = players.values().stream().filter(PlayerState::isAlive).count();
        if (aliveCount <= 1) {
            end();
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, this::beginRound, 60L);
        }
    }

    private void checkTagProximity() {
        if (!getState().isRunning()) return;
        List<PlayerState> itPlayers = players.values().stream()
                .filter(ps -> ps.isAlive() && ps.isIt()).toList();
        for (PlayerState itPs : itPlayers) {
            Player itP = Bukkit.getPlayer(itPs.getUuid());
            if (itP == null) continue;
            for (PlayerState other : players.values()) {
                if (!other.isAlive() || other.isIt()) continue;
                Player otherP = Bukkit.getPlayer(other.getUuid());
                if (otherP == null) continue;
                if (itP.getLocation().distance(otherP.getLocation()) <= TAG_RADIUS)
                    attemptTagPass(itPs.getUuid(), other.getUuid());
            }
        }
    }

    private void checkVoid() {
        if (!getState().isRunning()) return;
        int voidY = plugin.getArenaManager().getArena().getVoidYLevel();
        players.values().stream().filter(PlayerState::isAlive).forEach(ps -> {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null || p.getGameMode() == GameMode.SPECTATOR) return;
            if (p.getLocation().getY() < voidY) {
                ps.eliminate(eliminationCounter++);
                p.setGameMode(GameMode.SPECTATOR);
                broadcast("§c☠ §7" + ps.getName() + " §7fell into void.");
            }
        });
    }

    private void updateVisuals() {
        PotionEffectType glow = GamePlayerUtil.glowing();
        players.values().forEach(ps -> {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) return;
            if (glow != null) p.removePotionEffect(glow);
            if (ps.isIt() && glow != null)
                p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
        });
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        long it    = players.values().stream().filter(ps -> ps.isAlive() && ps.isIt()).count();
        bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "TNT Tag §8| §eRound " + currentRound
                + " §8| §c" + it + "§7 bombs §8| §e" + alive + "§7 alive §8| §b" + remainingRoundSeconds + "s");
    }

    private void cancelTasks() {
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (tagCheckTask   != null) { tagCheckTask.cancel();   tagCheckTask   = null; }
        if (voidCheckTask  != null) { voidCheckTask.cancel();  voidCheckTask  = null; }
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
