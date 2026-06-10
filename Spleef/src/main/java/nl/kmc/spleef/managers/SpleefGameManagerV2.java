package nl.kmc.spleef.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.spleef.SpleefPlugin;
import nl.kmc.spleef.models.PlayerState;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** V2 Spleef game manager — last alive wins via floor destruction. */
public final class SpleefGameManagerV2 extends BaseGameManager {

    private final SpleefPlugin plugin;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private int eliminationCounter;

    private BukkitTask gameTimerTask;
    private BukkitTask voidCheckTask;
    private BossBar    bossBar;
    private int remainingSeconds;

    private final Map<UUID, UUID> lastBreaker   = new HashMap<>();
    private final Map<UUID, Long> lastBreakerMs = new HashMap<>();

    public SpleefGameManagerV2(SpleefPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        players.clear();
        eliminationCounter = 0;
        lastBreaker.clear();
        lastBreakerMs.clear();

        plugin.getArenaManager().restoreFloor();

        List<Location> spleefSpawns = plugin.getArenaManager().getArena().getPlayerSpawns();
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < onlinePlayers.size(); i++) {
            Player p = onlinePlayers.get(i);
            Location spawnLoc = spleefSpawns.isEmpty() ? p.getLocation() : spleefSpawns.get(i % spleefSpawns.size());
            nl.kmc.game.api.GamePlayerUtil.safeTeleport(p, spawnLoc);
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
            giveShovel(p);
            // Hold players in place until "GO" so nobody digs during the countdown.
            nl.kmc.game.api.GamePlayerUtil.freezePlayer(p, 20 * 60);
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar(ChatColor.AQUA + "" + ChatColor.BOLD + "Spleef",
                BarColor.BLUE, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§b§l[Spleef] §eBreek de vloer! Laatste die overblijft wint.");
    }

    @Override
    protected void onGameStart() {
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 300);

        // Release players and signal GO.
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            nl.kmc.game.api.GamePlayerUtil.unfreezePlayer(p);
            p.sendTitle("§b§lSPLEEF", "§eBreek de vloer!", 0, 35, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        }

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);
        voidCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkVoid, 5L, 5L);
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (voidCheckTask != null) { voidCheckTask.cancel(); voidCheckTask = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.getEliminationOrder() != b.getEliminationOrder())
                return Integer.compare(b.getEliminationOrder(), a.getEliminationOrder());
            return Integer.compare(b.getBlocksBroken(), a.getBlocksBroken());
        });

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null; int topBlocks = 0;

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getBlocksBroken() > topBlocks) {
                topBlocks = ps.getBlocksBroken(); mvpUuid = ps.getUuid(); mvpName = ps.getName();
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
        if (ps != null) s.extra.put("blocksBroken", ps.getBlocksBroken());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§b[Spleef] Status hersteld!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(org.bukkit.entity.Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.common.time", String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)));
        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        l.add(api.tr(id, "sb.common.players-left", alive));
        PlayerState me = players.get(id);
        if (me != null) {
            l.add("");
            l.add(me.isAlive() ? api.tr(id, "sb.common.alive") : api.tr(id, "sb.common.eliminated"));
        }
        l.add("");
        l.add(api.tr(id, "sb.spleef.tip1"));
        l.add(api.tr(id, "sb.spleef.tip2"));
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Spleef"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Spleef arena not ready: " + plugin.getArenaManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void recordBreak(UUID victim, UUID breaker) {
        lastBreaker.put(victim, breaker);
        lastBreakerMs.put(victim, System.currentTimeMillis());
    }

    public void onBlockBroken(UUID breaker) {
        PlayerState ps = players.get(breaker);
        if (ps != null) ps.incrementBlocksBroken();
        int pts = plugin.getConfig().getInt("points.per-block", 2);
        if (pts > 0) api.points().givePoints(breaker, pts, PointAward.Reason.OBJECTIVE, registration.getId());
    }

    public void eliminate(Player p, String reason) {
        if (!getState().isRunning()) return;
        PlayerState ps = players.get(p.getUniqueId());
        if (ps == null || !ps.isAlive()) return;

        ps.eliminate(eliminationCounter++);
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        broadcast("§c☠ §7" + p.getName() + " §7" + reason);
        p.sendTitle("§c§lEliminated!", "§7" + reason, 10, 50, 10);

        // Kill credit
        UUID killerUuid = lastBreaker.get(p.getUniqueId());
        Long breakMs    = lastBreakerMs.get(p.getUniqueId());
        if (killerUuid != null && breakMs != null
                && System.currentTimeMillis() - breakMs < 2000
                && !killerUuid.equals(p.getUniqueId())) {
            PlayerState ks = players.get(killerUuid);
            if (ks != null) ks.incrementKills();
            api.points().givePoints(killerUuid,
                    plugin.getConfig().getInt("points.per-elimination", 35),
                    PointAward.Reason.KILL, registration.getId());
        }

        // Survival bonus
        int bonus = plugin.getConfig().getInt("points.living-while-someone-dies", 5);
        if (bonus > 0) {
            players.values().stream()
                .filter(s -> s.isAlive() && !s.getUuid().equals(p.getUniqueId()))
                .forEach(s -> api.points().givePoints(s.getUuid(), bonus, PointAward.Reason.SURVIVAL_BONUS, registration.getId()));
        }

        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        if (alive <= 1) end();
        updateBossBar();
    }

    public boolean isRunningGame() { return getState().isRunning(); }
    /** True only once the floor is live (ACTIVE/DEATHMATCH) — gates digging. */
    public boolean isActivePhase() { return getState().isPvPActive(); }
    public Map<UUID, PlayerState> getPlayersMap() { return Collections.unmodifiableMap(players); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void checkVoid() {
        if (!getState().isRunning()) return;
        int voidY = plugin.getArenaManager().getArena().getVoidYLevel();
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            PlayerState ps = players.get(uuid);
            if (ps == null || !ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getLocation().getY() < voidY) eliminate(p, "fell into the void");
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = players.values().stream().filter(PlayerState::isAlive).count();
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "Spleef §8| §e" + alive
                + " alive §8| §b" + String.format("%02d:%02d", min, sec));
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        players.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        });
    }

    private void giveShovel(Player p) {
        ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.displayName(net.kyori.adventure.text.Component.text(
                    ChatColor.AQUA + "" + ChatColor.BOLD + "Spleef Shovel"));
            shovel.setItemMeta(meta);
        }
        shovel.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY, 5);
        p.getInventory().setItem(0, shovel);
    }
}
