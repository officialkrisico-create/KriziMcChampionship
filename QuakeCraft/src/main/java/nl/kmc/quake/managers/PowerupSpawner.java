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
    private final Random random = new Random();

    /** Tracks which locations currently have a live powerup item. */
    private final Map<String, Item> activeAt = new HashMap<>();
    /** One independent spawn timer PER location (rate scales with #locations). */
    private final Map<String, BukkitTask> locationTasks = new HashMap<>();

    public PowerupSpawner(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    public void start() {
        stop();
        int interval = plugin.getConfig().getInt("powerup-spawning.interval-seconds", 30);
        var locations = plugin.getArenaManager().getPowerupLocations();
        long period = Math.max(20L, 20L * interval);

        // Each location runs its OWN cycle: with 1 location powerups are scarce,
        // with 45 locations they appear far more often. Initial delay is staggered
        // so they don't all pop at the exact same tick.
        for (String key : locations.keySet()) {
            long initial = (long) (random.nextDouble() * period);
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> tickLocation(key), initial, period);
            locationTasks.put(key, task);
        }
        plugin.getLogger().info("Powerup spawner started: " + locations.size()
                + " locaties, elk interval " + interval + "s.");
    }

    public void stop() {
        for (BukkitTask t : locationTasks.values()) if (t != null) t.cancel();
        locationTasks.clear();
        // Remove all active items
        for (Item item : activeAt.values()) {
            if (item != null && !item.isDead()) item.remove();
        }
        activeAt.clear();
    }

    /** Refills one specific location if it's currently empty. */
    private void tickLocation(String key) {
        Location loc = plugin.getArenaManager().getPowerupLocations().get(key);
        if (loc == null) return;
        Item existing = activeAt.get(key);
        if (existing != null && !existing.isDead() && existing.isValid()) return; // still occupied
        PowerupType type = pickWeightedType();
        if (type == null) return;
        spawnAt(loc, key, type);
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

        announceRarity(type, locationKey);

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

    /**
     * Broadcasts an announcement for rare+ powerups so the whole lobby knows
     * something valuable just appeared (and where). Tier comes from
     * {@code powerups.<key>.rarity} (common|rare|epic|legendary).
     */
    private void announceRarity(PowerupType type, String locationKey) {
        String rarity = plugin.getConfig().getString(
                "powerups." + type.getConfigKey() + ".rarity", "common").toLowerCase();
        if (rarity.equals("common")) return;

        String display = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("powerups." + type.getConfigKey() + ".display-name", type.name()));

        String tierLabel = switch (rarity) {
            case "legendary" -> "&6&lLEGENDARY";
            case "epic"      -> "&5&lEPIC";
            case "rare"      -> "&b&lRARE";
            default          -> "&f" + rarity;
        };

        String msg = ChatColor.translateAlternateColorCodes('&',
                tierLabel + " &7powerup gespawnd: " + display + " &7@ &e" + locationKey);
        Bukkit.broadcastMessage(msg);

        // Legendary gets a global stinger; epic/rare a softer ping.
        if (rarity.equals("legendary")) {
            nl.kmc.quake.util.Sfx.playGlobal(plugin, "rarity.legendary",
                    Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        } else {
            nl.kmc.quake.util.Sfx.playGlobal(plugin, "rarity." + rarity,
                    Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.4f);
        }
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
