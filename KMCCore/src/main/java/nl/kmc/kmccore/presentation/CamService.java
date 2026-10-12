package nl.kmc.kmccore.presentation;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Short automatic camera moments built on the spectator target:
 *
 * <ul>
 *   <li><b>Kill-cam</b>: a player who was just knocked out of a game watches their killer for a few seconds
 *       (instead of staring at the spot where they died).</li>
 *   <li><b>Finish-cam</b>: when somebody wins a race, everybody who is already spectating watches that
 *       finish for a moment.</li>
 * </ul>
 * Both are safe to ignore: they only act on players that are already spectators, never fight a running
 * cinematic, and hand the camera back on their own ({@code cams.*} in config.yml).
 */
public final class CamService {

    private final KMCCore plugin;

    public CamService(KMCCore plugin) { this.plugin = plugin; }

    private boolean busy(Player p) {
        var cm = plugin.getCinematicManager();
        return cm != null && cm.isInCinematic(p.getUniqueId());
    }

    // ── Kill-cam ──────────────────────────────────────────────────────────────

    public void killCam(UUID victimId, UUID killerId, String gameName) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("cams.kill-cam.enabled", true) || killerId == null || victimId.equals(killerId)) return;
        int seconds = Math.max(2, cfg.getInt("cams.kill-cam.seconds", 4));
        // Wait a moment: the game switches the victim to spectator and may move them first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player victim = Bukkit.getPlayer(victimId), killer = Bukkit.getPlayer(killerId);
            if (victim == null || killer == null || !victim.isOnline() || !killer.isOnline()) return;
            if (victim.getGameMode() != GameMode.SPECTATOR || busy(victim)) return;
            if (!victim.getWorld().equals(killer.getWorld()) || killer.getGameMode() == GameMode.SPECTATOR) return;
            try {
                victim.setSpectatorTarget(killer);
                victim.sendActionBar(net.kyori.adventure.text.Component.text(
                        plugin.getLanguageManager().tr(victim, "cams.kill", killer.getName())));
            } catch (Throwable t) { return; }
            Bukkit.getScheduler().runTaskLater(plugin, () -> release(victimId, killerId), seconds * 20L);
        }, 12L);
    }

    // ── Finish-cam ────────────────────────────────────────────────────────────

    /** Called when a racer finishes; only the winner (placement 1) triggers the cam. */
    public void finishCam(UUID finisherId, int placement, String gameName) {
        var cfg = plugin.getConfig();
        if (placement != 1 || !cfg.getBoolean("cams.finish-cam.enabled", true)) return;
        Player finisher = Bukkit.getPlayer(finisherId);
        if (finisher == null) return;
        int seconds = Math.max(2, cfg.getInt("cams.finish-cam.seconds", 4));

        List<UUID> viewers = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(finisher)) continue;
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    plugin.getLanguageManager().tr(p, "cams.finish", finisher.getName())));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
            if (p.getGameMode() == GameMode.SPECTATOR && !busy(p)) viewers.add(p.getUniqueId());
        }
        // The finisher is put in spectator by the game a few ticks later — follow them after that.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID v : viewers) {
                Player vp = Bukkit.getPlayer(v);
                if (vp == null || vp.getGameMode() != GameMode.SPECTATOR || !finisher.isOnline()) continue;
                if (!vp.getWorld().equals(finisher.getWorld())) continue;
                try { vp.setSpectatorTarget(finisher); } catch (Throwable ignored) { }
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> viewers.forEach(v -> release(v, finisherId)), seconds * 20L);
        }, 10L);
    }

    /** Gives the camera back — only if the viewer is still following the same player. */
    private void release(UUID viewerId, UUID targetId) {
        Player v = Bukkit.getPlayer(viewerId);
        if (v == null || !v.isOnline()) return;
        try {
            var cur = v.getSpectatorTarget();
            if (cur != null && cur.getUniqueId().equals(targetId)) v.setSpectatorTarget(null);
        } catch (Throwable ignored) { }
    }
}
