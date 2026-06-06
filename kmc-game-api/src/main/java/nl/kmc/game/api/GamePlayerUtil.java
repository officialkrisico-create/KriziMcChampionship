package nl.kmc.game.api;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Shared player-state helpers for V2 game managers.
 *
 * <p>Every game manager performs the same "clean slate" reset before a game
 * starts (clear inventory, set GameMode, reset health/food, remove effects).
 * Centralising that logic here ensures all games behave consistently and makes
 * future changes (e.g. adding a saturation reset) apply everywhere at once.
 */
public final class GamePlayerUtil {

    private GamePlayerUtil() {}

    /**
     * Resets a player to a clean in-game state: ADVENTURE mode, full health
     * and food, cleared inventory, all potion effects removed, fall distance
     * zeroed.  Does NOT teleport the player — callers handle positioning.
     *
     * @param player the player to reset (must not be null)
     */
    public static void resetPlayer(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        // getMaxHealth() is deprecated but version-stable — it avoids referencing the
        // Attribute enum constant (renamed MAX_HEALTH ↔ GENERIC_MAX_HEALTH between
        // 1.21 builds), which would throw NoSuchFieldError on a mismatched server.
        double max;
        try { max = player.getMaxHealth(); } catch (Throwable t) { max = 20.0; }
        player.setHealth(max);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFallDistance(0f);
    }

    /**
     * Teleports a player to {@code target}, but falls back to their current
     * location when {@code target} is null or has no world. This prevents the
     * {@code IllegalArgumentException: location} crash that occurs when an arena
     * spawn hasn't been configured — the game starts where the players are
     * instead of crashing during {@code onPrepare}.
     *
     * @return true if teleported to the intended target, false if it fell back
     */
    public static boolean safeTeleport(Player player, org.bukkit.Location target) {
        if (target != null && target.getWorld() != null) {
            player.teleport(target);
            return true;
        }
        player.teleport(player.getLocation()); // no-op fallback — never crashes
        return false;
    }

    /**
     * Applies a Slowness 255 (movement-freeze) potion for the given number of
     * ticks.  Used during the countdown / prepare phase to hold players in place.
     *
     * @param player         player to freeze
     * @param durationTicks  how long the effect should last
     */
    public static void freezePlayer(Player player, int durationTicks) {
        PotionEffectType slow = slowness();
        if (slow != null)
            player.addPotionEffect(new PotionEffect(slow, durationTicks, 255, true, false, false));
    }

    /**
     * Removes the Slowness freeze applied by {@link #freezePlayer}.
     *
     * @param player player to unfreeze
     */
    public static void unfreezePlayer(Player player) {
        PotionEffectType slow = slowness();
        if (slow != null) player.removePotionEffect(slow);
    }

    /**
     * Returns the {@link PotionEffectType} for Slowness via Paper's registry,
     * or {@code null} if unavailable (should never happen on 1.21+).
     */
    public static PotionEffectType slowness() {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft("slowness"));
        } catch (Exception e) { return null; }
    }

    /**
     * Returns the {@link PotionEffectType} for Glowing via Paper's registry,
     * or {@code null} if unavailable.
     */
    public static PotionEffectType glowing() {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft("glowing"));
        } catch (Exception e) { return null; }
    }
}
