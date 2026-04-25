package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages player gear:
 * <ul>
 *   <li>Wooden starter kit at game start</li>
 *   <li>Wave-end drops at the team's spawn</li>
 * </ul>
 *
 * <p>Wave drop tier scales with wave number. Early waves drop stone,
 * mid-game iron, late-game diamond + arrows + golden apples.
 */
public class KitManager {

    private final MobMayhemPlugin plugin;

    public KitManager(MobMayhemPlugin plugin) { this.plugin = plugin; }

    /** Gives the wooden starter kit + 32 cooked beef. */
    public void giveStarterKit(Player p) {
        var inv = p.getInventory();
        inv.clear();
        inv.setItem(0, new ItemStack(Material.WOODEN_SWORD));
        inv.setItem(1, new ItemStack(Material.WOODEN_AXE));
        inv.setItem(2, new ItemStack(Material.BOW));
        inv.setItem(8, new ItemStack(Material.COOKED_BEEF, 32));
        inv.setItem(9, new ItemStack(Material.ARROW, 16));

        // Leather armor for slight defense
        inv.setHelmet(new ItemStack(Material.LEATHER_HELMET));
        inv.setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        inv.setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        inv.setBoots(new ItemStack(Material.LEATHER_BOOTS));
    }

    /**
     * Returns the loot drops for completing a wave. Scales with wave number.
     */
    public List<ItemStack> getWaveDropLoot(int waveNumber) {
        List<ItemStack> drops = new ArrayList<>();

        if (waveNumber == 2) {
            drops.add(new ItemStack(Material.STONE_SWORD));
            drops.add(new ItemStack(Material.STONE_AXE));
            drops.add(new ItemStack(Material.ARROW, 16));
            drops.add(new ItemStack(Material.COOKED_BEEF, 16));
        } else if (waveNumber == 3) {
            drops.add(new ItemStack(Material.CHAINMAIL_HELMET));
            drops.add(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
            drops.add(new ItemStack(Material.SHIELD));
            drops.add(new ItemStack(Material.ARROW, 32));
        } else if (waveNumber == 4) {
            drops.add(new ItemStack(Material.IRON_SWORD));
            drops.add(new ItemStack(Material.IRON_AXE));
            drops.add(new ItemStack(Material.GOLDEN_APPLE, 2));
            drops.add(new ItemStack(Material.COOKED_BEEF, 16));
        } else if (waveNumber == 5) {
            drops.add(new ItemStack(Material.IRON_HELMET));
            drops.add(new ItemStack(Material.IRON_CHESTPLATE));
            drops.add(new ItemStack(Material.IRON_LEGGINGS));
            drops.add(new ItemStack(Material.IRON_BOOTS));
        } else if (waveNumber == 6) {
            ItemStack enchSword = new ItemStack(Material.IRON_SWORD);
            enchSword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 2);
            drops.add(enchSword);
            drops.add(new ItemStack(Material.GOLDEN_APPLE, 4));
            drops.add(new ItemStack(Material.ARROW, 32));
            drops.add(new ItemStack(Material.COOKED_BEEF, 32));
        } else if (waveNumber == 7) {
            // After mini-boss: big upgrade
            drops.add(new ItemStack(Material.DIAMOND_SWORD));
            drops.add(new ItemStack(Material.DIAMOND_HELMET));
            drops.add(new ItemStack(Material.DIAMOND_CHESTPLATE));
            drops.add(new ItemStack(Material.GOLDEN_APPLE, 6));
        } else if (waveNumber == 8) {
            drops.add(new ItemStack(Material.DIAMOND_LEGGINGS));
            drops.add(new ItemStack(Material.DIAMOND_BOOTS));
            drops.add(new ItemStack(Material.ARROW, 64));
            drops.add(new ItemStack(Material.COOKED_BEEF, 32));
        } else if (waveNumber == 9) {
            ItemStack enchBow = new ItemStack(Material.BOW);
            enchBow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, 3);
            drops.add(enchBow);
            ItemStack enchSword = new ItemStack(Material.DIAMOND_SWORD);
            enchSword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 3);
            drops.add(enchSword);
            drops.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
        }
        // Wave 10 (final boss) has no post-drops; game ends after

        return drops;
    }
}
