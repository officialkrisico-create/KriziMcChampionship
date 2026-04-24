package nl.kmc.adventure.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.EffectBlock;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Reads all effect-block definitions from config and applies them
 * when a player walks over a matching block.
 *
 * <p><b>Paper 1.21 compatibility:</b> Uses Paper's {@link RegistryAccess}
 * for all Sound/Enchantment/PotionEffectType lookups. This avoids both
 * the {@code IncompatibleClassChangeError} on {@code Sound.valueOf} and
 * the deprecation warnings on static enum fields like
 * {@code Enchantment.DEPTH_STRIDER}.
 */
public class EffectBlockManager {

    private final AdventureEscapePlugin plugin;

    /** Lookup: Material → EffectBlock (O(1) match on each player step). */
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
            if (soundName != null && !soundName.isBlank()) {
                sound = resolveSound(soundName);
                if (sound == null) {
                    plugin.getLogger().warning("Unknown sound '" + soundName
                            + "' for effect block " + id + " — no sound will play.");
                }
            }

            byMaterial.put(mat, new EffectBlock(id, mat, duration, effects, equipment, message, sound));
        }

        plugin.getLogger().info("Loaded " + byMaterial.size() + " effect blocks.");
    }

    // ----------------------------------------------------------------
    // Registry lookup helpers — non-deprecated Paper 1.21 API
    // ----------------------------------------------------------------

    private Sound resolveSound(String raw) {
        try {
            String key = raw.toLowerCase().replace('_', '.');
            var reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
            Sound s = reg.get(NamespacedKey.minecraft(key));
            if (s != null) return s;
            return reg.get(NamespacedKey.minecraft(raw.toLowerCase()));
        } catch (Exception e) {
            return null;
        }
    }

    private Enchantment lookupEnchantment(String id) {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(id));
        } catch (Exception e) {
            return null;
        }
    }

    private PotionEffectType lookupEffect(String id) {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft(id));
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------------
    // Query
    // ----------------------------------------------------------------

    public EffectBlock getForMaterial(Material mat) { return byMaterial.get(mat); }

    // ----------------------------------------------------------------
    // Application
    // ----------------------------------------------------------------

    public void apply(Player player, EffectBlock eb) {
        int ticks = eb.getDurationSeconds() * 20;

        for (Map<String, Object> effectDef : eb.getEffects()) {
            PotionEffectType type = mapEffectType(String.valueOf(effectDef.get("type")));
            if (type == null) continue;
            int amplifier = (int) effectDef.getOrDefault("amplifier", 0);
            player.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, true, true));
        }

        for (String key : eb.getEquipment()) applyEquipment(player, key);

        if (eb.getMessage() != null && !eb.getMessage().isBlank()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', eb.getMessage())));
        }

        if (eb.getSound() != null) {
            player.playSound(player.getLocation(), eb.getSound(), 1.0f, 1.0f);
        }
    }

    /** Maps friendly config keys (speed, jump, ...) to vanilla ids, then registry-looks up. */
    private PotionEffectType mapEffectType(String key) {
        if (key == null) return null;
        String mcId = switch (key.toLowerCase()) {
            case "speed"        -> "speed";
            case "jump"         -> "jump_boost";
            case "haste"        -> "haste";
            case "strength"     -> "strength";
            case "regen"        -> "regeneration";
            case "dolphin"      -> "dolphins_grace";
            case "night_vision" -> "night_vision";
            case "slow_fall"    -> "slow_falling";
            case "levitation"   -> "levitation";
            case "fire_res"     -> "fire_resistance";
            case "water_breath" -> "water_breathing";
            case "invisibility" -> "invisibility";
            // Accept vanilla names directly too
            default             -> key.toLowerCase().replace('.', '_');
        };
        return lookupEffect(mcId);
    }

    private void applyEquipment(Player player, String key) {
        switch (key.toLowerCase()) {
            case "elytra" -> player.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
            case "elytra_rocket" -> {
                player.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
                player.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, 5));
            }
            case "depth_strider_boots" -> {
                ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
                ItemMeta meta = boots.getItemMeta();
                Enchantment ds = lookupEnchantment("depth_strider");
                if (ds != null && meta != null) meta.addEnchant(ds, 3, true);
                if (meta != null) boots.setItemMeta(meta);
                player.getInventory().setBoots(boots);
            }
            case "feather_boots" -> {
                ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
                ItemMeta meta = boots.getItemMeta();
                Enchantment ff = lookupEnchantment("feather_falling");
                if (ff != null && meta != null) meta.addEnchant(ff, 4, true);
                if (meta != null) boots.setItemMeta(meta);
                player.getInventory().setBoots(boots);
            }
        }
    }
}