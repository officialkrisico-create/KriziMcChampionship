package nl.kmc.adventure.listeners;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.managers.CheckpointManager;
import nl.kmc.adventure.managers.RaceManager;
import nl.kmc.adventure.models.Checkpoint;
import nl.kmc.adventure.models.OutOfBoundsBox;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Tick-based out-of-bounds detector.
 *
 * <p>While a race is ACTIVE, every 5 ticks we scan every racer's location
 * against every OOB box on every checkpoint. If a player is inside an OOB
 * box, they get teleported back to that checkpoint's respawn (or the
 * arena spawn if they've not reached any CP yet).
 *
 * <p>5-tick interval (0.25s) is fast enough that players don't sink very
 * far before being yanked back, and cheap enough at <100 players to be
 * negligible overhead.
 */
public class OutOfBoundsListener {

    private final AdventureEscapePlugin plugin;
    private BukkitTask task;

    public OutOfBoundsListener(AdventureEscapePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        RaceManager rm = plugin.getRaceManager();
        if (rm == null || rm.getState() != RaceManager.State.ACTIVE) return;

        CheckpointManager cm = plugin.getCheckpointManager();
        if (cm == null) return;

        for (UUID uuid : rm.getActiveRacerUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            if (rm.hasFinished(uuid)) continue;  // don't yank finished players

            Location loc = p.getLocation();

            // Walk all checkpoints' OOB boxes
            for (Checkpoint cp : cm.getAll()) {
                for (OutOfBoundsBox box : cp.getOobBoxes()) {
                    if (box.contains(loc)) {
                        sendBack(p, cp, box.getName());
                        return;  // one yank per tick per player is enough
                    }
                }
            }
        }
    }

    private void sendBack(Player p, Checkpoint cp, String boxName) {
        // Determine respawn target: prefer player's last reached CP if they
        // have progressed beyond `cp`, else use `cp` itself.
        Location target = plugin.getCheckpointManager().getRespawnFor(p.getUniqueId());
        if (target == null) target = cp.getRespawn();

        // Center on block + safe offset
        target = target.clone().add(0.5, 0.1, 0.5);
        p.teleport(target);
        p.setVelocity(p.getVelocity().setX(0).setY(0).setZ(0));
        p.setFallDistance(0f);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.9f);
        p.sendMessage(ChatColor.RED + "Out of bounds! "
                + ChatColor.GRAY + "(Teruggezet bij " + cp.getName() + ")");
    }
}
