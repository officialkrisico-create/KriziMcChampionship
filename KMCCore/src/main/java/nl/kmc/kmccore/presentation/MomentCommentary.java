package nl.kmc.kmccore.presentation;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.core.event.GameObjectiveEvent;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Turns the big moments games already report (clutch detections and a few
 * rare game objectives) into a short server-wide announcement: a title plus a
 * chat line, in each viewer's own language. Throttled so a busy match doesn't
 * drown in commentary.
 *
 * <p>Texts live in {@code lang/<code>.yml} under {@code commentary.<type>} with
 * a {@code title} and {@code line1..line2} variants ({0} = the player's name).
 */
public final class MomentCommentary implements Listener {

    private static final int VARIANTS = 2;

    private final KMCCore plugin;
    private final Random rng = new Random();
    private final Map<String, Long> lastByPlayerAndType = new HashMap<>();
    private long lastAnnouncementMs;

    public MomentCommentary(KMCCore plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClutch(ClutchMomentEvent event) {
        announce(event.getPlayer(), event.getType().name().toLowerCase(), event.getGameId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onObjective(GameObjectiveEvent event) {
        switch (event.getType()) {
            case BOSS_KILLED, KILL_MILESTONE_LEGENDARY, LAST_STANDING ->
                    announce(event.getPlayer(), event.getType().name().toLowerCase(), event.getGameId());
            default -> { }
        }
    }

    private void announce(Player subject, String typeKey, String gameId) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("commentary.enabled", true)) return;

        long now = System.currentTimeMillis();
        if (now - lastAnnouncementMs < cfg.getLong("commentary.global-cooldown-seconds", 6) * 1000L) return;

        String cooldownKey = subject.getUniqueId() + ":" + typeKey;
        Long last = lastByPlayerAndType.get(cooldownKey);
        if (last != null && now - last < cfg.getLong("commentary.per-player-cooldown-seconds", 45) * 1000L) return;

        lastAnnouncementMs = now;
        lastByPlayerAndType.put(cooldownKey, now);

        // Some games already broadcast their own chat line for the same moment.
        var skipChat = cfg.isSet("commentary.skip-chat-games")
                ? cfg.getStringList("commentary.skip-chat-games") : java.util.List.of("tnt_tag");
        boolean chat = !skipChat.contains(gameId);
        int variant = 1 + rng.nextInt(VARIANTS);
        var lang = plugin.getLanguageManager();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String title = lang.tr(viewer, "commentary." + typeKey + ".title");
            String line  = lang.tr(viewer, "commentary." + typeKey + ".line" + variant, subject.getName());
            viewer.sendTitle(title, line, 5, 45, 10);
            if (chat) viewer.sendMessage(title + " §8» " + line);
            viewer.playSound(viewer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
    }
}
