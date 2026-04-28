package nl.kmc.kmccore.leaderboard;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Dynamic leaderboard that's always visible as a boss bar.
 *
 * <p>Shows the top 3 teams + their points in a compact format above
 * the player's hotbar. Updates every 2 seconds.
 *
 * <p>Auto-hidden during games where the active scoreboard owner has
 * acquired the scoreboard lock (via {@link nl.kmc.kmccore.api.KMCApi})
 * — we don't want to overlap. Reactivated between games / in lobby.
 *
 * <p>Color of bar reflects state:
 * <ul>
 *   <li>Green = lobby / between games</li>
 *   <li>Blue = round in progress (less attention-grabbing)</li>
 *   <li>Yellow = final round (heightened attention)</li>
 * </ul>
 */
public class BossBarLeaderboardManager implements Listener {

    private final KMCCore plugin;
    private BossBar bar;
    private BukkitTask updateTask;
    private boolean enabled;
    private boolean suppressed;  // active game has acquired scoreboard lock

    public BossBarLeaderboardManager(KMCCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        if (enabled) return;
        enabled = true;
        bar = Bukkit.createBossBar("Loading...", BarColor.GREEN, BarStyle.SOLID);
        bar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 0L, 40L);
    }

    public void stop() {
        if (!enabled) return;
        enabled = false;
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
    }

    /** Called by GameManager when a game starts — hides the bar so
     *  the game's own scoreboard is the focus. */
    public void suppress() {
        suppressed = true;
        if (bar != null) bar.setVisible(false);
    }

    /** Called when a game ends — bar comes back. */
    public void unsuppress() {
        suppressed = false;
        if (bar != null) bar.setVisible(true);
    }

    public boolean isEnabled() { return enabled; }

    // ----------------------------------------------------------------

    private void refresh() {
        if (!enabled || bar == null) return;
        if (suppressed) return;

        List<KMCTeam> top = plugin.getTeamManager().getTeamsSortedByPoints();
        StringBuilder sb = new StringBuilder();

        // Build "🥇 Red 1200  🥈 Blue 950  🥉 Green 720"
        String[] medals = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            if (i > 0) sb.append("  ");
            KMCTeam t = top.get(i);
            sb.append(medals[i]).append(" ")
              .append(t.getColor()).append(t.getDisplayName())
              .append(ChatColor.WHITE).append(" ").append(t.getPoints());
        }
        if (top.isEmpty()) sb.append(ChatColor.GRAY + "Waiting for tournament to start...");

        // Color cycle based on round
        int round = plugin.getTournamentManager().getCurrentRound();
        int total = plugin.getConfig().getInt("tournament.total-rounds", 8);
        BarColor color;
        if (!plugin.getTournamentManager().isActive()) {
            color = BarColor.GREEN;
            sb.insert(0, ChatColor.GREEN + "" + ChatColor.BOLD + "LOBBY  " + ChatColor.RESET);
        } else if (round >= total - 1) {
            color = BarColor.YELLOW;
            sb.insert(0, ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "FINAL ROUND  " + ChatColor.RESET);
        } else {
            color = BarColor.BLUE;
            sb.insert(0, ChatColor.AQUA + "Round " + round + "/" + total + "  " + ChatColor.RESET);
        }
        bar.setColor(color);
        bar.setTitle(sb.toString());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bar != null) bar.addPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (bar != null) bar.removePlayer(event.getPlayer());
    }
}
