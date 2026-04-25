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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Handles all in-game player interaction.
 *
 * <p>FIXES:
 * <ul>
 *   <li>{@code onInteract} no longer uses {@code ignoreCancelled=true}.
 *       Other listeners (e.g. block adventure-mode protections) sometimes
 *       pre-cancel right-clicks on grass etc., which prevented our
 *       fire handler from running.</li>
 *   <li>{@code onDamage} now lets scripted kill damage through (the
 *       setHealth(0) call from GameManager). It only blocks environmental
 *       damage (fall, drown, etc.) so players don't accidentally die.</li>
 *   <li>NEW {@code onPlayerDamagePlayer} at HIGHEST priority — un-cancels
 *       PvP that KMCCore's GlobalPvPListener cancelled at NORMAL priority.
 *       Note: railgun hits don't actually go through this event, but
 *       this keeps the world consistent for any indirect PvP.</li>
 * </ul>
 */
public class WeaponListener implements Listener {

    private final QuakeCraftPlugin plugin;

    public WeaponListener(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Right-click → fire weapon
    // ----------------------------------------------------------------

    @EventHandler   // ← removed ignoreCancelled=true so we always run
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getGameManager().isActive()) return;

        Player p = event.getPlayer();
        PlayerState state = plugin.getGameManager().get(p.getUniqueId());
        if (state == null) return;

        // Only right-clicks (in air or on block)
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        String weaponId = WeaponFactory.getWeaponId(plugin, item);
        if (weaponId == null) return;

        // Cancel default behavior (block placement, food eating, etc.)
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
                p.getInventory().remove(item);
                state.clearPowerup();
            }
        }
    }

    private void consumeUse(Player p, PlayerState state, ItemStack stack) {
        boolean stillActive = state.consumePowerupUse();
        if (!stillActive) {
            int slot = p.getInventory().getHeldItemSlot();
            p.getInventory().setItem(slot, null);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.GRAY + "Powerup leeg!"));
            return;
        }
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
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        plugin.getPowerupSpawner().onPickedUp(item);
        item.remove();

        applyPowerup(p, state, type);
    }

    private void applyPowerup(Player p, PlayerState state, PowerupType type) {
        int bonus = plugin.getConfig().getInt(
                "powerups." + type.getConfigKey() + ".bonus-coins", 0);
        if (bonus > 0) {
            plugin.getKmcCore().getApi().givePoints(p.getUniqueId(), bonus);
        }

        if (type == PowerupType.SPEED) {
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

        int uses = plugin.getConfig().getInt("powerups." + type.getConfigKey() + ".uses", 1);
        state.setActivePowerup(new ActivePowerup(type, uses));

        ItemStack weapon = WeaponFactory.buildPowerup(plugin, type, uses);
        p.getInventory().setItem(1, weapon);
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
    // Protection
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        if (WeaponFactory.isQuakeWeapon(plugin, event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    /**
     * Blocks PURELY environmental damage — fall, lava, drown, suffocation,
     * etc. — to participants. PvP damage IS allowed (you specifically want
     * it enabled in QuakeCraft). The railgun kills via {@code setHealth(0)}
     * which doesn't fire this event anyway.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;

        // Allow player-vs-player damage (PvP is enabled in QuakeCraft)
        if (event instanceof EntityDamageByEntityEvent ebe
                && ebe.getDamager() instanceof Player) {
            return;
        }

        // Block environmental damage so HP stays full from fall/lava/etc.
        event.setCancelled(true);
    }

    /**
     * Un-cancels player-vs-player damage at HIGHEST priority, overriding
     * KMCCore's GlobalPvPListener which cancels at NORMAL.
     *
     * <p>Without this, melee/projectile attacks between players would be
     * silently blocked. Railgun hits don't actually go through damage
     * events (they call GameManager.handleHit directly), but standard
     * PvP from punching, bows, etc., needs this override.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity()  instanceof Player target)) return;
        if (plugin.getGameManager().get(target.getUniqueId()) == null) return;

        // Un-cancel — GlobalPvPListener cancelled at NORMAL, we override
        event.setCancelled(false);
    }
}
