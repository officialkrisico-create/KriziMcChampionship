package nl.kmc.luckyblock.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.luckyblock.LuckyBlockPlugin;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 Lucky Block manager — last player alive wins.
 *
 * <p>Players break lucky blocks to get random loot. Eliminated when killed.
 * No team mechanic — pure FFA. Points awarded for eliminations and survival.
 */
public final class LuckyBlockGameManagerV2 extends BaseGameManager {

    private final LuckyBlockPlugin plugin;

    private final Set<UUID>  alivePlayers     = new LinkedHashSet<>();
    private final Map<UUID, String> playerNames = new LinkedHashMap<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private int eliminationCounter;

    private BukkitTask timeLimitTask;
    private BossBar    bossBar;

    public LuckyBlockGameManagerV2(LuckyBlockPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        alivePlayers.clear();
        playerNames.clear();
        eliminationOrder.clear();
        eliminationCounter = 0;

        // Arena paste + player teleport delegated to KMCCore
        plugin.getKmcCore().getArenaManager().loadArenaForGame("lucky_block");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location origin = plugin.getKmcCore().getSchematicManager().getOriginForGame("lucky_block");
            plugin.getTracker().scanForLuckyBlocks(origin);
        }, 5L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            alivePlayers.add(p.getUniqueId());
            playerNames.put(p.getUniqueId(), p.getName());
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
        }

        bossBar = Bukkit.createBossBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Lucky Block",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§6§l[Lucky Block] §eBreak lucky blocks! Last player alive wins!");
    }

    @Override
    protected void onGameStart() {
        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        for (UUID uuid : alivePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "Lucky Block!",
                    ChatColor.YELLOW + "Last player alive wins!", 10, 60, 20);
        }

        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 300);
        if (maxDuration > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (getState().isRunning()) end();
            }, maxDuration * 20L);
        }
    }

    @Override
    protected void onGameEnd() {
        if (timeLimitTask != null) { timeLimitTask.cancel(); timeLimitTask = null; }
        if (bossBar       != null) { bossBar.removeAll();   bossBar       = null; }

        // Build finish order: survivors first (sorted by UUID insertion), then eliminated in reverse order
        List<UUID> finishOrder = new ArrayList<>();
        List<UUID> survivors   = new ArrayList<>(alivePlayers);
        List<UUID> eliminated  = new ArrayList<>(eliminationOrder);
        Collections.reverse(eliminated);
        finishOrder.addAll(survivors);
        finishOrder.addAll(eliminated);

        String winnerDesc = finishOrder.isEmpty() ? "No winner"
                : playerNames.getOrDefault(finishOrder.get(0), "Unknown");
        UUID mvpUuid = finishOrder.isEmpty() ? null : finishOrder.get(0);
        String mvpName = winnerDesc;

        for (int i = 0; i < finishOrder.size(); i++) {
            UUID uuid = finishOrder.get(i);
            String name = playerNames.getOrDefault(uuid, uuid.toString());
            api.points().awardPlayerPlacement(uuid, i + 1, finishOrder.size(), registration.getId());
            api.games().recordGameParticipation(uuid, name, registration.getId(), i == 0);
        }

        returnToLobby();
        alivePlayers.clear();
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
        s.extra.put("isAlive", alivePlayers.contains(player.getUniqueId()));
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§6[LuckyBlock] State restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(org.bukkit.entity.Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.common.players-left", alivePlayers.size()));
        l.add("");
        l.add(isAlive(id) ? api.tr(id, "sb.common.alive") : api.tr(id, "sb.common.eliminated"));
        l.add("");
        l.add(api.tr(id, "sb.luckyblock.tip1"));
        l.add(api.tr(id, "sb.luckyblock.tip2"));
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Lucky Block"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (plugin.getKmcCore().getSchematicManager().getSchematicForGame("lucky_block") == null)
                    r.addError("No schematic set for lucky_block. See KMCCore config.");
                if (plugin.getKmcCore().getSchematicManager().getOriginForGame("lucky_block") == null)
                    r.addError("Arena origin not set. Use: /kmcarena setorigin lucky_block");
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void eliminatePlayer(UUID uuid) {
        if (!getState().isRunning()) return;
        if (!alivePlayers.remove(uuid)) return;
        eliminationOrder.add(uuid);

        String name = playerNames.getOrDefault(uuid, "Player");
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.setGameMode(GameMode.SPECTATOR);
            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Eliminated!",
                    ChatColor.GRAY + "Wait for the game to end.", 10, 50, 10);
        }

        int killerPts = plugin.getConfig().getInt("points.per-elimination", 35);
        broadcast("§c☠ §7" + name + " §7was eliminated! §e" + alivePlayers.size() + " §7players left.");

        // Award survival bonus to remaining players
        int survivalBonus = plugin.getConfig().getInt("points.survival-bonus", 5);
        if (survivalBonus > 0) {
            alivePlayers.forEach(aliveUuid ->
                api.points().givePoints(aliveUuid, survivalBonus, PointAward.Reason.SURVIVAL_BONUS, registration.getId()));
        }

        updateBossBar();
        if (alivePlayers.size() <= 1) {
            Bukkit.getScheduler().runTaskLater(plugin, this::end, 40L);
        }
    }

    public void onLuckyBlockBreak(UUID breaker, int lootPoints) {
        if (!getState().isRunning()) return;
        if (lootPoints > 0)
            api.points().givePoints(breaker, lootPoints, PointAward.Reason.LUCKY_BLOCK, registration.getId());
    }

    public boolean isAlive(UUID uuid) { return alivePlayers.contains(uuid); }
    public Set<UUID> getAlivePlayers() { return Collections.unmodifiableSet(alivePlayers); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void updateBossBar() {
        if (bossBar == null) return;
        bossBar.setTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "Lucky Block §8| §e"
                + alivePlayers.size() + " §7players alive");
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        playerNames.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            if (lobby != null) p.teleport(lobby);
        });
        plugin.getTracker().clear();
        plugin.getKmcCore().getArenaManager().resetArenaForGame("lucky_block");
    }
}
