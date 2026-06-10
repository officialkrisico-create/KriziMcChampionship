package nl.kmc.tnttag.managers;

import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.PlayerState;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Spawns floating powerups at the arena's powerup spots and applies their
 * effect on pickup. Powerups encourage movement and create strategic plays.
 */
public final class PowerupManager {

    public enum Type {
        SPEED(Material.SUGAR,        "&b&lSpeed Boost"),
        SHIELD(Material.SHIELD,      "&e&lShield"),
        RADAR(Material.COMPASS,      "&a&lRadar"),
        TELEPORTER(Material.ENDER_PEARL, "&d&lTeleporter"),
        FREEZE(Material.PACKED_ICE,  "&b&lFreeze Bomb"),
        SMOKE(Material.GUNPOWDER,    "&8&lSmoke Bomb"),
        DASH(Material.FEATHER,       "&f&lDash Orb"),
        GRAPPLE(Material.FISHING_ROD,"&a&lGrapple Charge"),
        DECOY(Material.ARMOR_STAND,  "&5&lDecoy");

        final Material material; final String display;
        Type(Material m, String d) { this.material = m; this.display = d; }
    }

    private static final String KEY = "tnttag_powerup";

    private final TNTTagPlugin plugin;
    private BukkitTask spawnTask;
    private final Map<Integer, Item> live = new HashMap<>(); // spawn-index → item
    private boolean enabled = true;
    private final Random random = new Random();

    public PowerupManager(TNTTagPlugin plugin) { this.plugin = plugin; }

    public void setEnabled(boolean e) { this.enabled = e; }

    public void start() {
        stop();
        enabled = true;
        if (plugin.getArenaManager().getArena().getPowerupSpawns().isEmpty()) return;
        int interval = plugin.getConfig().getInt("powerups.spawn-interval-seconds", 12);
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trySpawn, 20L * interval, 20L * interval);
    }

    public void stop() {
        if (spawnTask != null) { spawnTask.cancel(); spawnTask = null; }
        live.values().forEach(i -> { if (i != null && !i.isDead()) i.remove(); });
        live.clear();
    }

    private void trySpawn() {
        if (!enabled) return;
        var spots = plugin.getArenaManager().getArena().getPowerupSpawns();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) {
            Item it = live.get(i);
            if (it == null || it.isDead() || !it.isValid()) free.add(i);
        }
        if (free.isEmpty()) return;
        int idx = free.get(random.nextInt(free.size()));
        Location loc = spots.get(idx);
        if (loc.getWorld() == null) return;

        Type type = Type.values()[random.nextInt(Type.values().length)];
        ItemStack stack = new ItemStack(type.material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', type.display));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY), PersistentDataType.STRING, type.name());
            stack.setItemMeta(meta);
        }
        Item item = loc.getWorld().dropItem(loc.clone().add(0, 0.6, 0), stack);
        item.setUnlimitedLifetime(true);
        item.setPickupDelay(8);
        item.setGlowing(true);
        item.setVelocity(new org.bukkit.util.Vector(0, 0.05, 0));
        item.customName(net.kyori.adventure.text.Component.text(
                ChatColor.translateAlternateColorCodes('&', type.display)));
        item.setCustomNameVisible(true);
        live.put(idx, item);
        loc.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.8f);
    }

    /** Returns true if the item was a powerup (and consumes/applies it). */
    public boolean handlePickup(Player p, Item item) {
        ItemStack stack = item.getItemStack();
        if (stack.getItemMeta() == null) return false;
        String typeName = stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, KEY), PersistentDataType.STRING);
        if (typeName == null) return false;

        Type type;
        try { type = Type.valueOf(typeName); } catch (Exception e) { return false; }

        live.values().remove(item);
        item.remove();
        var gm = plugin.getTntManagerV2();
        PlayerState ps = gm != null ? gm.getPlayersMap().get(p.getUniqueId()) : null;
        if (ps != null) ps.incrementPowerups();

        apply(type, p, ps);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.translateAlternateColorCodes('&', type.display + " &7opgepakt!")));
        return true;
    }

    private void apply(Type type, Player p, PlayerState ps) {
        switch (type) {
            case SPEED -> p.addPotionEffect(new PotionEffect(effect("speed"), 200, 1, true, true, true));
            case SHIELD -> { if (ps != null) ps.setShielded(true);
                p.sendTitle("§e§lSHIELD", "§7Blokkeert de volgende TNT-overdracht", 5, 30, 5); }
            case TELEPORTER -> {
                var spawns = plugin.getArenaManager().getArena().getSpawns();
                if (!spawns.isEmpty()) {
                    p.teleport(spawns.get(random.nextInt(spawns.size())));
                    p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 40, 0.5, 1, 0.5, 0.1);
                }
            }
            case RADAR -> revealTntHolders();
            case FREEZE -> freezeNearby(p);
            case SMOKE -> smokeNearby(p);
            case DASH -> {
                p.setVelocity(p.getLocation().getDirection().multiply(1.7).setY(0.4));
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 20, 0.3, 0.3, 0.3, 0.05);
            }
            case GRAPPLE -> {
                p.setVelocity(p.getEyeLocation().getDirection().multiply(2.2).add(new org.bukkit.util.Vector(0, 0.55, 0)));
                p.getWorld().spawnParticle(Particle.CRIT, p.getLocation(), 25, 0.3, 0.3, 0.3, 0.1);
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 1.2f);
            }
            case DECOY -> spawnDecoy(p);
        }
    }

    private void spawnDecoy(Player p) {
        var as = p.getWorld().spawn(p.getLocation(), org.bukkit.entity.ArmorStand.class, a -> {
            a.customName(net.kyori.adventure.text.Component.text(ChatColor.translateAlternateColorCodes('&', "&7" + p.getName())));
            a.setCustomNameVisible(true);
            a.setBasePlate(false);
            a.setArms(true);
            a.setInvulnerable(true);
            a.getEquipment().setHelmet(p.getInventory().getHelmet());
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!as.isDead()) as.remove(); }, 160L);
        p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0);
    }

    private void revealTntHolders() {
        var glow = effect("glowing");
        var gm = plugin.getTntManagerV2();
        if (glow == null || gm == null) return;
        gm.getPlayersMap().values().stream().filter(PlayerState::isIt).forEach(ps -> {
            Player t = Bukkit.getPlayer(ps.getUuid());
            if (t != null) t.addPotionEffect(new PotionEffect(glow, 200, 0, true, false, true));
        });
    }

    private void freezeNearby(Player source) {
        var slow = effect("slowness");
        if (slow == null) return;
        for (var e : source.getNearbyEntities(5, 3, 5)) {
            if (e instanceof Player other && !other.equals(source)) {
                other.addPotionEffect(new PotionEffect(slow, 40, 255, true, false, false));
                other.getWorld().spawnParticle(Particle.SNOWFLAKE, other.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
            }
        }
        source.getWorld().playSound(source.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
    }

    private void smokeNearby(Player source) {
        var blind = effect("blindness");
        Location c = source.getLocation();
        for (int t = 0; t < 60; t += 5) {
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    c.getWorld().spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, 1, 0), 30, 2.5, 1.5, 2.5, 0.01), t);
        }
        if (blind != null) for (var e : source.getNearbyEntities(4, 3, 4))
            if (e instanceof Player other && !other.equals(source))
                other.addPotionEffect(new PotionEffect(blind, 60, 0, true, false, false));
    }

    private static org.bukkit.potion.PotionEffectType effect(String name) {
        try { return io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(name)); }
        catch (Throwable t) { return null; }
    }
}
