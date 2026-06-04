package nl.kmc.kmccore.presentation;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of a player's state saved before they enter cinematic mode.
 * Restored when the cinematic ends or is skipped.
 */
public final class CinematicState {

    private final UUID              uuid;
    private final Location          location;
    private final GameMode          gameMode;
    private final ItemStack[]       inventory;
    private final ItemStack[]       armor;
    private final List<PotionEffect> effects;
    private final float             health;
    private final int               foodLevel;
    private final boolean           allowFlight;
    private final boolean           flying;

    private CinematicState(UUID uuid, Location location, GameMode gameMode,
                           ItemStack[] inventory, ItemStack[] armor,
                           Collection<PotionEffect> effects,
                           float health, int foodLevel,
                           boolean allowFlight, boolean flying) {
        this.uuid        = uuid;
        this.location    = location;
        this.gameMode    = gameMode;
        this.inventory   = inventory;
        this.armor       = armor;
        this.effects     = List.copyOf(effects);
        this.health      = health;
        this.foodLevel   = foodLevel;
        this.allowFlight = allowFlight;
        this.flying      = flying;
    }

    /** Captures the current state of the player. */
    public static CinematicState capture(Player player) {
        return new CinematicState(
                player.getUniqueId(),
                player.getLocation().clone(),
                player.getGameMode(),
                player.getInventory().getContents().clone(),
                player.getInventory().getArmorContents().clone(),
                player.getActivePotionEffects(),
                (float) player.getHealth(),
                player.getFoodLevel(),
                player.getAllowFlight(),
                player.isFlying()
        );
    }

    /**
     * Restores this snapshot to the player.
     * Safe to call even if the player has changed world — uses location's world.
     */
    public void restore(Player player) {
        player.setGameMode(gameMode);
        player.teleport(location);
        player.getInventory().setContents(inventory);
        player.getInventory().setArmorContents(armor);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        effects.forEach(player::addPotionEffect);
        try { player.setHealth(Math.min(health, 20)); } catch (Exception ignored) {}
        player.setFoodLevel(foodLevel);
        player.setAllowFlight(allowFlight);
        player.setFlying(flying && allowFlight);
    }

    public UUID     getUuid()     { return uuid; }
    public Location getLocation() { return location; }
    public GameMode getGameMode() { return gameMode; }
}
