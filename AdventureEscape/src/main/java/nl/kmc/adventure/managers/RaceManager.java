package nl.kmc.adventure.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.RacerData;
import nl.kmc.kmccore.api.KMCApi;
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
 * Adventure Escape race lifecycle.
 *
 * <p>FIXES:
 * <ul>
 *   <li>Paper 1.21 PotionEffectType lookups via RegistryAccess
 *       (no more deprecated SLOW / JUMP fields).</li>
 *   <li>BossBar uses bigger / clearer titles for the lobby countdown.</li>
 *   <li>RaceScoreboard.start() called once at COUNTDOWN start so
 *       the lobby sidebar gets out of the way immediately.</li>
 * </ul>
 */
public class RaceManager {

    public enum State { IDLE, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "adventure_escape";

    private final AdventureEscapePlugin plugin;
    private State state = State.IDLE;

    private final Map<UUID, RacerData> racers = new LinkedHashMap<>();
    private final Set<UUID> allRacers = new LinkedHashSet<>();
    private final List<UUID> finishOrder = new ArrayList<>();

    private BukkitTask countdownTask;
    private BukkitTask tickTask;
    private BukkitTask timeLimitTask;
    private int countdownSeconds;
    private BossBar bossBar;

    public RaceManager(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Helpers — Paper 1.21 compatible
    // ----------------------------------------------------------------

    private PotionEffectType slow() {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft("slowness"));
        } catch (Exception e) { return null; }
    }

    private PotionEffectType jumpBoost() {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft("jump_boost"));
        } catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // API
    // ----------------------------------------------------------------

    public String startCountdown() {
        if (state != State.IDLE) return "Er is al een race bezig.";
        if (!plugin.getArenaManager().isReady())
            return "Arena niet klaar:\n" + plugin.getArenaManager().getReadinessReport();

        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 30);

        racers.clear();
        allRacers.clear();
        finishOrder.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            racers.put(p.getUniqueId(), new RacerData(p.getUniqueId(), p.getName()));
            allRacers.add(p.getUniqueId());
        }

        // Acquire scoreboard lock IMMEDIATELY so KMCCore's lobby sidebar
        // stops fighting during countdown.
        plugin.getRaceScoreboard().start();

        // Teleport everyone to the spawn grid + freeze them
        List<Location> grid = plugin.getArenaManager().getShuffledSpawns();
        List<UUID> order = new ArrayList<>(racers.keySet());
        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        for (int i = 0; i < order.size(); i++) {
            Player p = Bukkit.getPlayer(order.get(i));
            if (p == null) continue;
            Location spawn = grid.get(i % grid.size());
            p.teleport(spawn);
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);

            // Freeze with slowness + negative jump boost
            int ticks = countdownSeconds * 20;
            if (slowType != null) {
                p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            }
            if (jumpType != null) {
                p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
            }
        }

        // Lobby BossBar — big, blue, clear
        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Race start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Adventure Escape] &eRace start over &6" + countdownSeconds + " &eseconden!");

        // Per-second tick
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("game.countdown-seconds", 30);
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "Race start over " + countdownSeconds + "s");

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                bossBar.setColor(BarColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(
                            ChatColor.GOLD + "" + ChatColor.BOLD + "" + countdownSeconds,
                            ChatColor.YELLOW + "Maak je klaar!",
                            0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }

            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                launchRace();
            }
        }, 20L, 20L);

        return null;
    }

    private void launchRace() {
        state = State.ACTIVE;

        // Already acquired scoreboard during COUNTDOWN — defensive re-acquire ok
        plugin.getRaceScoreboard().start();

        bossBar.setColor(BarColor.GREEN);
        bossBar.setTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "🏁 RACE ACTIVE");

        long now = System.currentTimeMillis();
        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        for (RacerData rd : racers.values()) {
            rd.markRaceStart(now);
            rd.startFirstLap(now);

            Player p = Bukkit.getPlayer(rd.getUuid());
            if (p != null) {
                if (slowType != null) p.removePotionEffect(slowType);
                if (jumpType != null) p.removePotionEffect(jumpType);
                p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                        ChatColor.YELLOW + "Race is live!", 0, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
            }
        }

        broadcast("&a&l[Adventure Escape] &eGO! &7Wees de eerste over de finishlijn!");

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long t = System.currentTimeMillis();
            for (RacerData rd : racers.values()) rd.tickUpdate(t);
            plugin.getRaceScoreboard().refresh();
        }, 20L, 10L);

        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 600);
        if (maxDuration > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (state == State.ACTIVE) {
                    broadcast("&c[Adventure Escape] &eTijd is op!");
                    endRace();
                }
            }, maxDuration * 20L);
        }
    }

    public void onPlayerCrossFinish(Player player) {
        if (state != State.ACTIVE) return;
        RacerData rd = racers.get(player.getUniqueId());
        if (rd == null || rd.hasFinished()) return;

        long now = System.currentTimeMillis();
        long lapTime = rd.completeLap(now);

        int targetLaps = plugin.getArenaManager().getLaps();

        if (rd.getLapsCompleted() >= targetLaps) {
            int placement = finishOrder.size() + 1;
            rd.markFinished(now, placement);
            finishOrder.add(rd.getUuid());

            player.sendTitle(
                    ChatColor.GOLD + "#" + placement,
                    ChatColor.YELLOW + "Totaal: " + RacerData.formatMs(rd.getTotalTimeMs()),
                    10, 60, 20);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

            broadcast("&6[Adventure Escape] &e" + player.getName()
                    + " &bfinisht als &6#" + placement
                    + " &7(" + RacerData.formatMs(rd.getTotalTimeMs()) + ")");

            player.setGameMode(GameMode.SPECTATOR);
            if (finishOrder.size() >= racers.size()) endRace();
        } else {
            String lapStr = ChatColor.AQUA + "Lap " + rd.getLapsCompleted()
                    + "/" + targetLaps + "  "
                    + ChatColor.YELLOW + RacerData.formatMs(lapTime);
            player.sendTitle("", lapStr, 0, 30, 10);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }
    }

    public void onPlayerCrossStart(Player player) {
        if (state != State.ACTIVE) return;
        RacerData rd = racers.get(player.getUniqueId());
        if (rd == null || rd.hasStarted()) return;
        rd.startFirstLap(System.currentTimeMillis());
        player.sendTitle("", ChatColor.AQUA + "Lap 1 gestart!", 0, 30, 10);
    }

    public void endRace() {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        KMCApi api = plugin.getKmcCore().getApi();

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lAdventure Escape — Uitslag");
        broadcast("&6═══════════════════════════════════");

        for (int i = 0; i < finishOrder.size(); i++) {
            UUID uuid = finishOrder.get(i);
            int placement = i + 1;
            int base = plugin.getConfig().getInt("points." + placement, 0);
            if (base == 0) base = plugin.getConfig().getInt("points.last-place", 25);
            int awarded = api.givePoints(uuid, base);

            RacerData rd = racers.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : rd != null ? rd.getName() : "?";
            String medal = placement == 1 ? "&6🥇" : placement == 2 ? "&7🥈" : placement == 3 ? "&c🥉" : "&7#" + placement;
            broadcast("  " + medal + " &f" + name + " &8- &e" + awarded + " punten "
                    + "&8(" + RacerData.formatMs(rd != null ? rd.getTotalTimeMs() : 0) + ")");

            if (p != null) {
                var pd = plugin.getKmcCore().getPlayerDataManager().getOrCreate(uuid, name);
                pd.incrementGamesPlayed();
                if (placement == 1) pd.addWin(GAME_ID);
                else pd.resetStreak();
            }
        }

        int lastPlace = plugin.getConfig().getInt("points.last-place", 25);
        for (UUID uuid : allRacers) {
            if (finishOrder.contains(uuid)) continue;
            api.givePoints(uuid, lastPlace);
            RacerData rd = racers.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : rd != null ? rd.getName() : "?";
            broadcast("  &7DNF &f" + name + " &8- &e" + lastPlace + " punten");
        }

        broadcast("&6═══════════════════════════════════");

        String winnerName = !finishOrder.isEmpty()
                ? Optional.ofNullable(Bukkit.getPlayer(finishOrder.get(0)))
                    .map(Player::getName).orElse("?")
                : "Niemand";

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getRaceScoreboard().cleanup();
            for (UUID uuid : allRacers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.setGameMode(GameMode.ADVENTURE);
                    p.getInventory().clear();
                    for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
                    if (plugin.getKmcCore().getArenaManager().getLobby() != null) {
                        p.teleport(plugin.getKmcCore().getArenaManager().getLobby());
                    }
                }
            }
            racers.clear();
            allRacers.clear();
            finishOrder.clear();
            state = State.IDLE;

            if (plugin.getKmcCore().getAutomationManager().isRunning()) {
                plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
            }
        }, 100L);
    }

    public void forceStop() {
        if (state != State.IDLE) endRace();
        cancelTasks();
    }

    private void cancelTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (tickTask      != null) { tickTask.cancel();      tickTask = null; }
        if (timeLimitTask != null) { timeLimitTask.cancel(); timeLimitTask = null; }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    public State     getState()    { return state; }
    public boolean   isActive()    { return state == State.ACTIVE; }
    public Map<UUID, RacerData> getRacers() { return Collections.unmodifiableMap(racers); }
    public List<UUID> getFinishOrder()      { return Collections.unmodifiableList(finishOrder); }

    public List<RacerData> getRankedRacers() {
        List<RacerData> list = new ArrayList<>(racers.values());
        list.sort((a, b) -> {
            if (a.hasFinished() && b.hasFinished()) return Integer.compare(a.getPlacement(), b.getPlacement());
            if (a.hasFinished()) return -1;
            if (b.hasFinished()) return 1;
            int lapCmp = Integer.compare(b.getLapsCompleted(), a.getLapsCompleted());
            if (lapCmp != 0) return lapCmp;
            return Long.compare(a.getCurrentLapMs(), b.getCurrentLapMs());
        });
        return list;
    }
}
