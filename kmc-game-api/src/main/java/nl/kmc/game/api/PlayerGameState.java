package nl.kmc.game.api;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.*;

/**
 * Snapshot of a player's mid-game state for reconnect recovery.
 * Captured when a player disconnects; restored when they rejoin within the timeout window.
 */
public final class PlayerGameState {

    public UUID   playerUuid;
    public String playerName;
    public String gameId;
    public String teamId;

    // Bukkit state
    public ItemStack[] inventory;
    public ItemStack[] armor;
    public double      health;
    public double      maxHealth;
    public int         foodLevel;
    public float       saturation;
    public Location    location;
    public Collection<PotionEffect> effects;

    // Game-specific stats snapshot
    public int     kills;
    public int     deaths;
    public int     assists;
    public int     pointsEarned;

    // Extension map for game-specific data (e.g. score in The Bridge, islands in SkyWars)
    public final Map<String, Object> extra = new HashMap<>();

    public long capturedAt = System.currentTimeMillis();

    public PlayerGameState() {}

    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - capturedAt > timeoutMillis;
    }
}
