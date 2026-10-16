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
    /** The powerup each player is holding (one at a time) — used with a right-click. */
    private final Map<UUID, Type> held = new HashMap<>();
    private static final int HOTBAR_SLOT = 4;

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
        held.clear();
        removeDecoys();
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

    /** True if this player may pick up a powerup (they hold none right now). */
    public boolean canHold(UUID id) { return !held.containsKey(id); }

    /** True if {@code item} is one of our powerup items (floating or in an inventory). */
    public boolean isPowerupItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer()
                .has(new NamespacedKey(plugin, KEY), PersistentDataType.STRING);
    }

    private void giveHeldItem(Player p, Type type) {
        ItemStack stack = new ItemStack(type.material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', type.display));
            meta.setLore(java.util.List.of(ChatColor.GRAY + "Rechtsklik om te gebruiken"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY), PersistentDataType.STRING, type.name());
            stack.setItemMeta(meta);
        }
        p.getInventory().setItem(HOTBAR_SLOT, stack);
    }

    /** Right-click: fire the held powerup. */
    public void use(Player p) {
        Type type = held.remove(p.getUniqueId());
        if (type == null) return;
        p.getInventory().setItem(HOTBAR_SLOT, null);
        var gm = plugin.getTntManagerV2();
        PlayerState ps = gm != null ? gm.getPlayersMap().get(p.getUniqueId()) : null;
        apply(type, p, ps);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
    }

    /** Drops everything players were holding (game end). */
    public void clearHeld() { held.clear(); }

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

        // Stored, not fired: it goes into slot 5 and is used with a right-click.
        held.put(p.getUniqueId(), type);
        giveHeldItem(p, type);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.translateAlternateColorCodes('&', type.display + " &7opgepakt — &frechtsklik om te gebruiken!")));
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

    /** Live decoy NPCs (mannequins) — removed on a hit, when they expire, and at game end. */
    private final java.util.Set<org.bukkit.entity.Mannequin> decoys = new java.util.HashSet<>();

    private void spawnDecoy(Player p) {
        Location at = p.getLocation();
        org.bukkit.entity.Mannequin npc;
        try {
            npc = at.getWorld().spawn(at, org.bukkit.entity.Mannequin.class, m -> {
                m.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
                m.setImmovable(false);
                m.setInvulnerable(false);
                m.setCustomName(ChatColor.GRAY + p.getName());
                m.setCustomNameVisible(false);
                m.getEquipment().setHelmet(p.getInventory().getHelmet());
                m.getEquipment().setChestplate(p.getInventory().getChestplate());
                m.getEquipment().setLeggings(p.getInventory().getLeggings());
                m.getEquipment().setBoots(p.getInventory().getBoots());
                m.getPersistentDataContainer().set(new NamespacedKey(plugin, "tnttag_decoy"), PersistentDataType.BYTE, (byte) 1);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[TNTTag] Mannequin decoy unavailable: " + t);
            return;
        }
        decoys.add(npc);
        // The decoy runs around in a 3 x 3 square on the spot where it was dropped (not away in one line), so it
        // keeps looking like a player who is dodging — 8 seconds.
        final Location origin = at.clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            int retarget = 0;
            double tx = origin.getX(), tz = origin.getZ();
            @Override public void run() {
                if (npc.isDead() || !npc.isValid() || ticks++ > 160) { remove(npc); cancel(); return; }
                Location cur = npc.getLocation();
                double dx = tx - cur.getX(), dz = tz - cur.getZ();
                double dist = Math.hypot(dx, dz);
                if (dist < 0.25 || retarget-- <= 0) {
                    java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
                    tx = origin.getX() + r.nextDouble(-1.5, 1.5);
                    tz = origin.getZ() + r.nextDouble(-1.5, 1.5);
                    retarget = 10 + r.nextInt(10);
                    return;
                }
                double step = Math.min(0.3, dist);
                Location next = cur.clone().add(dx / dist * step, 0, dz / dist * step);
                next.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                // never into a wall: pick another corner of the square
                if (next.getBlock().getType().isSolid() && next.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                    retarget = 0;
                    return;
                }
                if (!next.getBlock().getType().isSolid()) npc.teleport(next);
            }
        }.runTaskTimer(plugin, 2L, 1L);
        p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0);
    }

    private void remove(org.bukkit.entity.Mannequin npc) {
        decoys.remove(npc);
        if (npc != null && !npc.isDead()) {
            npc.getWorld().spawnParticle(Particle.POOF, npc.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
            npc.remove();
        }
    }

    /** A bomb carrier hit a decoy: it pops and the hit is wasted. */
    public boolean popDecoy(org.bukkit.entity.Entity e) {
        if (!(e instanceof org.bukkit.entity.Mannequin m) || !decoys.contains(m)) return false;
        remove(m);
        return true;
    }

    public void removeDecoys() {
        for (var m : new java.util.ArrayList<>(decoys)) remove(m);
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
