package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import nl.kmc.mayhem.models.PowerupType;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Spawns powerups around a single team's cloned arena on a timer — same
 * pattern as QuakeCraft's {@code PowerupSpawner}: each configured location
 * runs its own independent cycle (more locations = more frequent overall
 * spawns), holds at most one powerup at a time, and unclaimed pickups
 * auto-despawn.
 *
 * <p>One instance runs per team for the whole match (not per-wave).
 */
public class PowerupSpawner {

    public static final String SPAWNER_KEY = "mm_powerup_type";

    private final MobMayhemPlugin plugin;
    private final Arena           arena;
    private final Random          random = new Random();

    private final Map<Integer, Item>      activeAt      = new HashMap<>();
    private final Map<Integer, BukkitTask> locationTasks = new HashMap<>();

    public PowerupSpawner(MobMayhemPlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.arena  = arena;
    }

    public void start() {
        stop();
        List<Location> locations = arena.getPowerupSpawns();
        if (locations.isEmpty()) return;

        int interval = plugin.getConfig().getInt("powerups.interval-seconds", 30);
        long period  = Math.max(20L, 20L * interval);

        for (int i = 0; i < locations.size(); i++) {
            int idx = i;
            long initial = (long) (random.nextDouble() * period);
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> tickLocation(idx), initial, period);
            locationTasks.put(idx, task);
        }
    }

    public void stop() {
        for (BukkitTask t : locationTasks.values()) if (t != null) t.cancel();
        locationTasks.clear();
        for (Item item : activeAt.values()) {
            if (item != null && !item.isDead()) item.remove();
        }
        activeAt.clear();
    }

    private void tickLocation(int idx) {
        List<Location> locations = arena.getPowerupSpawns();
        if (idx >= locations.size()) return;
        Location loc = locations.get(idx);
        Item existing = activeAt.get(idx);
        if (existing != null && !existing.isDead() && existing.isValid()) return;

        PowerupType type = pickWeightedType();
        if (type == null) return;
        spawnAt(loc, idx, type);
    }

    private PowerupType pickWeightedType() {
        var weights = plugin.getConfig().getConfigurationSection("powerups.weights");
        if (weights == null) {
            // No config — spawn any type with equal weight.
            PowerupType[] all = PowerupType.values();
            return all[random.nextInt(all.length)];
        }

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

    private void spawnAt(Location loc, int idx, PowerupType type) {
        World world = loc.getWorld();
        if (world == null) return;

        ItemStack stack = new ItemStack(type.getIcon());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + type.getDisplayName());
            stack.setItemMeta(meta);
        }

        Item item = world.dropItem(loc.clone().add(0, 0.5, 0), stack);
        item.setVelocity(new org.bukkit.util.Vector(0, 0.05, 0));
        item.setUnlimitedLifetime(true);
        item.setPickupDelay(10);
        item.setGlowing(true);
        item.setCustomNameVisible(true);

        var key = new NamespacedKey(plugin, SPAWNER_KEY);
        item.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.name());

        activeAt.put(idx, item);

        world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 30, 0.5, 0.5, 0.5,
                new Particle.DustOptions(Color.AQUA, 1.5f));

        int despawnSec = plugin.getConfig().getInt("powerups.despawn-seconds", 45);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Item current = activeAt.get(idx);
            if (current != item) return;
            if (!item.isDead() && item.isValid()) {
                item.remove();
                world.playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 0.3f, 1.5f);
            }
            activeAt.remove(idx);
        }, despawnSec * 20L);
    }

    /** Called by the pickup listener once a powerup item has been consumed. */
    public void onPickedUp(Item item) {
        activeAt.entrySet().removeIf(e -> e.getValue().equals(item));
    }

    public static PowerupType getPowerupType(MobMayhemPlugin plugin, Item item) {
        if (item == null) return null;
        var key = new NamespacedKey(plugin, SPAWNER_KEY);
        var pdc = item.getPersistentDataContainer();
        if (!pdc.has(key, PersistentDataType.STRING)) return null;
        try { return PowerupType.valueOf(pdc.get(key, PersistentDataType.STRING)); }
        catch (Exception e) { return null; }
    }

    /** Applies a powerup's effect to the player who picked it up. */
    public static void apply(Player p, PowerupType type) {
        if (type == PowerupType.INSTANT_HEAL) {
            p.setHealth(Math.min(20.0, p.getHealth() + 10.0));
        }
        type.buildEffects().forEach(p::addPotionEffect);
        p.sendMessage(ChatColor.AQUA + "✦ " + ChatColor.GRAY + "Je hebt " + ChatColor.AQUA
                + type.getDisplayName() + ChatColor.GRAY + " opgepakt!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }
}
