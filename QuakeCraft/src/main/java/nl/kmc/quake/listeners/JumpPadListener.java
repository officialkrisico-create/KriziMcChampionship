package nl.kmc.quake.listeners;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Launches players who step onto a registered jump pad.
 *
 * <p>Jump pads are placed with {@code /qc setjumppad} — the admin stands on
 * the block they want to turn into a pad. Any player who then walks onto that
 * block (in the arena world) is launched in the direction they're facing with
 * an upward boost, letting them reach higher parts of the map.
 *
 * <p>The pad is purely location-based, so the block can be any material the
 * map builder likes (slime, a coloured block, a pressure plate, etc.).
 */
public final class JumpPadListener implements Listener {

    private final QuakeCraftPlugin plugin;
    private final Map<UUID, Long> lastLaunch = new HashMap<>();

    public JumpPadListener(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;

        var arena = plugin.getArenaManager();
        if (arena.getJumpPads().isEmpty()) return;

        Player p = event.getPlayer();
        if (arena.getArenaWorld() != null && !p.getWorld().equals(arena.getArenaWorld())) return;

        // Only act when the player crosses into a new block (cheap guard).
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        // The block the player is standing on (floor beneath their feet).
        Location floor = event.getTo().getBlock().getRelative(BlockFace.DOWN).getLocation();
        nl.kmc.quake.models.JumpPad pad = arena.getJumpPadAt(floor);
        if (pad == null) return;

        // Per-player cooldown so it fires once per step-on, not every tick.
        long now  = System.currentTimeMillis();
        long last = lastLaunch.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < 600) return;
        lastLaunch.put(p.getUniqueId(), now);

        launch(p, pad);
    }

    private void launch(Player p, nl.kmc.quake.models.JumpPad pad) {
        double up      = pad.getVerticalVelocity();
        double forward = pad.getForward();

        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() > 0) dir.normalize();
        Vector velocity = dir.multiply(forward).setY(up);

        p.setVelocity(velocity);
        p.setFallDistance(0f);

        Location at = p.getLocation();
        p.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 1f, 1.2f);
        p.getWorld().spawnParticle(Particle.CLOUD, at, 15, 0.3, 0.1, 0.3, 0.05);
    }
}
