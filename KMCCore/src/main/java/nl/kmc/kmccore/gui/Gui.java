package nl.kmc.kmccore.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minimal chest-GUI framework shared by all KMC menus.
 *
 * <p>Subclasses build their layout in the constructor with {@link #set} /
 * {@link #button}. Clicks are routed to per-slot actions by {@code GuiListener}
 * — no per-GUI listener boilerplate. A GUI is identified by being the
 * inventory's {@link InventoryHolder}.
 */
public abstract class Gui implements InventoryHolder {

    protected final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Gui(String title, int rows) {
        this.inventory = Bukkit.createInventory(this, rows * 9,
                ChatColor.translateAlternateColorCodes('&', title));
    }

    @Override public Inventory getInventory() { return inventory; }

    public void open(Player p) { p.openInventory(inventory); }

    /** Called by GuiListener when a player clicks a slot in this GUI. */
    public void handleClick(Player p, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) action.accept(p);
    }

    // ── Layout helpers ──────────────────────────────────────────────────────

    protected void set(int slot, ItemStack item) { inventory.setItem(slot, item); }

    protected void button(int slot, ItemStack item, Consumer<Player> onClick) {
        inventory.setItem(slot, item);
        if (onClick != null) actions.put(slot, onClick);
    }

    protected void clearActions() { actions.clear(); }

    /** Builds a display item with a coloured name + lore (each supports &-codes). */
    protected static ItemStack item(Material mat, String name, String... lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore.length > 0) {
                List<String> l = java.util.Arrays.stream(lore)
                        .map(s -> ChatColor.translateAlternateColorCodes('&', s)).toList();
                meta.setLore(l);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** A plain filler pane for empty slots. */
    protected static ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    /** A player-head item showing the given player's skin. */
    protected static ItemStack head(org.bukkit.OfflinePlayer owner, String name, String... lore) {
        ItemStack stack = item(Material.PLAYER_HEAD, name, lore);
        if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            sm.setOwningPlayer(owner);
            stack.setItemMeta(sm);
        }
        return stack;
    }

    protected void fillEmpty() {
        ItemStack f = filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, f);
        }
    }
}
