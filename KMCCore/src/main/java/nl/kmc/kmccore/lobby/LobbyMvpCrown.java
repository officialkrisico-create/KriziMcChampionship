package nl.kmc.kmccore.lobby;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * A small rotating gold-dust crown above the tournament leader — shown only while the leader is
 * standing in the lobby and no game is running (never during games).
 */
public final class LobbyMvpCrown {

    private final KMCCore plugin;
    private BukkitTask task;
    private double angle;

    public LobbyMvpCrown(KMCCore plugin) { this.plugin = plugin; }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("lobby.mvp-crown", true)) return;
        if (nl.kmc.core.RunningGames.anyRunning() || plugin.getGameManager().isGameActive()) return;

        Location lobby = plugin.getArenaManager().getLobby();
        if (lobby == null || lobby.getWorld() == null) return;

        List<PlayerData> board = plugin.getPlayerDataManager().getLeaderboard();
        if (board.isEmpty() || board.get(0).getPoints() <= 0) return;
        Player leader = Bukkit.getPlayer(board.get(0).getUuid());
        if (leader == null || !leader.isOnline() || !leader.getWorld().equals(lobby.getWorld())) return;

        double radius = plugin.getConfig().getDouble("arena.lobby-protection-radius", 60);
        if (leader.getLocation().distanceSquared(lobby) > radius * radius) return;

        angle += 0.35;
        Location head = leader.getLocation().add(0, 2.5, 0);
        int points = 6;
        for (int i = 0; i < points; i++) {
            double a = angle + (2 * Math.PI * i / points);
            leader.getWorld().spawnParticle(Particle.DUST, head.clone().add(Math.cos(a) * 0.4, 0, Math.sin(a) * 0.4),
                    1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 215, 0), 1.1f));
        }
    }
}
