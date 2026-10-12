package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.TeamGameState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Eliminated Mob Mayhem players get a custom compass: right-click opens a menu with every player who is still
 * alive in ANY team's arena, and clicking one teleports the spectator there — so you can watch the whole game
 * instead of just your own pocket world.
 */
public final class SpectatorCompass implements Listener {

    private final MobMayhemPlugin plugin;
    private final NamespacedKey key;

    public SpectatorCompass(MobMayhemPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "spectator_compass");
    }

    /** Hands the compass to a (just) eliminated player; retried because they respawn a moment after dying. */
    public void give(Player p) {
        for (long delay : new long[]{5L, 30L, 80L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || has(p)) return;
                p.getInventory().setItem(4, compass(p));
            }, delay);
        }
    }

    private boolean has(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isCompass(it)) return true;
        return false;
    }

    private String tr(Player p, String key, Object... args) {
        return plugin.getKmcCore().getLanguageManager().tr(p, key, args);
    }

    private ItemStack compass(Player p) {
        ItemStack it = new ItemStack(Material.COMPASS);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(tr(p, "mm.compass.name"));
        meta.setLore(List.of(tr(p, "mm.compass.lore1"), tr(p, "mm.compass.lore2")));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isCompass(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK
                && e.getAction() != Action.LEFT_CLICK_AIR && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (!isCompass(e.getItem())) return;
        e.setCancelled(true);
        openMenu(e.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isCompass(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (isCompass(e.getCurrentItem()) && e.getView().getTopInventory().getHolder() == null) e.setCancelled(true);
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player spectator)) return;
        UUID target = menu.targets.get(e.getRawSlot());
        if (target == null) return;
        Player t = Bukkit.getPlayer(target);
        spectator.closeInventory();
        if (t == null || !t.isOnline() || t.isDead()) {
            spectator.sendMessage(tr(spectator, "mm.compass.gone"));
            return;
        }
        spectator.teleport(t.getLocation().add(0, 1, 0));
        spectator.sendMessage(tr(spectator, "mm.compass.watching", t.getName()));
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    private void openMenu(Player spectator) {
        var gm = plugin.getGameManagerV2();
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 54, tr(spectator, "mm.compass.menu"));
        menu.inv = inv;
        int slot = 0;
        if (gm != null) {
            for (TeamGameState ts : gm.getTeamStates().values()) {
                for (UUID id : ts.getAlivePlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline() || slot >= 54) continue;
                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    if (head.getItemMeta() instanceof SkullMeta sm) {
                        sm.setOwningPlayer(p);
                        sm.setDisplayName("§e§l" + p.getName());
                        sm.setLore(List.of(tr(spectator, "mm.compass.team", gm.teamLabelFor(ts.getTeamId())),
                                tr(spectator, "mm.compass.wave", ts.getCurrentWave()),
                                tr(spectator, "mm.compass.click")));
                        head.setItemMeta(sm);
                    }
                    inv.setItem(slot, head);
                    menu.targets.put(slot, id);
                    slot++;
                }
            }
        }
        if (slot == 0) spectator.sendMessage(tr(spectator, "mm.compass.nobody"));
        else spectator.openInventory(inv);
    }

    private static final class Menu implements InventoryHolder {
        final java.util.Map<Integer, UUID> targets = new java.util.HashMap<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
}
