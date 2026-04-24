package nl.kmc.adventure.listeners;

import nl.kmc.adventure.AdventureEscapePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * If a player quits mid-race, they're just removed from the active set.
 * Their DNF status is handled by RaceManager at race end.
 */
public class PlayerJoinQuitListener implements Listener {

    private final AdventureEscapePlugin plugin;

    public PlayerJoinQuitListener(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Nothing specific needed — racer data stays; they'll be DNF'd at end
        // If you want to auto-remove them from race on quit, add logic here.
    }
}
