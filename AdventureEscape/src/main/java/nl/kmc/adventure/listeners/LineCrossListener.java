package nl.kmc.adventure.listeners;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.Checkpoint;
import nl.kmc.adventure.models.RacerData;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects when a player enters the start, finish, or any checkpoint region.
 *
 * <p>Cooldowns prevent double-triggering when a player stands in a region
 * for multiple ticks.
 *
 * <p>Checkpoint logic: passing checkpoints in order is required before
 * the finish line counts as a lap completion. If a player skips a
 * checkpoint or tries to finish without all of them, the cross is
 * silently ignored and they get an action bar warning.
 */
public class LineCrossListener implements Listener {

    private final AdventureEscapePlugin plugin;

    private final Map<UUID, Long> finishCooldown     = new HashMap<>();
    private final Map<UUID, Long> startCooldown      = new HashMap<>();
    private final Map<UUID, Long> checkpointCooldown = new HashMap<>();

    public LineCrossListener(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getRaceManager().isActive()) return;
        if (event.getTo() == null) return;

        // Only trigger on block-position changes — same tick rapid-fire is wasted work
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        // ---- Checkpoint check FIRST ----
        Checkpoint cp = plugin.getArenaManager().getCheckpointAt(event.getTo());
        if (cp != null) {
            Long cExpire = checkpointCooldown.get(uuid);
            if (cExpire == null || now > cExpire) {
                checkpointCooldown.put(uuid, now + 1500); // 1.5s cooldown
                handleCheckpoint(player, cp);
            }
            return;
        }

        // ---- Finish line ----
        if (plugin.getArenaManager().isInFinishline(event.getTo())) {
            Long expire = finishCooldown.get(uuid);
            if (expire == null || now > expire) {
                finishCooldown.put(uuid, now + 2000);
                handleFinish(player);
            }
            return;
        }

        // ---- Start line ----
        if (plugin.getArenaManager().isInStartline(event.getTo())) {
            Long expire = startCooldown.get(uuid);
            if (expire == null || now > expire) {
                startCooldown.put(uuid, now + 2000);
                plugin.getRaceManager().onPlayerCrossStart(player);
            }
        }
    }

    // ----------------------------------------------------------------

    private void handleCheckpoint(Player player, Checkpoint cp) {
        RacerData rd = plugin.getRaceManager().getRacers().get(player.getUniqueId());
        if (rd == null) return;

        var result = rd.passCheckpoint(cp.getIndex());
        int total = plugin.getArenaManager().getCheckpointCount();

        switch (result) {
            case OK -> {
                player.sendActionBar(net.kyori.adventure.text.Component.text(
                        ChatColor.AQUA + "✔ Checkpoint " + cp.getIndex() + "/" + total));
                player.playSound(player.getLocation(),
                        Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            }
            case ALREADY_PASSED -> {
                // Quietly do nothing — they're just standing in the region
            }
            case OUT_OF_ORDER -> {
                player.sendActionBar(net.kyori.adventure.text.Component.text(
                        ChatColor.RED + "✘ Sla geen checkpoints over! "
                        + "Je moet eerst checkpoint " + (rd.getLastCheckpointIndex() + 1) + " halen."));
                player.playSound(player.getLocation(),
                        Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            }
        }
    }

    private void handleFinish(Player player) {
        RacerData rd = plugin.getRaceManager().getRacers().get(player.getUniqueId());
        if (rd == null) return;

        int total = plugin.getArenaManager().getCheckpointCount();
        if (total > 0 && !rd.hasAllCheckpoints(total)) {
            // Refuse the finish — player is shortcutting
            int missing = total - rd.getLastCheckpointIndex();
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.RED + "✘ Je mist nog " + missing + " checkpoint(s)!"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.6f);
            return;
        }

        plugin.getRaceManager().onPlayerCrossFinish(player);
    }
}
