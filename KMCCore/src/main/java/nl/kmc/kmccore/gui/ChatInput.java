package nl.kmc.kmccore.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reusable "type your answer in chat" flow for GUIs. A menu calls
 * {@link #await} (which closes the inventory and prompts the player); the next
 * chat line is captured (not broadcast) and handed to the callback on the main
 * thread — typically to set a value and reopen the menu.
 */
public final class ChatInput implements Listener {

    private final KMCCore plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatInput(KMCCore plugin) { this.plugin = plugin; }

    /** Prompts the player and captures their next chat message. */
    public void await(Player player, String prompt, Consumer<String> callback) {
        pending.put(player.getUniqueId(), callback);
        player.closeInventory();
        player.sendMessage("§e§l» " + prompt);
        player.sendMessage("§7Typ je antwoord in de chat — of '§ccancel§7' om te annuleren.");
    }

    public boolean isAwaiting(UUID uuid) { return pending.containsKey(uuid); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Consumer<String> cb = pending.remove(event.getPlayer().getUniqueId());
        if (cb == null) return;
        event.setCancelled(true); // don't broadcast the answer

        String msg = PlainTextComponentSerializer.plainText().serialize(event.message());
        Player p = event.getPlayer();
        if (msg.equalsIgnoreCase("cancel")) {
            p.sendMessage("§7Geannuleerd.");
            return;
        }
        // Hop back to the main thread for safe API use.
        Bukkit.getScheduler().runTask(plugin, () -> cb.accept(msg));
    }
}
