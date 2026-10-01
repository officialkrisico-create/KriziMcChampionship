package nl.kmc.kmccore.tournament;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.FanFavoriteVoteGui;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Post-tournament "Fan Favorite" vote: players pick who they most enjoyed
 * watching/playing with this event. The winner is announced and unlocks the
 * {@code fan_favorite} achievement — a Hall-of-Fame-style honour that sits
 * next to the stat-based records, decided by the crowd instead of the numbers.
 */
public final class FanFavoriteManager {

    private final KMCCore plugin;

    private boolean active;
    private final Map<UUID, UUID> votes = new HashMap<>(); // voter -> candidate
    private BukkitTask closeTask;

    public FanFavoriteManager(KMCCore plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() { return active; }

    /** Opens the vote for every online player; tallies automatically after {@code seconds}. */
    public void startVote(int seconds) {
        if (active) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        active = true;
        votes.clear();

        Bukkit.broadcastMessage(MessageUtil.color(
                "&6&l🌟 Stem op je Fan Favorite van dit toernooi! &7(" + seconds + "s)"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            new FanFavoriteVoteGui(plugin, this).open(p);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        }

        closeTask = Bukkit.getScheduler().runTaskLater(plugin, this::endVote, seconds * 20L);
    }

    /** Casts (or changes) one player's vote. Ignored once voting has closed. */
    public void castVote(Player voter, UUID candidate) {
        if (!active) return;
        votes.put(voter.getUniqueId(), candidate);
        voter.sendMessage(MessageUtil.color("&a✔ Stem genoteerd."));
    }

    private void endVote() {
        active = false;
        closeTask = null;

        if (votes.isEmpty()) {
            Bukkit.broadcastMessage(MessageUtil.color("&7Geen stemmen voor Fan Favorite — overgeslagen."));
            return;
        }

        Map<UUID, Integer> tally = new HashMap<>();
        for (UUID candidate : votes.values()) tally.merge(candidate, 1, Integer::sum);

        UUID winner = tally.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        if (winner == null) return;

        int count = tally.get(winner);
        String name = nameOf(winner);

        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
        Bukkit.broadcastMessage(MessageUtil.color("        &6&l🌟 FAN FAVORITE 🌟"));
        Bukkit.broadcastMessage(MessageUtil.color("  &e" + name + " &7— &f" + count + " stem(men)"));
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));

        Player winnerOnline = Bukkit.getPlayer(winner);
        if (winnerOnline != null) {
            winnerOnline.sendTitle(MessageUtil.color("&6&l🌟 FAN FAVORITE"),
                    MessageUtil.color("&7De spelers hebben gekozen!"), 10, 70, 15);
        }
        for (Player p : Bukkit.getOnlinePlayers())
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        if (plugin.getAchievementManager() != null) {
            plugin.getAchievementManager().unlock(winner, "fan_favorite");
        }
    }

    private String nameOf(UUID uuid) {
        var off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }
}
