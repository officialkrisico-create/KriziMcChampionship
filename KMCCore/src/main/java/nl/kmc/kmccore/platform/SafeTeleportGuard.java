package nl.kmc.kmccore.platform;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Safety net under every plugin teleport (there are dozens of raw {@code player.teleport(...)} calls across
 * the games): a destination that would put a player INSIDE solid blocks is nudged up to the first free spot,
 * and a destination with no ground at all is reported. Every correction is recorded with the class that asked
 * for it, so {@code /kmcaudit} shows which game/map has a bad spawn.
 *
 * <p>Spectators, creative players, cinematics, ender pearls, chorus fruit and portals are left alone.
 */
public final class SafeTeleportGuard implements Listener {

    public record Finding(long timeMs, String player, String caller, String world, int x, int y, int z, String kind) {}

    private static final int MAX_FINDINGS = 40;

    private final KMCCore plugin;
    private final Deque<Finding> findings = new ArrayDeque<>();
    private int corrected;
    private int voidWarnings;
    private int checked;

    public SafeTeleportGuard(KMCCore plugin) { this.plugin = plugin; }

    public int corrected() { return corrected; }
    public int voidWarnings() { return voidWarnings; }
    public int checked() { return checked; }
    public synchronized List<Finding> findings() { return new ArrayList<>(findings); }
    public synchronized void clear() { findings.clear(); corrected = 0; voidWarnings = 0; checked = 0; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!plugin.getConfig().getBoolean("safe-teleport.enabled", true)) return;
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.PLUGIN && c != PlayerTeleportEvent.TeleportCause.COMMAND
                && c != PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) return;
        var cm = plugin.getCinematicManager();
        if (cm != null && cm.isInCinematic(p.getUniqueId())) return;

        Location to = e.getTo();
        World w = to.getWorld();
        if (w == null || !w.isChunkLoaded(to.getBlockX() >> 4, to.getBlockZ() >> 4)) return;
        checked++;

        if (blocked(w, to)) {
            Location fixed = firstFree(w, to, plugin.getConfig().getInt("safe-teleport.max-lift", 12));
            if (fixed != null) {
                e.setTo(fixed);
                corrected++;
                record(p, to, "inside-blocks");
                return;
            }
            record(p, to, "inside-blocks-unfixable");
            return;
        }
        // Standing in air with nothing below for a long way: report only (a sky platform spawn is legal).
        if (airBelow(w, to, plugin.getConfig().getInt("safe-teleport.void-check-depth", 40))) {
            voidWarnings++;
            record(p, to, "no-ground-below");
        }
    }

    private static boolean blocked(World w, Location l) {
        Block feet = w.getBlockAt(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        Block head = feet.getRelative(0, 1, 0);
        return !feet.isPassable() || !head.isPassable();
    }

    private static Location firstFree(World w, Location l, int maxLift) {
        for (int dy = 1; dy <= maxLift; dy++) {
            int y = l.getBlockY() + dy;
            if (y + 1 >= w.getMaxHeight()) break;
            Block feet = w.getBlockAt(l.getBlockX(), y, l.getBlockZ());
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                    && !feet.getRelative(0, -1, 0).isPassable()) {
                Location out = l.clone();
                out.setY(y);
                return out;
            }
        }
        return null;
    }

    private static boolean airBelow(World w, Location l, int depth) {
        int y0 = l.getBlockY();
        for (int d = 0; d <= depth; d++) {
            int y = y0 - d;
            if (y <= w.getMinHeight()) return true;
            if (!w.getBlockAt(l.getBlockX(), y, l.getBlockZ()).isPassable()) return false;
            if (w.getBlockAt(l.getBlockX(), y, l.getBlockZ()).isLiquid()) return false;
        }
        return true;
    }

    private synchronized void record(Player p, Location to, String kind) {
        String caller = "?";
        for (StackTraceElement el : new Throwable().getStackTrace()) {
            String cn = el.getClassName();
            if (cn.startsWith("nl.kmc.") && !cn.equals(SafeTeleportGuard.class.getName())) {
                caller = cn.substring(cn.lastIndexOf('.') + 1) + ":" + el.getLineNumber();
                break;
            }
        }
        findings.addFirst(new Finding(System.currentTimeMillis(), p.getName(), caller,
                to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ(), kind));
        while (findings.size() > MAX_FINDINGS) findings.removeLast();
        plugin.getLogger().warning(String.format(Locale.ROOT, "[SafeTeleport] %s -> %s %d,%d,%d (%s) via %s",
                p.getName(), to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ(), kind, caller));
    }
}
