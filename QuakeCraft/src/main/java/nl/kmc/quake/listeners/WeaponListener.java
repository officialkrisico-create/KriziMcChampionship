package nl.kmc.quake.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.managers.PowerupSpawner;
import nl.kmc.quake.models.ActivePowerup;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.models.PowerupType;
import nl.kmc.quake.util.WeaponFactory;
import nl.kmc.quake.weapons.GrenadeWeapon;
import nl.kmc.quake.weapons.RailgunWeapon;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Handles all in-game player interaction:
 *
 * <ul>
 *   <li>Right-click hoe → fire weapon</li>
 *   <li>Pick up powerup item → equip it</li>
 *   <li>Block dropping weapons / swapping to off-hand / item damage</li>
 * </ul>
 */
public class WeaponListener implements Listener {

    private final QuakeCraftPlugin plugin;

    public WeaponListener(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Right-click handler
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getGameManager().isActive()) return;

        Player p = event.getPlayer();
        PlayerState state = plugin.getGameManager().get(p.getUniqueId());
        if (state == null) return;

        // Only right-clicks
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        String weaponId = WeaponFactory.getWeaponId(plugin, item);
        if (weaponId == null) return;

        event.setCancelled(true);

        switch (weaponId) {
            case "RAILGUN" -> RailgunWeapon.fireBase(plugin, p, state);
            case "SHOTGUN" -> {
                if (RailgunWeapon.fireShotgun(plugin, p, state)) consumeUse(p, state, item);
            }
            case "SNIPER" -> {
                if (RailgunWeapon.fireSniper(plugin, p, state)) consumeUse(p, state, item);
            }
            case "MACHINE_GUN" -> {
                if (RailgunWeapon.fireMachineGun(plugin, p, state)) consumeUse(p, state, item);
            }
            case "GRENADE" -> {
                GrenadeWeapon.throwGrenade(plugin, p);
                // Single-use → remove from inventory
                p.getInventory().remove(item);
                state.clearPowerup();
            }
        }
    }

    /** Decrement powerup uses; if exhausted, remove the item. */
    private void consumeUse(Player p, PlayerState state, ItemStack stack) {
        boolean stillActive = state.consumePowerupUse();
        if (!stillActive) {
            // Replace with base railgun (slot 0 stays as base)
            int slot = p.getInventory().getHeldItemSlot();
            p.getInventory().setItem(slot, null);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.GRAY + "Powerup leeg!"));
            return;
        }
        // Update display lore with new use count
        ActivePowerup ap = state.getActivePowerup();
        if (ap != null) {
            ItemStack updated = WeaponFactory.buildPowerup(plugin, ap.getType(), ap.getRemainingUses());
            int slot = p.getInventory().getHeldItemSlot();
            p.getInventory().setItem(slot, updated);
        }
    }

    // ----------------------------------------------------------------
    // Powerup pickup
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;

        PlayerState state = plugin.getGameManager().get(p.getUniqueId());
        if (state == null) {
            event.setCancelled(true);
            return;
        }

        Item item = event.getItem();
        PowerupType type = PowerupSpawner.getPowerupType(plugin, item);
        if (type == null) {
            // Not a powerup spawner item — block all other pickups
            event.setCancelled(true);
            return;
        }

        // Cancel default pickup; we handle it manually
        event.setCancelled(true);
        plugin.getPowerupSpawner().onPickedUp(item);
        item.remove();

        applyPowerup(p, state, type);
    }

    private void applyPowerup(Player p, PlayerState state, PowerupType type) {
        // Award bonus coins via KMCCore
        int bonus = plugin.getConfig().getInt(
                "powerups." + type.getConfigKey() + ".bonus-coins", 0);
        if (bonus > 0) {
            plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), bonus);
        }

        if (type == PowerupType.SPEED) {
            // Apply Speed II effect
            PotionEffectType speedType = speed();
            if (speedType != null) {
                int seconds = plugin.getConfig().getInt(
                        "powerups.speed.duration-seconds", 15);
                p.addPotionEffect(new PotionEffect(speedType, seconds * 20, 1, true, false, false));
            }
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.GREEN + "✦ Speed II Boost!"));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
            return;
        }

        // Weapon powerup — set active + give item
        int uses = plugin.getConfig().getInt("powerups." + type.getConfigKey() + ".uses", 1);
        state.setActivePowerup(new ActivePowerup(type, uses));

        ItemStack weapon = WeaponFactory.buildPowerup(plugin, type, uses);
        // Place in slot 1 (slot 0 is reserved for the base railgun)
        if (p.getInventory().getItem(1) != null
                && WeaponFactory.getWeaponId(plugin, p.getInventory().getItem(1)) != null) {
            // Already have a powerup — replace it
            p.getInventory().setItem(1, weapon);
        } else {
            p.getInventory().setItem(1, weapon);
        }
        p.getInventory().setHeldItemSlot(1);

        p.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.YELLOW + "✦ " + type.name() + " (" + uses + " uses)"));
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.0f);
    }

    private PotionEffectType speed() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("speed")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // Protection — block dropping, off-hand swap, item damage
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        // Block dropping weapons during the game
        if (WeaponFactory.isQuakeWeapon(plugin, event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true); // No off-hand cheese
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;

        // Block fall damage, drowning, etc. — only railgun hits should count
        // (railgun "hits" don't go through EntityDamageEvent — they go through
        // GameManager.handleHit() directly. So all damage here is environmental.)
        if (plugin.getGameManager().get(p.getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }
}
