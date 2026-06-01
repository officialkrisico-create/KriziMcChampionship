package nl.kmc.tournament.reconnect;

import nl.kmc.game.api.PlayerGameState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stores a {@link PlayerGameState} when a player disconnects mid-game and
 * restores it when the player reconnects within the configured timeout window.
 *
 * The actual snapshot capture/restore calls are driven by the active
 * {@code BaseGameManager} — this class only manages the storage and timing.
 */
public final class ReconnectManager implements Listener {

    private static final Logger LOG = Logger.getLogger(ReconnectManager.class.getName());

    private final JavaPlugin plugin;
    private final long       timeoutMillis;

    /** snapshots keyed by player UUID */
    private final Map<UUID, PlayerGameState> snapshots = new ConcurrentHashMap<>();
    /** task IDs for expiry timers */
    private final Map<UUID, Integer>         expiryTasks = new ConcurrentHashMap<>();

    public ReconnectManager(JavaPlugin plugin, int timeoutSeconds) {
        this.plugin        = plugin;
        this.timeoutMillis = timeoutSeconds * 1000L;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Store a snapshot for the given player. Called by BaseGameManager on PlayerQuitEvent. */
    public void store(UUID uuid, PlayerGameState state) {
        snapshots.put(uuid, state);
        scheduleExpiry(uuid);
        LOG.fine("[KMC/Reconnect] Snapshot stored for " + uuid);
    }

    /** Retrieve and remove a valid snapshot. Returns empty if none or expired. */
    public Optional<PlayerGameState> consume(UUID uuid) {
        PlayerGameState state = snapshots.remove(uuid);
        cancelExpiry(uuid);
        if (state == null || state.isExpired(timeoutMillis)) return Optional.empty();
        return Optional.of(state);
    }

    /** Returns true if a non-expired snapshot exists for this player. */
    public boolean hasPendingReconnect(UUID uuid) {
        PlayerGameState state = snapshots.get(uuid);
        if (state == null) return false;
        if (state.isExpired(timeoutMillis)) {
            snapshots.remove(uuid);
            cancelExpiry(uuid);
            return false;
        }
        return true;
    }

    /** Discard all stored snapshots (called when the tournament ends). */
    public void clearAll() {
        expiryTasks.forEach((uuid, taskId) ->
                plugin.getServer().getScheduler().cancelTask(taskId));
        expiryTasks.clear();
        snapshots.clear();
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!snapshots.containsKey(uuid)) return;
        // snapshot was already stored by BaseGameManager; just ensure expiry timer is set
        if (!expiryTasks.containsKey(uuid)) scheduleExpiry(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();
        if (!hasPendingReconnect(uuid)) return;
        // Notify via title — the active GameManager will call consume() and restore
        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendTitle(
                        "§a§lWelcome back!",
                        "§7Restoring your game state…",
                        10, 60, 20);
                LOG.info("[KMC/Reconnect] Player " + player.getName() + " rejoined with a pending snapshot.");
            }
        }, 5L);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void scheduleExpiry(UUID uuid) {
        cancelExpiry(uuid);
        int taskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            snapshots.remove(uuid);
            expiryTasks.remove(uuid);
            LOG.fine("[KMC/Reconnect] Snapshot expired for " + uuid);
        }, (timeoutMillis / 50L));   // millis → ticks (20 ticks/s = 50 ms/tick)
        expiryTasks.put(uuid, taskId);
    }

    private void cancelExpiry(UUID uuid) {
        Integer taskId = expiryTasks.remove(uuid);
        if (taskId != null) plugin.getServer().getScheduler().cancelTask(taskId);
    }
}
