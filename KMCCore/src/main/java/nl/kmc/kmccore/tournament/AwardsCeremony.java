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
    private final boolean demo;
    private boolean scripted;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean finished;

    private AwardsCeremony(KMCCore plugin, List<Award> awards, Runnable onDone, boolean demo) {
        this.demo = demo;
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
        run(plugin, awards, onDone, false);
    }

    private static void run(KMCCore plugin, List<Award> awards, Runnable onDone, boolean demo) {
        if (active != null) active.finishNow(false);
        if (awards.isEmpty() || (!demo && !plugin.getConfig().getBoolean("awards-ceremony.enabled", true))) {
            if (onDone != null) onDone.run();
            return;
        }
        active = new AwardsCeremony(plugin, awards, onDone, demo);
        active.start();
    }

    /**
     * A test run of the real ceremony ({@code /kmchost awards}): the same host show, camera and reveals, with
     * stand-in awards made from the players who are online. Nothing is stored.
     *
     * @return false when nobody is online
     */
    public static boolean demo(KMCCore plugin, Runnable onDone) {
        List<Award> awards = demoAwards(plugin);
        if (awards.isEmpty()) return false;
        run(plugin, awards, onDone, true);
        return active != null;
    }

    /** True while a test run is playing. */
    public static boolean isDemo() { return active != null && active.demo; }

    public static boolean skip() {
        if (active == null) return false;
        active.finishNow(true);
        return true;
    }

    private static List<Award> demoAwards(KMCCore plugin) {
        List<Player> ps = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (ps.isEmpty()) return List.of();
        List<Award> out = new ArrayList<>();
        String[] ids = {"teammate", "clutch", "killer", "mvp"};
        double[] vals = {42, 3, 17, 640};
        for (int i = 0; i < ids.length; i++) {
            Player w = ps.get(i % ps.size());
            List<Standing> runners = new ArrayList<>();
            for (int r = 1; r <= 2 && ps.size() > r; r++) {
                Player o = ps.get((i + r) % ps.size());
                runners.add(new Standing(o.getUniqueId(), o.getName(), teamIdOf(plugin, o), Math.max(1, vals[i] - r * 4)));
            }
            out.add(new Award(ids[i], false, w.getUniqueId(), w.getName(), teamIdOf(plugin, w), vals[i], runners));
        }
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        if (!teams.isEmpty()) {
            List<Standing> runners = new ArrayList<>();
            for (int r = 1; r <= 2 && teams.size() > r; r++)
                runners.add(new Standing(null, teams.get(r).getDisplayName(), teams.get(r).getId(), 5 - r));
            out.add(new Award("team_wins", true, null, null, teams.get(0).getId(), 5, runners));
        }
        return out;
    }

    private static String teamIdOf(KMCCore plugin, Player p) {
        KMCTeam t = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
        return t == null ? null : t.getId();
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    private static final String SCRIPT_KEY = "awards";

    /** The host presents the ceremony as one show (see {@code awards-*} in host.yml); true when it does. */
    private boolean startScript() {
        var host = plugin.getHost();
        if (host == null) return false;
        List<nl.kmc.kmccore.host.HostManager.Part> parts = new ArrayList<>();
        parts.add(new nl.kmc.kmccore.host.HostManager.Part("awards-intro", new HashMap<>(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(plugin.getLanguageManager().tr(p, "awards.intro.title"),
                        plugin.getLanguageManager().tr(p, "awards.intro.sub"), 10, 50, 10);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.1f);
            }
        }));
        int n = queue.size();
        for (int i = 0; i < n; i++) {
            final Award a = queue.get(i);
            Map<String, String> v = scriptValues(a, i + 1, n);
            boolean mvp = a.id().equals("mvp");
            int variant = i % 3 + 1;
            parts.add(new nl.kmc.kmccore.host.HostManager.Part(mvp ? "awards-mvp-build" : "awards-build-" + variant, v, null));
            parts.add(new nl.kmc.kmccore.host.HostManager.Part(mvp ? "awards-mvp-reveal" : "awards-reveal-" + variant, v,
                    () -> reveal(a)));
        }
        parts.add(new nl.kmc.kmccore.host.HostManager.Part("awards-outro", new HashMap<>(), null));
        int ticks = host.playScript(SCRIPT_KEY, parts, Set.of(), demo, () -> finishNow(false));
        if (ticks < 0) return false;
        scripted = true;
        // Safety net: whatever happens to the host, the ceremony never hangs.
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> finishNow(false), ticks + 20L * 90));
        return true;
    }

    private Map<String, String> scriptValues(Award a, int index, int total) {
        var lm = plugin.getLanguageManager();
        KMCTeam team = a.teamId() != null ? plugin.getTeamManager().getTeam(a.teamId()) : null;
        String teamLabel = team != null ? team.getColor() + team.getDisplayName() : "";
        Map<String, String> v = new HashMap<>();
        v.put("award_name", spoken(lm.tr("nl", "awards." + a.id() + ".name")));
        v.put("award_desc", plain(lm.tr("nl", "awards." + a.id() + ".desc")));
        v.put("award_who", a.teamAward() ? teamLabel : (team != null ? team.getColor() : "§f") + nz(a.name()));
        v.put("award_value", plain(lm.tr("nl", "awards." + a.id() + ".value", fmt(a.value()))));
        v.put("award_index", String.valueOf(index));
        v.put("award_total", String.valueOf(total));
        v.put("award_team", teamLabel);
        return v;
    }

    private static String plain(String s) {
        return s == null ? "" : org.bukkit.ChatColor.stripColor(nl.kmc.kmccore.util.MessageUtil.color(s)).trim();
    }

    /** "&6&l★ MVP VAN HET TOERNOOI" → "MVP van het toernooi": no colours or symbols, sentence case. */
    private static String spoken(String s) {
        String t = plain(s).replaceFirst("^[^\\p{L}\\p{N}]+", "").replace("(TEAM)", "").trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return t;
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return t.replace("Mvp", "MVP");
    }

    private void start() {
        if (startScript()) return;
        try { if (plugin.getHost() != null) plugin.getHost().onAwards(); }
        catch (Throwable ignored) { }
        int per = Math.max(3, plugin.getConfig().getInt("awards-ceremony.seconds-per-award", 7));
        // The awards happen at the same podium as the rest of the ceremonies, with the host beside it.
        try {
            var podium = plugin.getPodium();
            if (podium != null && podium.isSet())
                podium.watch(new ArrayList<>(Bukkit.getOnlinePlayers()), (4 + queue.size() * per + 2) * 20);
        } catch (Throwable t) { plugin.getLogger().warning("[KMC] Awards camera: " + t); }
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
        var pod = plugin.getPodium();
        if (pod != null && pod.isSet() && pod.spot(1) != null) {       // everybody watches the podium: fireworks there
            Color c = team != null ? dye(team) : Color.YELLOW;
            burstAt(pod.spot(1).add(0, 1, 0), c);
            return;
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

    private void burstAt(org.bukkit.Location at, Color c) {
        try {
            Firework fw = at.getWorld().spawn(at, Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().withColor(c, Color.WHITE).with(FireworkEffect.Type.STAR)
                    .flicker(true).trail(true).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
            Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 12L);
        } catch (Throwable ignored) { }
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
        if (scripted) { try { plugin.getHost().cancelScript(SCRIPT_KEY); } catch (Throwable ignored) { } }
        tasks.forEach(t -> { if (t != null) t.cancel(); });
        tasks.clear();
        if (active == this) active = null;
        if (skipped) for (Player p : Bukkit.getOnlinePlayers()) p.clearTitle();
        try { var podium = plugin.getPodium(); if (podium != null && podium.isSet()) podium.finish(); }
        catch (Throwable ignored) { }
        if (onDone != null) try { onDone.run(); } catch (Throwable ignored) { }
    }

    private static String nz(String s) { return s == null ? "?" : s; }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
