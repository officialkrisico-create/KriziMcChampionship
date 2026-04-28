package nl.kmc.kmccore.readyup;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Ready-up system used between games.
 *
 * <p>Between games, AutomationManager calls {@link #beginReadyCheck}.
 * Each player gets a green-concrete "Ready" item in slot 4 of their
 * hotbar. Right-clicking it marks them ready.
 *
 * <p>The check passes when:
 * <ul>
 *   <li>All online players have readied up, OR</li>
 *   <li>Timeout expires (default 60 seconds)</li>
 * </ul>
 *
 * <p>Players who don't ready up by timeout are logged as "AFK" but
 * the tournament continues — we don't want one AFK player to halt
 * the whole event.
 *
 * <p>Admin override: /kmcready force — skips the check immediately.
 */
public class ReadyUpManager implements Listener {

    public static final NamespacedKey READY_KEY = NamespacedKey.minecraft("kmc_ready_item");

    private final KMCCore plugin;
    private final Set<UUID> ready = new HashSet<>();
    private BukkitTask timeoutTask;
    private BukkitTask uiTask;
    private Runnable onComplete;
    private long startMs;
    private int timeoutSeconds;

    public ReadyUpManager(KMCCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Begins a ready check; runs onComplete when done (either all
     *  ready or timeout). */
    public void beginReadyCheck(int timeoutSeconds, Runnable onComplete) {
        this.ready.clear();
        this.startMs = System.currentTimeMillis();
        this.timeoutSeconds = timeoutSeconds;
        this.onComplete = onComplete;

        // Give all online players a Ready item
        for (Player p : Bukkit.getOnlinePlayers()) {
            giveReadyItem(p);
        }

        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                "&6&l⚡ READY CHECK ⚡ &eRight-click the &agreen item &eto ready up!"));
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                "&7Timeout: " + timeoutSeconds + "s. AFK players will be skipped."));

        // Action bar updates every 1s
        uiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickUI, 0L, 20L);

        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> finish(false), timeoutSeconds * 20L);
    }

    /** Skip the check immediately (admin override). */
    public void forceSkip() {
        finish(true);
    }

    public boolean isActive() {
        return onComplete != null;
    }

    public boolean isReady(UUID uuid) {
        return ready.contains(uuid);
    }

    // ----------------------------------------------------------------
    // Internal
    // ----------------------------------------------------------------

    private void giveReadyItem(Player p) {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(
                ChatColor.GREEN + "" + ChatColor.BOLD + "✔ Ready Up"));
        meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Right-click to confirm"),
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "you're ready for the next game")
        ));
        meta.getPersistentDataContainer().set(READY_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        p.getInventory().setItem(4, item);
    }

    private void tickUI() {
        if (onComplete == null) return;
        long elapsed = (System.currentTimeMillis() - startMs) / 1000L;
        long remaining = Math.max(0, timeoutSeconds - elapsed);
        int total = Bukkit.getOnlinePlayers().size();
        int readyCount = ready.size();

        for (Player p : Bukkit.getOnlinePlayers()) {
            String status;
            if (ready.contains(p.getUniqueId())) {
                status = ChatColor.GREEN + "✔ You are READY";
            } else {
                status = ChatColor.YELLOW + "Right-click the green item!";
            }
            String summary = ChatColor.GRAY + "[" + readyCount + "/" + total + "]  "
                    + ChatColor.AQUA + remaining + "s";
            p.sendActionBar(net.kyori.adventure.text.Component.text(status + "  " + summary));
        }

        if (readyCount >= total && total > 0) {
            // Everyone is ready — finish early
            finish(true);
        }
    }

    private void finish(boolean allReady) {
        if (onComplete == null) return;
        Runnable cb = onComplete;
        onComplete = null;
        if (timeoutTask != null) { timeoutTask.cancel(); timeoutTask = null; }
        if (uiTask != null) { uiTask.cancel(); uiTask = null; }

        // Remove all Ready items
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack slot = p.getInventory().getItem(4);
            if (slot != null && slot.hasItemMeta()
                    && slot.getItemMeta().getPersistentDataContainer()
                        .has(READY_KEY, PersistentDataType.BYTE)) {
                p.getInventory().setItem(4, null);
            }
        }

        // Announce
        if (allReady) {
            Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD
                    + "✔ All players ready! Continuing...");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.5f);
            }
        } else {
            int afk = Bukkit.getOnlinePlayers().size() - ready.size();
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Ready timeout. "
                    + afk + " player(s) skipped (AFK).");
        }

        try { cb.run(); } catch (Exception e) {
            plugin.getLogger().warning("ReadyCheck callback failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (onComplete == null) return;
        if (event.getItem() == null) return;
        ItemStack item = event.getItem();
        if (!item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer()
                .has(READY_KEY, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player p = event.getPlayer();
        if (ready.add(p.getUniqueId())) {
            // Convert to red glass for visual feedback
            ItemStack done = new ItemStack(Material.LIME_STAINED_GLASS);
            ItemMeta meta = done.getItemMeta();
            meta.displayName(net.kyori.adventure.text.Component.text(
                    ChatColor.GREEN + "" + ChatColor.BOLD + "✔ READY"));
            meta.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Waiting for others...")
            ));
            meta.getPersistentDataContainer().set(READY_KEY, PersistentDataType.BYTE, (byte) 1);
            done.setItemMeta(meta);
            p.getInventory().setItem(4, done);

            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.5f);
            Bukkit.broadcastMessage(ChatColor.GRAY + p.getName() + " is ready ("
                    + ready.size() + "/" + Bukkit.getOnlinePlayers().size() + ")");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (onComplete != null) {
            // Mid-check join — give them the item too
            giveReadyItem(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ready.remove(event.getPlayer().getUniqueId());
        // If their leave brings us to "all ready", finish
        if (onComplete != null && ready.size() >= Bukkit.getOnlinePlayers().size() - 1
                && Bukkit.getOnlinePlayers().size() > 1) {
            // re-check on next tick
            Bukkit.getScheduler().runTask(plugin, this::tickUI);
        }
    }

    public void shutdown() {
        if (timeoutTask != null) timeoutTask.cancel();
        if (uiTask != null) uiTask.cancel();
        ready.clear();
        onComplete = null;
    }
}
