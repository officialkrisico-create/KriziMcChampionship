package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.EffectBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.logging.Level;

/**
 * Reads all effect-block definitions from config and applies them
 * when a player walks over a matching block.
 */
public class EffectBlockManager {

    private final AdventureEscapePlugin plugin;

    /** Lookup: Material → EffectBlock (for O(1) match on each step). */
    private final Map<Material, EffectBlock> byMaterial = new HashMap<>();

    public EffectBlockManager(AdventureEscapePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ----------------------------------------------------------------
    // Loading
    // ----------------------------------------------------------------

    public void load() {
        byMaterial.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("effect-blocks");
        if (sec == null) {
            plugin.getLogger().warning("No effect-blocks in config!");
            return;
        }

        for (String id : sec.getKeys(false)) {
            ConfigurationSection b = sec.getConfigurationSection(id);
            if (b == null) continue;

            Material mat;
            try {
                mat = Material.valueOf(b.getString("material", "STONE").toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material for effect block " + id);
                continue;
            }

            int duration = b.getInt("duration", 5);

            // Effects list
            List<Map<String, Object>> effects = new ArrayList<>();
            List<Map<?, ?>> rawEffects = b.getMapList("effects");
            for (Map<?, ?> m : rawEffects) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("type", String.valueOf(m.get("type")));
                entry.put("amplifier", m.get("amplifier") instanceof Number n ? n.intValue() : 0);
                effects.add(entry);
            }

            List<String> equipment = b.getStringList("equipment");
            String message = b.getString("message", "");

            Sound sound = null;
            String soundName = b.getString("sound");
            if (soundName != null) {
                try { sound = Sound.valueOf(soundName.toUpperCase()); }
                catch (IllegalArgumentException ignored) {}
            }

            byMaterial.put(mat, new EffectBlock(id, mat, duration, effects, equipment, message, sound));
        }

        plugin.getLogger().info("Loaded " + byMaterial.size() + " effect blocks.");
    }

    // ----------------------------------------------------------------
    // Query
    // ----------------------------------------------------------------

    public EffectBlock getForMaterial(Material mat) { return byMaterial.get(mat); }

    // ----------------------------------------------------------------
    // Application
    // ----------------------------------------------------------------

    /**
     * Applies an effect block to a player: potion effects + equipment + sound + message.
     */
    public void apply(Player player, EffectBlock eb) {
        int ticks = eb.getDurationSeconds() * 20;

        // Effects
        for (Map<String, Object> effectDef : eb.getEffects()) {
            PotionEffectType type = mapEffectType(String.valueOf(effectDef.get("type")));
            if (type == null) continue;
            int amplifier = (int) effectDef.getOrDefault("amplifier", 0);
            // true = ambient (no particle spam), true = show particles, true = icon
            player.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, true, true));
        }

        // Equipment
        for (String key : eb.getEquipment()) applyEquipment(player, key, ticks);

        // Message
        if (eb.getMessage() != null && !eb.getMessage().isBlank()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', eb.getMessage())));
        }

        // Sound
        if (eb.getSound() != null) {
            player.playSound(player.getLocation(), eb.getSound(), 1.0f, 1.0f);
        }
    }

    /** Maps config effect key → Bukkit PotionEffectType. */
    private PotionEffectType mapEffectType(String key) {
        return switch (key.toLowerCase()) {
            case "speed"        -> PotionEffectType.SPEED;
            case "jump"         -> PotionEffectType.JUMP_BOOST;
            case "haste"        -> PotionEffectType.HASTE;
            case "strength"     -> PotionEffectType.STRENGTH;
            case "regen"        -> PotionEffectType.REGENERATION;
            case "dolphin"      -> PotionEffectType.DOLPHINS_GRACE;
            case "night_vision" -> PotionEffectType.NIGHT_VISION;
            case "slow_fall"    -> PotionEffectType.SLOW_FALLING;
            case "levitation"   -> PotionEffectType.LEVITATION;
            case "fire_res"     -> PotionEffectType.FIRE_RESISTANCE;
            case "water_breath" -> PotionEffectType.WATER_BREATHING;
            case "invisibility" -> PotionEffectType.INVISIBILITY;
            default             -> null;
        };
    }

    private void applyEquipment(Player player, String key, int ticks) {
        switch (key.toLowerCase()) {
            case "elytra" -> {
                player.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
            }
            case "elytra_rocket" -> {
                player.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
                ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET, 5);
                player.getInventory().addItem(rockets);
            }
            case "depth_strider_boots" -> {
                ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
                ItemMeta meta = boots.getItemMeta();
                meta.addEnchant(Enchantment.DEPTH_STRIDER, 3, true);
                boots.setItemMeta(meta);
                player.getInventory().setBoots(boots);
            }
            case "feather_boots" -> {
                ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
                ItemMeta meta = boots.getItemMeta();
                meta.addEnchant(Enchantment.FEATHER_FALLING, 4, true);
                boots.setItemMeta(meta);
                player.getInventory().setBoots(boots);
            }
        }
    }
}
