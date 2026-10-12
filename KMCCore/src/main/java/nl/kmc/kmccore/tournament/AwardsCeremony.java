package nl.kmc.kmccore.tournament;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.AwardsEngine.Award;
import nl.kmc.kmccore.scoring.AwardsEngine.Standing;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * The awards ceremony: one prize at a time — best clutch, best racer, best parkour player, best teammate,
 * team prizes … and the tournament MVP last. Each one is a title, a framed chat card with the winner,
 * the value and the runners-up, a sound and a small firework.
 */
public final class AwardsCeremony {

    private static AwardsCeremony active;

    private final KMCCore plugin;
    private final List<Award> queue;
    private final Runnable onDone;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean finished;

    private AwardsCeremony(KMCCore plugin, List<Award> awards, Runnable onDone) {
        this.plugin = plugin;
        this.queue = order(awards);
        this.onDone = onDone;
    }

    /** Presentation order: the small prizes first, team prizes, and the MVP as the grand finale. */
    private static List<Award> order(List<Award> awards) {
        List<String> order = List.of("consistent", "teammate", "clutch", "racer", "parkour", "killer", "winner",
                "team_consistent", "team_comeback", "team_wins", "mvp");
        List<Award> out = new ArrayList<>(awards);
        out.sort(Comparator.comparingInt(a -> {
            int i = order.indexOf(a.id());
            return i < 0 ? -1 : i;
        }));
        return out;
    }

    public static void run(KMCCore plugin, List<Award> awards, Runnable onDone) {
        if (active != null) active.finishNow(false);
        if (awards.isEmpty() || !plugin.getConfig().getBoolean("awards-ceremony.enabled", true)) {
            if (onDone != null) onDone.run();
            return;
        }
        active = new AwardsCeremony(plugin, awards, onDone);
        active.start();
    }

    public static boolean skip() {
        if (active == null) return false;
        active.finishNow(true);
        return true;
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    private void start() {
        int per = Math.max(3, plugin.getConfig().getInt("awards-ceremony.seconds-per-award", 7));
        long t = 0;
        schedule(t, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(plugin.getLanguageManager().tr(p, "awards.intro.title"),
                        plugin.getLanguageManager().tr(p, "awards.intro.sub"), 10, 50, 10);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.1f);
            }
        });
        t += 4;
        for (Award a : queue) {
            final Award award = a;
            schedule(t, () -> reveal(award));
            t += per;
        }
        schedule(t, () -> finishNow(false));
    }

    private void reveal(Award a) {
        var lm = plugin.getLanguageManager();
        KMCTeam team = a.teamId() != null ? plugin.getTeamManager().getTeam(a.teamId()) : null;
        String teamLabel = team != null ? team.getColor() + team.getDisplayName() : "";
        String winner = a.teamAward() ? teamLabel : (team != null ? team.getColor() : "§f") + nz(a.name());

        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = lm.tr(p, "awards." + a.id() + ".name");
            p.sendMessage("");
            p.sendMessage("§6§m                                        ");
            p.sendMessage("        " + name);
            p.sendMessage("  " + lm.tr(p, "awards." + a.id() + ".desc"));
            p.sendMessage("  §e" + winner
                    + (a.teamAward() || team == null ? "" : " §8(" + teamLabel + "§8)")
                    + " §8— " + lm.tr(p, "awards." + a.id() + ".value", fmt(a.value())));
            int i = 2;
            for (Standing r : a.runnersUp()) {
                KMCTeam rt = r.teamId() != null ? plugin.getTeamManager().getTeam(r.teamId()) : null;
                String who = a.teamAward()
                        ? (rt != null ? rt.getColor() + rt.getDisplayName() : nz(r.teamId()))
                        : "§f" + nz(r.name());
                p.sendMessage("    §7" + i++ + ". " + who + " §8— §7" + fmt(r.value()));
            }
            p.sendMessage("§6§m                                        ");
            p.sendTitle(name, winner, 5, 70, 15);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
        }
        // A small firework above the winner (if online) — or above everybody for a team prize.
        if (!a.teamAward() && a.player() != null) {
            Player w = Bukkit.getPlayer(a.player());
            if (w != null) burst(w, team != null ? dye(team) : Color.YELLOW);
        } else if (team != null) {
            for (UUID m : team.getMembers()) {
                Player mp = Bukkit.getPlayer(m);
                if (mp != null) burst(mp, dye(team));
            }
        }
    }

    private void burst(Player p, Color c) {
        try {
            Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().withColor(c, Color.WHITE).with(FireworkEffect.Type.STAR)
                    .flicker(true).trail(true).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
            // Fireworks must never hurt anybody (see FireworkSafetyListener) — detonate right away.
            Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 12L);
        } catch (Throwable ignored) { }
    }

    private static Color dye(KMCTeam t) {
        return switch (String.valueOf(t.getColor()).replace("§", "").toLowerCase(Locale.ROOT)) {
            case "c", "4" -> Color.RED;
            case "6"      -> Color.ORANGE;
            case "e"      -> Color.YELLOW;
            case "a", "2" -> Color.LIME;
            case "b", "3" -> Color.AQUA;
            case "9", "1" -> Color.BLUE;
            case "5"      -> Color.PURPLE;
            case "d"      -> Color.FUCHSIA;
            default       -> Color.WHITE;
        };
    }

    private void schedule(long seconds, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!finished) r.run(); }, seconds * 20L));
    }

    private void finishNow(boolean skipped) {
        if (finished) return;
        finished = true;
        tasks.forEach(t -> { if (t != null) t.cancel(); });
        tasks.clear();
        if (active == this) active = null;
        if (skipped) for (Player p : Bukkit.getOnlinePlayers()) p.clearTitle();
        if (onDone != null) try { onDone.run(); } catch (Throwable ignored) { }
    }

    private static String nz(String s) { return s == null ? "?" : s; }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
