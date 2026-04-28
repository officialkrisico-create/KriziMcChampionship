package nl.kmc.kmccore.spectator;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Spectator UX improvements.
 *
 * <p>When a player goes into spectator mode (during any game), this
 * manager gives them a special compass + tracking system:
 *
 * <ul>
 *   <li><b>Spectator Compass</b> — right-click to open a GUI of all
 *       alive players, click any to teleport to them</li>
 *   <li><b>Follow Mode</b> — sneak + right-click to enter "follow"
 *       mode, which auto-teleports you to follow a target. Sneak +
 *       right-click again to switch targets, drop the compass to exit.</li>
 *   <li><b>Leader Tracker</b> — a separate item that always points
 *       at the player currently leading the game (most kills /
 *       fastest progress, depending on game)</li>
 * </ul>
 *
 * <p>Game plugins call {@link #enableSpectatorMode(Player, SpectatorContext)}
 * on death and {@link #disableSpectatorMode(Player)} on cleanup. The
 * spectator manager handles all the UX from there.
 */
public class SpectatorManager implements Listener {

    public static final NamespacedKey COMPASS_KEY = NamespacedKey.minecraft("kmc_spec_compass");
    public static final NamespacedKey LEADER_KEY  = NamespacedKey.minecraft("kmc_spec_leader");

    /** Provides the spectator manager with game-specific info: who's alive, who's leading. */
    public interface SpectatorContext {
        /** Returns currently-alive players that this spectator can track. */
        List<Player> getAlivePlayers();
        /** Optional: returns the current "leader" (top player). Null if not applicable. */
        default Player getLeader() { return null; }
        /** Optional display name for this game (shown in GUI title). */
        default String getGameName() { return "Game"; }
    }

    private final KMCCore plugin;
    private final Map<UUID, SpectatorContext> active = new HashMap<>();
    private final Map<UUID, UUID> followTarget = new HashMap<>();
    private BukkitTask followTask;

    public SpectatorManager(KMCCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startFollowTask();
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Enables spectator UX for a player. Called by minigame plugins
     * when a player dies/spectates. Gives them the compass + leader tracker.
     */
    public void enableSpectatorMode(Player p, SpectatorContext ctx) {
        active.put(p.getUniqueId(), ctx);
        p.getInventory().clear();
        p.getInventory().setItem(0, buildSpectatorCompass());
        if (ctx.getLeader() != null) {
            p.getInventory().setItem(8, buildLeaderTracker());
        }
        p.sendMessage(ChatColor.GRAY + "Right-click compass to teleport. Sneak + right-click to follow.");
    }

    /** Disables spectator mode — clears items, drops follow target. */
    public void disableSpectatorMode(Player p) {
        active.remove(p.getUniqueId());
        followTarget.remove(p.getUniqueId());
        p.getInventory().clear();
    }

    public boolean isSpectating(Player p) {
        return active.containsKey(p.getUniqueId());
    }

    // ----------------------------------------------------------------
    // Item builders
    // ----------------------------------------------------------------

    private ItemStack buildSpectatorCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta m = compass.getItemMeta();
        m.displayName(net.kyori.adventure.text.Component.text(ChatColor.AQUA + "" + ChatColor.BOLD + "Spectator Compass"));
        m.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Right-click: teleport menu"),
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Sneak + right-click: follow mode"),
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Drop: exit follow mode")
        ));
        m.getPersistentDataContainer().set(COMPASS_KEY, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(m);
        return compass;
    }

    private ItemStack buildLeaderTracker() {
        ItemStack tracker = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta m = tracker.getItemMeta();
        m.displayName(net.kyori.adventure.text.Component.text(ChatColor.GOLD + "" + ChatColor.BOLD + "Leader Tracker"));
        m.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Always points at the current leader"),
                net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Right-click: teleport to leader")
        ));
        m.getPersistentDataContainer().set(LEADER_KEY, PersistentDataType.BYTE, (byte) 1);
        tracker.setItemMeta(m);
        return tracker;
    }

    private boolean isCompass(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(COMPASS_KEY, PersistentDataType.BYTE);
    }

    private boolean isLeaderTracker(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(LEADER_KEY, PersistentDataType.BYTE);
    }

    // ----------------------------------------------------------------
    // Event handlers
    // ----------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!isSpectating(p)) return;
        ItemStack item = event.getItem();
        if (item == null) return;

        if (isCompass(item)) {
            event.setCancelled(true);
            if (p.isSneaking()) {
                // Cycle to next alive player as follow target
                cycleFollowTarget(p);
            } else {
                openTeleportMenu(p);
            }
        } else if (isLeaderTracker(item)) {
            event.setCancelled(true);
            SpectatorContext ctx = active.get(p.getUniqueId());
            if (ctx == null) return;
            Player leader = ctx.getLeader();
            if (leader != null && leader.isOnline()) {
                p.teleport(leader.getLocation());
                p.sendMessage(ChatColor.GOLD + "Teleported to leader: " + leader.getName());
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1f);
            } else {
                p.sendMessage(ChatColor.GRAY + "No leader to follow yet.");
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        if (!isSpectating(p)) return;
        ItemStack stack = event.getItemDrop().getItemStack();
        if (isCompass(stack) || isLeaderTracker(stack)) {
            event.setCancelled(true);
            // Treat drop as "exit follow mode"
            if (followTarget.remove(p.getUniqueId()) != null) {
                p.sendMessage(ChatColor.GRAY + "Exited follow mode.");
            }
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getView().getTitle() == null) return;
        if (!event.getView().getTitle().contains("Teleport to")) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        // Use displayName to find player name
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(meta.displayName() != null
                        ? meta.displayName()
                        : net.kyori.adventure.text.Component.text(""));
        // Strip color codes
        name = ChatColor.stripColor(name);
        Player target = Bukkit.getPlayerExact(name);
        if (target != null && target.isOnline()) {
            p.closeInventory();
            p.teleport(target.getLocation());
            p.sendMessage(ChatColor.AQUA + "Teleported to " + target.getName());
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1f);
        }
    }

    // ----------------------------------------------------------------
    // Teleport menu
    // ----------------------------------------------------------------

    private void openTeleportMenu(Player viewer) {
        SpectatorContext ctx = active.get(viewer.getUniqueId());
        if (ctx == null) return;
        List<Player> alive = ctx.getAlivePlayers();
        int rows = Math.max(1, (alive.size() + 8) / 9);
        Inventory inv = Bukkit.createInventory(null, rows * 9,
                ChatColor.DARK_AQUA + "Teleport to — " + ctx.getGameName());

        for (int i = 0; i < alive.size() && i < rows * 9; i++) {
            Player target = alive.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta sm = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(target);
            sm.displayName(net.kyori.adventure.text.Component.text(ChatColor.YELLOW + target.getName()));
            sm.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text(ChatColor.GRAY + "HP: "
                            + ChatColor.RED + (int) target.getHealth() + "/20"),
                    net.kyori.adventure.text.Component.text(ChatColor.GRAY + "Distance: "
                            + ChatColor.AQUA
                            + (viewer.getWorld().equals(target.getWorld())
                                ? ((int) viewer.getLocation().distance(target.getLocation())) + " blocks"
                                : "different world"))
            ));
            head.setItemMeta(sm);
            inv.setItem(i, head);
        }
        viewer.openInventory(inv);
    }

    // ----------------------------------------------------------------
    // Follow mode
    // ----------------------------------------------------------------

    private void cycleFollowTarget(Player viewer) {
        SpectatorContext ctx = active.get(viewer.getUniqueId());
        if (ctx == null) return;
        List<Player> alive = ctx.getAlivePlayers();
        if (alive.isEmpty()) {
            viewer.sendMessage(ChatColor.GRAY + "No players to follow.");
            return;
        }

        UUID currentTarget = followTarget.get(viewer.getUniqueId());
        Player nextTarget = null;
        boolean takeNext = currentTarget == null;
        for (Player p : alive) {
            if (takeNext) { nextTarget = p; break; }
            if (p.getUniqueId().equals(currentTarget)) takeNext = true;
        }
        if (nextTarget == null) nextTarget = alive.get(0);  // wrap

        followTarget.put(viewer.getUniqueId(), nextTarget.getUniqueId());
        viewer.sendMessage(ChatColor.AQUA + "Now following: " + nextTarget.getName());
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
    }

    private void startFollowTask() {
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (var entry : new ArrayList<>(followTarget.entrySet())) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                Player target = Bukkit.getPlayer(entry.getValue());
                if (viewer == null || target == null || !target.isOnline()) {
                    followTarget.remove(entry.getKey());
                    continue;
                }
                if (!isSpectating(viewer)) {
                    followTarget.remove(entry.getKey());
                    continue;
                }
                // Spectate the target (vanilla spectator follow)
                if (viewer.getGameMode() == GameMode.SPECTATOR) {
                    viewer.setSpectatorTarget(target);
                }
            }
        }, 20L, 20L);
    }

    public void shutdown() {
        if (followTask != null) followTask.cancel();
        active.clear();
        followTarget.clear();
    }
}
