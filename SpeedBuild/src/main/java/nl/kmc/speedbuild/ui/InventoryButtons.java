package nl.kmc.speedbuild.ui;

import nl.kmc.speedbuild.SpeedBuildPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The hotbar control items players use to drive progression. Each carries a
 * persistent-data tag so clicks are matched reliably (no name-string guessing).
 *
 * <ul>
 *   <li>Slot 0 — GREEN WOOL: complete &amp; score the current build</li>
 *   <li>Slot 7 — RED WOOL: re-show the blueprint</li>
 *   <li>Slot 8 — GOLD BLOCK: finish the challenge (final build only)</li>
 * </ul>
 */
public final class InventoryButtons {

    public enum Button { COMPLETE, BLUEPRINT, FINISH }

    private final NamespacedKey key;

    public InventoryButtons(SpeedBuildPlugin plugin) {
        this.key = new NamespacedKey(plugin, "sb_button");
    }

    public void give(Player p, boolean finalStage) {
        var lm = nl.kmc.kmccore.KMCCore.getInstance().getLanguageManager();
        p.getInventory().setItem(0, make(Material.GREEN_WOOL, lm.tr(p, "speedbuild.btn.complete"), Button.COMPLETE,
                lm.tr(p, "speedbuild.btn.complete-lore")));
        p.getInventory().setItem(7, make(Material.RED_WOOL, lm.tr(p, "speedbuild.btn.blueprint"), Button.BLUEPRINT,
                lm.tr(p, "speedbuild.btn.blueprint-lore")));
        if (finalStage)
            p.getInventory().setItem(8, make(Material.GOLD_BLOCK, lm.tr(p, "speedbuild.btn.finish"), Button.FINISH,
                    lm.tr(p, "speedbuild.btn.finish-lore")));
        else
            p.getInventory().setItem(8, null);
        p.getInventory().setHeldItemSlot(0);
    }

    /** Final-build state: only the blueprint + GOLD finish remain (no COMPLETE). */
    public void giveFinishOnly(Player p) {
        give(p, true);
        p.getInventory().setItem(0, null);
    }

    public void clear(Player p) {
        p.getInventory().setItem(0, null);
        p.getInventory().setItem(7, null);
        p.getInventory().setItem(8, null);
    }

    private ItemStack make(Material mat, String name, Button button, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(java.util.List.of(lore));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, button.name());
        item.setItemMeta(meta);
        return item;
    }

    /** Returns the button type of an item, or {@code null} if it isn't a control item. */
    public Button identify(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String v = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (v == null) return null;
        try { return Button.valueOf(v); } catch (IllegalArgumentException e) { return null; }
    }
}
