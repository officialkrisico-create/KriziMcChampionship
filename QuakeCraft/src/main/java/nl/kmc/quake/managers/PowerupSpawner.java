package nl.kmc.quake.managers;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PowerupType;
import nl.kmc.quake.util.WeaponFactory;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Spawns powerups at configured arena locations on a timer.
 *
 * <p>Powerups are placed as floating Item entities. When picked up,
 * the listener checks the NBT tag to determine the powerup type and
 * applies it to the player.
 *
 * <p>Each location holds at most one powerup at a time. Unclaimed
 * powerups despawn after a configured duration.
 */
public class PowerupSpawner {

    public static final String SPAWNER_KEY = "quake_powerup_spawn";

    private final QuakeCraftPlugin plugin;
    private BukkitTask spawnTask;
    private final Random random = new Random();

    /** Tracks which locations currently have a live powerup item. */
    private final Map<String, Item> activeAt = new HashMap<>();

    public PowerupSpawner(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    public void start() {
        stop();
        int interval = plugin.getConfig().getInt("powerup-spawning.interval-seconds", 30);
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::trySpawnRandom, 20L * interval, 20L * interval);
        plugin.getLogger().info("Powerup spawner started (interval " + interval + "s).");
    }

    public void stop() {
        if (spawnTask != null) { spawnTask.cancel(); spawnTask = null; }
        // Remove all active items
        for (Item item : activeAt.values()) {
            if (item != null && !item.isDead()) item.remove();
        }
        activeAt.clear();
    }

    private void trySpawnRandom() {
        var locations = plugin.getArenaManager().getPowerupLocations();
        if (locations.isEmpty()) return;

        // Pick a location that doesn't currently have a powerup
        List<String> available = new ArrayList<>();
        for (var key : locations.keySet()) {
            Item existing = activeAt.get(key);
            if (existing == null || existing.isDead() || !existing.isValid()) {
                available.add(key);
            }
        }
        if (available.isEmpty()) return;

        String locationKey = available.get(random.nextInt(available.size()));
        Location loc = locations.get(locationKey);
        if (loc == null) return;

        // Pick a powerup type by weight
        PowerupType type = pickWeightedType();
        if (type == null) return;

        spawnAt(loc, locationKey, type);
    }

    private PowerupType pickWeightedType() {
        var weights = plugin.getConfig().getConfigurationSection("powerup-spawning.weights");
        if (weights == null) return null;

        List<PowerupType> options = new ArrayList<>();
        List<Integer>     wts     = new ArrayList<>();
        int total = 0;

        for (String key : weights.getKeys(false)) {
            PowerupType type = PowerupType.fromConfigKey(key);
            if (type == null) continue;
            int weight = weights.getInt(key, 0);
            if (weight <= 0) continue;
            options.add(type);
            wts.add(weight);
            total += weight;
        }

        if (options.isEmpty() || total == 0) return null;

        int roll = random.nextInt(total);
        int sum  = 0;
        for (int i = 0; i < options.size(); i++) {
            sum += wts.get(i);
            if (roll < sum) return options.get(i);
        }
        return options.get(options.size() - 1);
    }

    private void spawnAt(Location loc, String locationKey, PowerupType type) {
        World world = loc.getWorld();
        if (world == null) return;

        int uses = plugin.getConfig().getInt("powerups." + type.getConfigKey() + ".uses", 1);
        ItemStack stack = WeaponFactory.buildPowerup(plugin, type, uses);

        Item item = world.dropItem(loc.clone().add(0, 0.5, 0), stack);
        item.setVelocity(new org.bukkit.util.Vector(0, 0.05, 0));
        item.setUnlimitedLifetime(true); // we control despawn
        item.setPickupDelay(10);
        item.setGlowing(true);
        item.customName(net.kyori.adventure.text.Component.text(
                ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("powerups." + type.getConfigKey() + ".display-name", type.name()))));
        item.setCustomNameVisible(true);

        // Tag so the pickup listener knows it's a powerup
        var key = new NamespacedKey(plugin, SPAWNER_KEY);
        item.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.name());

        activeAt.put(locationKey, item);

        world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);
        world.spawnParticle(Particle.DUST,
                loc.clone().add(0, 1, 0), 30, 0.5, 0.5, 0.5,
                new Particle.DustOptions(Color.AQUA, 1.5f));

        // Schedule despawn
        int despawnSec = plugin.getConfig().getInt("powerup-spawning.despawn-seconds", 60);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (item.isDead() || !item.isValid()) {
                activeAt.remove(locationKey);
                return;
            }
            Item current = activeAt.get(locationKey);
            if (current == item) {
                item.remove();
                activeAt.remove(locationKey);
                world.playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 0.3f, 1.5f);
            }
        }, despawnSec * 20L);
    }

    /** Called by the listener when a powerup item is picked up. */
    public void onPickedUp(Item item) {
        // Remove from tracking
        activeAt.entrySet().removeIf(e -> e.getValue().equals(item));
    }

    public static PowerupType getPowerupType(QuakeCraftPlugin plugin, Item item) {
        if (item == null) return null;
        var key = new NamespacedKey(plugin, SPAWNER_KEY);
        var pdc = item.getPersistentDataContainer();
        if (!pdc.has(key, PersistentDataType.STRING)) return null;
        try {
            return PowerupType.valueOf(pdc.get(key, PersistentDataType.STRING));
        } catch (Exception e) { return null; }
    }
}
