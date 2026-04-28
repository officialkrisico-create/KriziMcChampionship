package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Manages the Ominous Trial Key — the "back to last checkpoint" item.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Given to every racer when the race becomes ACTIVE</li>
 *   <li>Locked into a specific hotbar slot (default: slot 8 = far right)</li>
 *   <li>Cannot be dropped, swapped, or moved within the inventory</li>
 *   <li>If the player somehow loses it (e.g. via {@code /clear}), it's
 *       automatically replenished within 1 second</li>
 *   <li>Right-click teleports the player back to their last reached
 *       checkpoint (or the spawn grid if they haven't reached one yet)</li>
 *   <li>2-second cooldown between uses to prevent spam-rocketing</li>
 * </ul>
 */
public class TrialKeyManager implements Listener {

    /** Hotbar slot where the key lives. 8 = far-right. */
    private static final int KEY_SLOT = 8;

    /** Cooldown between right-click uses, in millis. */
    private static final long COOLDOWN_MS = 2000;

    private final AdventureEscapePlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private BukkitTask replenishTask;

    public TrialKeyManager(AdventureEscapePlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "ae_trial_key");
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void onRaceStart(Collection<? extends Player> players) {
        for (Player p : players) giveKey(p);

        // Tick every 20L = 1s to replenish missing keys
        if (replenishTask != null) replenishTask.cancel();
        replenishTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void onRaceEnd() {
        if (replenishTask != null) {
            replenishTask.cancel();
            replenishTask = null;
        }
        cooldowns.clear();
        // Strip keys from all players who may still have them
        for (Player p : Bukkit.getOnlinePlayers()) removeKeys(p);
    }

    private void tick() {
        if (plugin.getRaceManager() == null) return;
        if (plugin.getRaceManager().getState() != nl.kmc.adventure.managers.RaceManager.State.ACTIVE) return;

        for (UUID uuid : plugin.getRaceManager().getActiveRacerUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            if (plugin.getRaceManager().hasFinished(uuid)) continue;

            ItemStack slotItem = p.getInventory().getItem(KEY_SLOT);
            if (!isKey(slotItem)) {
                // Either missing or wrong item — restore
                giveKey(p);
            }
        }
    }

    // ----------------------------------------------------------------
    // Item construction
    // ----------------------------------------------------------------

    public ItemStack createKey() {
        ItemStack item = new ItemStack(Material.OMINOUS_TRIAL_KEY);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "↩ Terug naar laatste checkpoint");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Klik met rechtermuisknop om",
                ChatColor.GRAY + "terug te springen naar je",
                ChatColor.GRAY + "laatst bereikte checkpoint.",
                "",
                ChatColor.DARK_GRAY + "Cooldown: 2s"
        ));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isKey(ItemStack item) {
        if (item == null || item.getType() != Material.OMINOUS_TRIAL_KEY) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte b = meta.getPersistentDataContainer().get(marker, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private void giveKey(Player p) {
        ItemStack key = createKey();
        // Force into KEY_SLOT, displacing whatever's there into next free slot
        ItemStack existing = p.getInventory().getItem(KEY_SLOT);
        if (existing != null && existing.getType() != Material.AIR && !isKey(existing)) {
            p.getInventory().addItem(existing);  // best-effort relocate
        }
        p.getInventory().setItem(KEY_SLOT, key);
    }

    private void removeKeys(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isKey(it)) p.getInventory().setItem(i, null);
        }
    }

    // ----------------------------------------------------------------
    // Listeners — prevent drop/move, handle right-click
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isKey(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (isKey(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        // Block moving the trial key
        if (isKey(e.getCurrentItem()) || isKey(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        // Also protect team armor while race is ACTIVE
        if (plugin.getRaceManager() != null
                && plugin.getRaceManager().getState() == nl.kmc.adventure.managers.RaceManager.State.ACTIVE) {
            if (nl.kmc.kmccore.util.TeamArmor.isTeamArmor(plugin.getKmcCore(), e.getCurrentItem())
             || nl.kmc.kmccore.util.TeamArmor.isTeamArmor(plugin.getKmcCore(), e.getCursor())) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isKey(e.getMainHandItem()) || isKey(e.getOffHandItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR
                && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isKey(e.getItem())) return;

        e.setCancelled(true);
        Player p = e.getPlayer();

        // Must be racing
        if (plugin.getRaceManager() == null
                || plugin.getRaceManager().getState() != nl.kmc.adventure.managers.RaceManager.State.ACTIVE) {
            return;
        }
        if (!plugin.getRaceManager().getActiveRacerUuids().contains(p.getUniqueId())) {
            return;
        }
        if (plugin.getRaceManager().hasFinished(p.getUniqueId())) return;

        // Cooldown
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < COOLDOWN_MS) {
            long left = (COOLDOWN_MS - (now - last)) / 100;  // tenths
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.RED + "Cooldown: " + (left / 10.0) + "s"));
            return;
        }
        cooldowns.put(p.getUniqueId(), now);

        // Resolve target — last CP, or first spawn if none reached yet
        Location target = plugin.getCheckpointManager() != null
                ? plugin.getCheckpointManager().getRespawnFor(p.getUniqueId())
                : null;
        String label;
        if (target == null) {
            // Fallback: first spawn point
            List<Location> spawns = plugin.getArenaManager().getSpawns();
            if (spawns.isEmpty()) {
                p.sendMessage(ChatColor.RED + "Geen respawn locatie beschikbaar.");
                return;
            }
            target = spawns.get(0);
            label = "begin van de race";
        } else {
            String cpName = plugin.getCheckpointManager().getLastCheckpointName(p.getUniqueId());
            label = cpName != null ? "checkpoint " + cpName : "laatste checkpoint";
        }

        target = target.clone().add(0.5, 0.1, 0.5);
        p.teleport(target);
        p.setVelocity(p.getVelocity().setX(0).setY(0).setZ(0));
        p.setFallDistance(0f);
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.4f);
        p.sendMessage(ChatColor.GOLD + "↩ Teruggesprongen naar " + ChatColor.YELLOW + label);
    }
}
