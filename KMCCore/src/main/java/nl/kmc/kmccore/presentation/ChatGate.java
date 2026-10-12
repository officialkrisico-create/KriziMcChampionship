package nl.kmc.kmccore.presentation;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Switches player chat off while something is being explained or celebrated (opening ceremony, a game's
 * intro + tutorial, the closing sequence) so nobody talks over it — <b>also for operators</b>. Only
 * players holding the explicit {@code kmc.chat.bypass} permission (default: nobody, not even OP) can still
 * talk, e.g. the host or a streamer. Commands keep working.
 *
 * <p>Several reasons can hold the gate at once; chat is open again when the last one is released.
 */
public final class ChatGate {

    private final KMCCore plugin;
    private final Map<String, Long> reasons = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public ChatGate(KMCCore plugin) { this.plugin = plugin; }

    /** True when this source may mute at all ({@code chat-gate.enabled} and {@code chat-gate.<source>}). */
    private boolean allowed(String reason) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("chat-gate.enabled", true)) return false;
        String source = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
        return cfg.getBoolean("chat-gate." + source, true);
    }

    /** Holds chat closed for {@code reason} (e.g. {@code "flow:quake_craft"}). */
    public void mute(String reason) {
        if (reason == null || !allowed(reason)) return;
        reasons.put(reason, System.currentTimeMillis());
    }

    public void unmute(String reason) {
        if (reason != null) reasons.remove(reason);
    }

    /** Opens chat again regardless of who held it (stop / crash safety). */
    public void clear() { reasons.clear(); }

    public boolean isMuted() { return !reasons.isEmpty(); }

    /** Whether {@code p} may not talk right now. */
    public boolean blocks(Player p) {
        return isMuted() && !p.hasPermission("kmc.chat.bypass");
    }

    /** At most one reminder every 4 s, as an action bar so it never adds to the chat. */
    public void notifyBlocked(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(p.getUniqueId());
        if (last != null && now - last < 4000) return;
        lastNotice.put(p.getUniqueId(), now);
        String msg = plugin.getLanguageManager().tr(p, "chatgate.blocked");
        plugin.getServer().getScheduler().runTask(plugin, () ->
                p.sendActionBar(net.kyori.adventure.text.Component.text(msg)));
    }
}
