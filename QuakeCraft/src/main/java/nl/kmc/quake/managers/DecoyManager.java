package nl.kmc.quake.managers;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.util.Sfx;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Hologram Decoy — spawns a static player-look-alike armor stand that wears the
 * deployer's skin (player head) and a team-coloured chestplate. Enemies who
 * shoot it waste their shot: the railgun's ray-trace pops the decoy instead of
 * killing, since a decoy is not a real player.
 *
 * <p>Decoys auto-despawn after a configurable lifetime and are cleared when the
 * game ends.
 */
public final class DecoyManager {

    private static final String DECOY_KEY = "quake_decoy";

    private final QuakeCraftPlugin plugin;
    private final Set<UUID> decoys = new HashSet<>();
    private BukkitTask cleanupTask;

    public DecoyManager(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    public void start() { stop(); }

    public void stop() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        for (UUID id : new HashSet<>(decoys)) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) e.remove();
        }
        decoys.clear();
    }

    /** Deploys a decoy at the player's location, facing the same way. */
    public void deploy(Player owner) {
        Location loc = owner.getLocation().clone();
        ArmorStand stand = owner.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.customName(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', "&7" + owner.getName())));
            as.setCustomNameVisible(true);

            // Player-skin head so it reads as a player from a distance.
            ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(owner);
                head.setItemMeta(sm);
            }
            as.getEquipment().setHelmet(head);
            // Copy the deployer's visible armor (often team-coloured leather).
            as.getEquipment().setChestplate(owner.getInventory().getChestplate());
            as.getEquipment().setLeggings(owner.getInventory().getLeggings());
            as.getEquipment().setBoots(owner.getInventory().getBoots());

            as.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(plugin, DECOY_KEY), PersistentDataType.BYTE, (byte) 1);
        });

        decoys.add(stand.getUniqueId());

        Sfx.play(plugin, loc, "decoy.deploy", Sound.ENTITY_ALLAY_ITEM_GIVEN, 1f, 0.8f);
        loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 1, 0), 25, 0.3, 0.8, 0.3, 0.02);

        int lifetimeSec = plugin.getConfig().getInt("powerups.hologram_decoy.lifetime-seconds", 10);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> remove(stand), lifetimeSec * 20L);
    }

    public boolean isDecoy(Entity e) {
        return e != null && decoys.contains(e.getUniqueId());
    }

    /** Pops a decoy that was shot — particles, sound, removal. */
    public void popDecoy(Entity decoy, Player shooter) {
        if (decoy == null) return;
        Location loc = decoy.getLocation();
        loc.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        Sfx.play(plugin, loc, "decoy.pop", Sound.ENTITY_ALLAY_DEATH, 1f, 1.2f);
        if (shooter != null) shooter.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.GRAY + "Het was een decoy!"));
        remove(decoy);
    }

    private void remove(Entity decoy) {
        if (decoy == null) return;
        decoys.remove(decoy.getUniqueId());
        if (!decoy.isDead()) decoy.remove();
    }
}
