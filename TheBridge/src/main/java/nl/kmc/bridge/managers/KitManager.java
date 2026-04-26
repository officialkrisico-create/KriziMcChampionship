package nl.kmc.bridge.managers;

import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.models.BridgeTeam;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Hypixel-style Bridge kit:
 * - Iron sword (slot 0)
 * - Bow + arrows (slot 1, slot 9 arrows)
 * - 64 wool of team color (slot 2)
 * - Diamond pickaxe (slot 3) for breaking your own placed blocks
 * - Golden apple (slot 4)
 * - Gold/leather armor in team color
 *
 * <p>All tools unbreakable. Wool refills automatically every few seconds
 * (see GameManager.respawn flow + tick task).
 */
public class KitManager {

    private final TheBridgePlugin plugin;

    public KitManager(TheBridgePlugin plugin) { this.plugin = plugin; }

    public void giveKit(Player p, BridgeTeam team) {
        var inv = p.getInventory();
        inv.clear();

        ItemStack sword = makeUnbreakable(new ItemStack(Material.IRON_SWORD));
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 1);
        inv.setItem(0, sword);

        ItemStack bow = makeUnbreakable(new ItemStack(Material.BOW));
        bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, 1);
        inv.setItem(1, bow);

        int woolCount = plugin.getConfig().getInt("kit.wool-count", 64);
        inv.setItem(2, new ItemStack(team.getWoolMaterial(), woolCount));

        ItemStack pick = makeUnbreakable(new ItemStack(Material.DIAMOND_PICKAXE));
        pick.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY, 2);
        inv.setItem(3, pick);

        inv.setItem(4, new ItemStack(Material.GOLDEN_APPLE, 1));

        // Team-tinted leather armor (color matches team chat color reasonably)
        inv.setHelmet(makeUnbreakable(new ItemStack(Material.LEATHER_HELMET)));
        inv.setChestplate(makeUnbreakable(new ItemStack(Material.IRON_CHESTPLATE)));
        inv.setLeggings(makeUnbreakable(new ItemStack(Material.IRON_LEGGINGS)));
        inv.setBoots(makeUnbreakable(new ItemStack(Material.IRON_BOOTS)));

        inv.setItem(8, new ItemStack(Material.ARROW, 16));
    }

    /** Top up the player's wool stack to the configured count. */
    public void refillWool(Player p, BridgeTeam team) {
        var inv = p.getInventory();
        int desired = plugin.getConfig().getInt("kit.wool-count", 64);

        // Find existing stack of THIS team's wool
        int totalCurrent = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == team.getWoolMaterial()) totalCurrent += s.getAmount();
        }
        int missing = desired - totalCurrent;
        if (missing <= 0) return;

        // Add the missing wool — try to merge with existing stack first
        boolean placed = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == team.getWoolMaterial() && s.getAmount() < 64) {
                int can = Math.min(64 - s.getAmount(), missing);
                s.setAmount(s.getAmount() + can);
                missing -= can;
                placed = true;
                if (missing <= 0) break;
            }
        }
        if (missing > 0 && !placed) {
            inv.addItem(new ItemStack(team.getWoolMaterial(), missing));
        } else if (missing > 0) {
            inv.addItem(new ItemStack(team.getWoolMaterial(), missing));
        }
    }

    private ItemStack makeUnbreakable(ItemStack s) {
        ItemMeta m = s.getItemMeta();
        if (m != null) {
            m.setUnbreakable(true);
            s.setItemMeta(m);
        }
        return s;
    }
}
