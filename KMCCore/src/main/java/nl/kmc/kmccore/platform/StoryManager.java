package nl.kmc.kmccore.platform;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.event.GameEndEvent;
import nl.kmc.core.event.GameStartEvent;
import nl.kmc.core.event.RoundStartEvent;
import nl.kmc.core.event.TournamentEndEvent;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;

/**
 * The storyline of the event: a short chapter when a round starts and a one-or-two-line recap after every
 * game ("X takes the lead from Y!", "Z climbs three places", "only 15 points between the top two").
 *
 * <p>Chapters can be written per round in config ({@code story.chapters.<round>.nl / .en}); where none is
 * given the story writes itself from the standings. Lines go to chat (in each player's language) and, for
 * the first line, to the presenter NPC.
 */
public final class StoryManager implements Listener {

    private final KMCCore plugin;
    /** Standings (team id -> {rank, points}) when the running game started. */
    private final Map<String, int[]> before = new HashMap<>();

    public StoryManager(KMCCore plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("story.enabled", true); }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(RoundStartEvent e) {
        if (!on()) return;
        int total = plugin.getTournamentManager().getTotalRounds();
        int round = e.getRoundNumber();
        // Wait for the round announcements to settle.
        Bukkit.getScheduler().runTaskLater(plugin, () -> chapter(round, total), 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameStart(GameStartEvent e) {
        snapshot();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameEnd(GameEndEvent e) {
        if (!on()) return;
        // After the points have been booked and the result ceremony had its moment.
        int delay = Math.max(20, plugin.getConfig().getInt("story.recap-delay-ticks", 240));
        Bukkit.getScheduler().runTaskLater(plugin, this::recap, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTournamentEnd(TournamentEndEvent e) {
        if (!on() || e.getWinningTeam() == null) return;
        KMCTeam w = e.getWinningTeam();
        broadcast("story.champion", w.getColor() + w.getDisplayName() + "§7", e.getEventNumber());
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    private void chapter(int round, int total) {
        List<KMCTeam> st = plugin.getTeamManager().getTeamsSortedByPoints();
        var cfg = plugin.getConfig();
        String base = "story.chapters." + round;
        if (cfg.isSet(base + ".nl") || cfg.isSet(base + ".en") || cfg.isSet(base)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                String code = plugin.getLanguageManager().getLanguage(p.getUniqueId());
                String text = cfg.getString(base + "." + code, cfg.getString(base + ".nl", cfg.getString(base, "")));
                if (!text.isBlank()) p.sendMessage(plugin.getLanguageManager().tr(p, "story.prefix")
                        + ChatColor.translateAlternateColorCodes('&', text));
            }
            say(cfg.getString(base + ".nl", cfg.getString(base, "")));
            return;
        }
        boolean finalRound = total > 0 && round >= total;
        String leader = st.isEmpty() ? "?" : st.get(0).getColor() + st.get(0).getDisplayName() + "§7";
        if (round <= 1 || st.isEmpty() || st.get(0).getPoints() == 0) {
            broadcast("story.round-first", round, total);
        } else if (finalRound) {
            broadcast("story.round-final", round, total, leader, gap(st));
        } else {
            broadcast("story.round-mid", round, total, leader, st.get(0).getPoints());
        }
    }

    // ── Recaps ────────────────────────────────────────────────────────────────

    private void snapshot() {
        before.clear();
        List<KMCTeam> st = plugin.getTeamManager().getTeamsSortedByPoints();
        for (int i = 0; i < st.size(); i++) before.put(st.get(i).getId(), new int[] {i + 1, st.get(i).getPoints()});
    }

    private void recap() {
        List<KMCTeam> st = plugin.getTeamManager().getTeamsSortedByPoints();
        if (st.size() < 2 || before.isEmpty()) return;
        List<String[]> lines = new ArrayList<>();   // {key, args...}

        KMCTeam leader = st.get(0), second = st.get(1);
        int[] leaderBefore = before.get(leader.getId());
        if (leaderBefore != null && leaderBefore[0] != 1) {
            KMCTeam old = null;
            for (KMCTeam t : st) { int[] b = before.get(t.getId()); if (b != null && b[0] == 1) old = t; }
            lines.add(new String[] {"story.lead-change", name(leader), old != null ? name(old) : "?"});
        }

        // biggest climb
        KMCTeam climber = null;
        int best = 0;
        for (int i = 0; i < st.size(); i++) {
            int[] b = before.get(st.get(i).getId());
            if (b == null) continue;
            int up = b[0] - (i + 1);
            if (up > best) { best = up; climber = st.get(i); }
        }
        if (climber != null && best >= 2) lines.add(new String[] {"story.climb", name(climber), String.valueOf(best)});

        // biggest points haul
        KMCTeam haul = null;
        int most = 0;
        for (KMCTeam t : st) {
            int[] b = before.get(t.getId());
            int gained = t.getPoints() - (b != null ? b[1] : 0);
            if (gained > most) { most = gained; haul = t; }
        }
        if (haul != null && most > 0 && lines.size() < 2)
            lines.add(new String[] {"story.haul", name(haul), String.valueOf(most)});

        int gap = leader.getPoints() - second.getPoints();
        if (gap <= plugin.getConfig().getInt("story.close-gap", 40) && lines.size() < 3)
            lines.add(new String[] {"story.close", name(leader), name(second), String.valueOf(gap)});
        else if (lines.size() < 3 && gap > 150)
            lines.add(new String[] {"story.runaway", name(leader), String.valueOf(gap)});

        if (lines.isEmpty()) return;
        int max = Math.max(1, plugin.getConfig().getInt("story.max-lines", 2));
        for (int i = 0; i < Math.min(max, lines.size()); i++) {
            String[] l = lines.get(i);
            Object[] args = Arrays.copyOfRange(l, 1, l.length);
            broadcast(l[0], args);
        }
    }

    private static String name(KMCTeam t) { return t.getColor() + t.getDisplayName() + "§7"; }

    private static int gap(List<KMCTeam> st) {
        return st.size() < 2 ? 0 : st.get(0).getPoints() - st.get(1).getPoints();
    }

    private void broadcast(String key, Object... args) {
        var lm = plugin.getLanguageManager();
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(lm.tr(p, "story.prefix") + lm.tr(p, key, args));
        say(ChatColor.stripColor(lm.tr("nl", key, args)));
    }

    private void say(String plain) {
        try {
            if (plain != null && !plain.isBlank() && plugin.getHost() != null)
                plugin.getHost().autoSay(plain, 9);
        } catch (Throwable ignored) { }
    }
}
