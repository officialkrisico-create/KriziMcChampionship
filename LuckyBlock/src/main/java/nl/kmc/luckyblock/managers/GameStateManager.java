package nl.kmc.luckyblock.managers;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import nl.kmc.kmccore.api.KMCApi;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Lucky Block game lifecycle:
 * IDLE → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * Arena paste, reset, and player teleportation are all delegated to
 * KMCCore.ArenaManager. This manager owns the countdown, elimination
 * tracking, and loot distribution.
 */
public class GameStateManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    private final LuckyBlockPlugin plugin;
    private State                  state = State.IDLE;

    private final Set<UUID>  alivePlayers     = new LinkedHashSet<>();
    private final Set<UUID>  allPlayers       = new LinkedHashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();

    private BukkitTask countdownTask;
    private BukkitTask timeLimitTask;
    private int        countdownSeconds;

    private org.bukkit.boss.BossBar bossBar;

    // The game ID in KMCCore config — used for arena lookup
    public static final String GAME_ID = "lucky_block";

    public GameStateManager(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** @return null on success, error message on failure */
    public String startCountdown() {
        if (state != State.IDLE) return "Er is al een game bezig.";

        // Pre-flight check — KMCCore must have the arena configured
        if (plugin.getKmcCore().getSchematicManager().getSchematicForGame(GAME_ID) == null) {
            return "Geen schematic ingesteld. Zie KMCCore config.yml → games.list.lucky_block.schematic";
        }
        if (plugin.getKmcCore().getSchematicManager().getOriginForGame(GAME_ID) == null) {
            return "Arena origin niet ingesteld. Gebruik: /kmcarena setorigin lucky_block";
        }

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 10);

        alivePlayers.clear();
        allPlayers.clear();
        eliminationOrder.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            alivePlayers.add(p.getUniqueId());
            allPlayers.add(p.getUniqueId());
        }

        // TP everyone to KMCCore lobby first
        plugin.getKmcCore().getArenaManager().teleportAllToLobby();

        // BossBar
        bossBar = Bukkit.createBossBar("Lucky Block start over " + countdownSeconds + "s",
                org.bukkit.boss.BarColor.YELLOW, org.bukkit.boss.BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Lucky Block] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("game.countdown-seconds", 10);
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "Lucky Block start over " + countdownSeconds + "s");

            if (countdownSeconds <= 3 && countdownSeconds > 0) {
                for (Player p : getOnlineAlive()) {
                    p.sendTitle(ChatColor.GOLD + "" + countdownSeconds, "", 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }

            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                startGame();
            }
        }, 20L, 20L);

        return null;
    }

    private void startGame() {
        state = State.ACTIVE;
        bossBar.setColor(org.bukkit.boss.BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "Lucky Block — Last One Standing!");

        // Ask KMCCore to paste the arena and teleport players to spawns
        plugin.getKmcCore().getArenaManager().loadArenaForGame(GAME_ID);

        // After paste completes (1 tick later), scan for yellow concrete
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location origin = plugin.getKmcCore().getSchematicManager().getOriginForGame(GAME_ID);
            plugin.getTracker().scanForLuckyBlocks(origin);
        }, 5L);

        broadcast("&a&l[Lucky Block] &eGame gestart! Laatste speler wint!");

        for (Player p : getOnlineAlive()) {
            p.sendTitle(ChatColor.GOLD + "Lucky Block!",
                        ChatColor.YELLOW + "Laatste speler wint!", 10, 60, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);

            plugin.getKmcCore().getPlayerDataManager()
                    .getOrCreate(p.getUniqueId(), p.getName()).startGameSession();
        }

        // Time limit (optional)
        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 300);
        if (maxDuration > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (state == State.ACTIVE) endGame(null);
            }, maxDuration * 20L);
        }
    }

    public void eliminatePlayer(UUID uuid) {
        if (state != State.ACTIVE) return;
        if (!alivePlayers.remove(uuid)) return;
        eliminationOrder.add(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            var pd = plugin.getKmcCore().getPlayerDataManager().getOrCreate(uuid, p.getName());
            pd.endGameSession();
            pd.incrementGamesPlayed();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    p.teleport(plugin.getKmcCore().getArenaManager().getLobby());
                    p.sendMessage(ChatColor.RED + "Je bent geëlimineerd! Wacht op het einde...");
                    p.setGameMode(GameMode.SPECTATOR);
                }
            }, 60L);
        }

        broadcast("&c" + (p != null ? p.getName() : "Speler") + " &7is geëlimineerd! &e"
                + alivePlayers.size() + " &7spelers over.");

        if (alivePlayers.size() <= 1) {
            UUID winner = alivePlayers.isEmpty() ? null : alivePlayers.iterator().next();
            Bukkit.getScheduler().runTaskLater(plugin, () -> endGame(winner), 40L);
        }
    }

    public void endGame(UUID winnerUuid) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        KMCApi api = plugin.getKmcCore().getApi();
        String winnerName = "Niemand";

        if (winnerUuid != null) {
            Player winner = Bukkit.getPlayer(winnerUuid);
            winnerName = winner != null ? winner.getName() : winnerUuid.toString();

            // Award winner
            var winnerTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(winnerUuid);
            if (winnerTeam != null) api.awardTeamPlacement(winnerTeam.getId(), 1);
            api.givePoints(winnerUuid, plugin.getConfig().getInt("points.win", 200));

            var pd = plugin.getKmcCore().getPlayerDataManager()
                    .getOrCreate(winnerUuid, winnerName);
            pd.addWin(GAME_ID);
            pd.endGameSession();
            pd.incrementGamesPlayed();

            if (winner != null) {
                winner.sendTitle(ChatColor.GOLD + "🏆 Winnaar!",
                                 ChatColor.YELLOW + "Je hebt gewonnen!", 10, 80, 20);
                winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                winner.setGameMode(GameMode.SURVIVAL);
            }
        }

        // Award runners-up
        List<UUID> placements = new ArrayList<>(eliminationOrder);
        Collections.reverse(placements);
        for (int i = 0; i < placements.size(); i++) {
            int place = i + 2;
            int pts = plugin.getConfig().getInt("points." + place, 0);
            if (pts > 0) api.givePoints(placements.get(i), pts);
        }

        // Reset streaks for losers
        for (UUID uuid : allPlayers) {
            if (!uuid.equals(winnerUuid)) {
                plugin.getKmcCore().getPlayerDataManager().getOrCreate(uuid, "").resetStreak();
            }
        }

        final String wn = winnerName;
        broadcast("&6&l[Lucky Block] &eGame afgelopen! Winnaar: &6" + winnerName + " &e🏆");

        // Reset arena after 5s via KMCCore, TP to lobby, return to IDLE
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getTracker().clear();
            plugin.getKmcCore().getArenaManager().resetArenaForGame(GAME_ID);
            plugin.getKmcCore().getArenaManager().teleportAllToLobby();

            for (UUID uuid : allPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.setGameMode(GameMode.ADVENTURE);
            }

            state = State.IDLE;

            // Notify KMCCore automation
            if (plugin.getKmcCore().getAutomationManager().isRunning()) {
                plugin.getKmcCore().getAutomationManager().onGameEnd(wn);
            }
        }, 100L);
    }

    public void forceStop() {
        if (state != State.IDLE) endGame(null);
        cancelTasks();
    }

    // ----------------------------------------------------------------

    private void cancelTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (timeLimitTask != null) { timeLimitTask.cancel(); timeLimitTask = null; }
    }

    private List<Player> getOnlineAlive() {
        List<Player> list = new ArrayList<>();
        for (UUID uuid : alivePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) list.add(p);
        }
        return list;
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    public State     getState()        { return state; }
    public boolean   isActive()        { return state == State.ACTIVE; }
    public Set<UUID> getAlivePlayers() { return Collections.unmodifiableSet(alivePlayers); }
    public int       getAliveCount()   { return alivePlayers.size(); }
    public boolean   isAlive(UUID u)   { return alivePlayers.contains(u); }
}
