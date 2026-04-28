package nl.kmc.quake.bonuses;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.kmccore.api.KMCApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Custom kill bonus tracker for QuakeCraft.
 *
 * <p>Awards extra points + announces special kill events:
 *
 * <table>
 *   <tr><th>Bonus</th><th>Trigger</th><th>Default points</th></tr>
 *   <tr><td>FIRST_BLOOD</td><td>First kill of the match</td><td>+30</td></tr>
 *   <tr><td>REVENGE</td><td>You kill the player who last killed you</td><td>+15</td></tr>
 *   <tr><td>DOUBLE_KILL</td><td>Two kills within 5 seconds</td><td>+20</td></tr>
 *   <tr><td>TRIPLE_KILL</td><td>Three kills within 5 seconds</td><td>+40</td></tr>
 *   <tr><td>RAMPAGE</td><td>5 kills in a row without dying</td><td>+50</td></tr>
 *   <tr><td>UNSTOPPABLE</td><td>10 kills in a row without dying</td><td>+100</td></tr>
 *   <tr><td>CLUTCH</td><td>Won a round at &lt;= 2 HP</td><td>+25</td></tr>
 *   <tr><td>FLAWLESS</td><td>Won a round with 0 deaths</td><td>+50</td></tr>
 *   <tr><td>HEADSHOT</td><td>Direct headshot (Y delta &gt; 0.7)</td><td>+10</td></tr>
 * </table>
 *
 * <p>All values are configurable via QuakeCraft config.yml under
 * {@code bonuses:}. Disable any by setting points to 0.
 *
 * <p>Game events to call:
 * <ul>
 *   <li>{@link #onMatchStart()} — reset state</li>
 *   <li>{@link #onKill(Player, Player, double)} — track kill, return earned bonuses</li>
 *   <li>{@link #onDeath(Player)} — reset their streak</li>
 *   <li>{@link #onMatchEnd(Player)} — check flawless/clutch</li>
 * </ul>
 */
public class KillBonusManager {

    public enum BonusType {
        FIRST_BLOOD("first-blood", "&c&l⚔ FIRST BLOOD!"),
        REVENGE("revenge", "&5&l☠ REVENGE!"),
        DOUBLE_KILL("double-kill", "&c&l⚡ DOUBLE KILL"),
        TRIPLE_KILL("triple-kill", "&c&l⚡⚡⚡ TRIPLE KILL"),
        RAMPAGE("rampage", "&4&l🔥 RAMPAGE"),
        UNSTOPPABLE("unstoppable", "&4&l💀 UNSTOPPABLE"),
        CLUTCH("clutch", "&e&l✨ CLUTCH WIN"),
        FLAWLESS("flawless", "&b&l⭐ FLAWLESS VICTORY"),
        HEADSHOT("headshot", "&6&l● HEADSHOT");

        public final String configKey;
        public final String banner;
        BonusType(String configKey, String banner) {
            this.configKey = configKey;
            this.banner = banner;
        }
    }

    public record BonusAwarded(BonusType type, int points) {}

    private final QuakeCraftPlugin plugin;
    private boolean firstBloodAwarded;

    // Per-player kill streak (resets on death)
    private final Map<UUID, Integer> killStreaks = new HashMap<>();
    // For double/triple-kill detection
    private final Map<UUID, Long> lastKillTimes = new HashMap<>();
    private final Map<UUID, Integer> recentKillsCount = new HashMap<>();
    // For revenge — who killed me last?
    private final Map<UUID, UUID> lastKilledBy = new HashMap<>();
    // For flawless — did I die this match?
    private final Set<UUID> diedThisMatch = new HashSet<>();

    public KillBonusManager(QuakeCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public void onMatchStart() {
        firstBloodAwarded = false;
        killStreaks.clear();
        lastKillTimes.clear();
        recentKillsCount.clear();
        lastKilledBy.clear();
        diedThisMatch.clear();
    }

    /**
     * Records a kill and returns any bonuses earned.
     *
     * @param killer the player who got the kill
     * @param victim the victim
     * @param yDelta absolute Y difference at time of hit (for headshot detection)
     */
    public List<BonusAwarded> onKill(Player killer, Player victim, double yDelta) {
        List<BonusAwarded> awarded = new ArrayList<>();
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();

        // 1. First blood
        if (!firstBloodAwarded) {
            firstBloodAwarded = true;
            int pts = pts(BonusType.FIRST_BLOOD, 30);
            if (pts > 0) {
                award(killer, BonusType.FIRST_BLOOD, pts);
                awarded.add(new BonusAwarded(BonusType.FIRST_BLOOD, pts));
            }
        }

        // 2. Revenge
        UUID killerOfKiller = lastKilledBy.get(killerId);
        if (killerOfKiller != null && killerOfKiller.equals(victimId)) {
            int pts = pts(BonusType.REVENGE, 15);
            if (pts > 0) {
                award(killer, BonusType.REVENGE, pts);
                awarded.add(new BonusAwarded(BonusType.REVENGE, pts));
            }
            lastKilledBy.remove(killerId);  // revenge clears the slate
        }

        // 3. Double / Triple kill
        long now = System.currentTimeMillis();
        Long lastKillMs = lastKillTimes.get(killerId);
        int recentCount = recentKillsCount.getOrDefault(killerId, 0);
        if (lastKillMs != null && (now - lastKillMs) <= 5000) {
            recentCount++;
        } else {
            recentCount = 1;
        }
        recentKillsCount.put(killerId, recentCount);
        lastKillTimes.put(killerId, now);

        if (recentCount == 2) {
            int pts = pts(BonusType.DOUBLE_KILL, 20);
            if (pts > 0) {
                award(killer, BonusType.DOUBLE_KILL, pts);
                awarded.add(new BonusAwarded(BonusType.DOUBLE_KILL, pts));
            }
        } else if (recentCount >= 3) {
            int pts = pts(BonusType.TRIPLE_KILL, 40);
            if (pts > 0) {
                award(killer, BonusType.TRIPLE_KILL, pts);
                awarded.add(new BonusAwarded(BonusType.TRIPLE_KILL, pts));
            }
        }

        // 4. Streak: rampage / unstoppable
        int streak = killStreaks.merge(killerId, 1, Integer::sum);
        if (streak == 5) {
            int pts = pts(BonusType.RAMPAGE, 50);
            if (pts > 0) {
                award(killer, BonusType.RAMPAGE, pts);
                awarded.add(new BonusAwarded(BonusType.RAMPAGE, pts));
            }
        } else if (streak == 10) {
            int pts = pts(BonusType.UNSTOPPABLE, 100);
            if (pts > 0) {
                award(killer, BonusType.UNSTOPPABLE, pts);
                awarded.add(new BonusAwarded(BonusType.UNSTOPPABLE, pts));
            }
        }

        // 5. Headshot
        if (yDelta > 0.7) {
            int pts = pts(BonusType.HEADSHOT, 10);
            if (pts > 0) {
                award(killer, BonusType.HEADSHOT, pts);
                awarded.add(new BonusAwarded(BonusType.HEADSHOT, pts));
            }
        }

        // Track who killed the victim (for their potential revenge)
        lastKilledBy.put(victimId, killerId);

        return awarded;
    }

    /**
     * Records a death — resets their kill streak, removes them from
     * the "flawless candidate" pool.
     */
    public void onDeath(Player victim) {
        UUID id = victim.getUniqueId();
        killStreaks.remove(id);
        recentKillsCount.remove(id);
        lastKillTimes.remove(id);
        diedThisMatch.add(id);
    }

    /**
     * Match ended — check end-of-match bonuses for the winner.
     * @param winner the player who won the round (or null for team modes)
     * @return list of bonuses awarded
     */
    public List<BonusAwarded> onMatchEnd(Player winner) {
        List<BonusAwarded> awarded = new ArrayList<>();
        if (winner == null) return awarded;

        UUID id = winner.getUniqueId();

        // Clutch — won at <= 2 HP
        if (winner.getHealth() <= 2.0) {
            int pts = pts(BonusType.CLUTCH, 25);
            if (pts > 0) {
                award(winner, BonusType.CLUTCH, pts);
                awarded.add(new BonusAwarded(BonusType.CLUTCH, pts));
            }
        }

        // Flawless — won without dying
        if (!diedThisMatch.contains(id)) {
            int pts = pts(BonusType.FLAWLESS, 50);
            if (pts > 0) {
                award(winner, BonusType.FLAWLESS, pts);
                awarded.add(new BonusAwarded(BonusType.FLAWLESS, pts));
            }
        }

        return awarded;
    }

    // ----------------------------------------------------------------

    private int pts(BonusType type, int defaultPts) {
        return plugin.getConfig().getInt("bonuses." + type.configKey, defaultPts);
    }

    private void award(Player p, BonusType type, int points) {
        KMCApi api = plugin.getKmcCore().getApi();
        api.givePoints(p.getUniqueId(), points);

        String banner = ChatColor.translateAlternateColorCodes('&', type.banner);
        p.sendTitle(banner, ChatColor.GREEN + "+" + points + " points",
                5, 30, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        // Broadcast the more dramatic ones
        if (type == BonusType.FIRST_BLOOD || type == BonusType.RAMPAGE
                || type == BonusType.UNSTOPPABLE || type == BonusType.FLAWLESS) {
            Bukkit.broadcastMessage(banner + ChatColor.GRAY + " " + p.getName());
        }
    }
}
