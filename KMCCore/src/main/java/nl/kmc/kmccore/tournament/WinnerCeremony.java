package nl.kmc.kmccore.tournament;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Winner Ceremony 2.0 — a staged tournament finale: reveal #3 → #2 → Champions,
 * with fireworks, a team showcase, the Tournament MVP, and tournament records.
 *
 * <p>Reads the final standings from the team manager and pulls the MVP from the
 * shared {@link TournamentDataStore}. Fully configurable + skippable.
 */
public final class WinnerCeremony {

    private static WinnerCeremony active;   // for skip support

    private final KMCCore plugin;
    private final Runnable onDone;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean finished;

    private final boolean demo;
    /** Where a test run starts: 3 = the whole ceremony, 2 = from second place, 1 = the champions, 0 = the MVP. */
    private final int from;
    private boolean scripted;

    private static final String SCRIPT_KEY = "winner";

    private WinnerCeremony(KMCCore plugin, Runnable onDone, boolean demo, int from) {
        this.plugin = plugin;
        this.onDone = onDone;
        this.demo = demo;
        this.from = from;
    }

    /** Starts the ceremony. Replaces any running one. */
    public static void run(KMCCore plugin, Runnable onDone) {
        start(plugin, onDone, false, 3);
    }

    /**
     * A test run of the real ceremony ({@code /kmchost third|second|champion|mvp|podium}): the same host show,
     * podium and reveals, from the given place on, with the teams as they stand now.
     *
     * @return false when there are no teams
     */
    public static boolean demo(KMCCore plugin, int from, Runnable onDone) {
        if (plugin.getTournamentManager().getFinalStandings().isEmpty()) return false;
        start(plugin, onDone, true, from);
        return active != null;
    }

    private static void start(KMCCore plugin, Runnable onDone, boolean demo, int from) {
        if (active != null) active.cancelTasks();
        active = new WinnerCeremony(plugin, onDone, demo, from);
        active.start();
    }

    /** True while a test run is playing. */
    public static boolean isDemo() { return active != null && active.demo; }

    /** Skips straight to the end of the active ceremony (admin). */
    public static void skip() {
        if (active != null) active.finishNow();
    }

    // ── The host show ─────────────────────────────────────────────────────────

    private record MvpInfo(String name, String teamLabel, int count) { }

    private MvpInfo mvpInfo() {
        List<UUID> top = plugin.getTournamentDataStore().topMvp(false, 1);
        if (!top.isEmpty()) {
            UUID id = top.get(0);
            var store = plugin.getTournamentDataStore();
            var team = plugin.getTeamManager().getTeamByPlayer(id);
            return new MvpInfo(store.getMvpName(id), team != null ? team.getColor() + team.getDisplayName() : "",
                    store.getTournamentMvp(id));
        }
        if (demo) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                var team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
                return new MvpInfo(p.getName(), team != null ? team.getColor() + team.getDisplayName() : "", 7);
            }
        }
        return null;
    }

    private String topRecord(List<PlayerData> all, Comparator<PlayerData> cmp, java.util.function.ToIntFunction<PlayerData> val) {
        return all.stream().sorted(cmp).findFirst().filter(pd -> val.applyAsInt(pd) > 0)
                .map(pd -> pd.getName() + " (" + val.applyAsInt(pd) + ")").orElse(null);
    }

    private static Map<String, String> placeValues(KMCTeam t, int place) {
        Map<String, String> v = new HashMap<>();
        v.put("place", String.valueOf(place));
        v.put("place_team", t.getColor() + t.getDisplayName() + "§f");
        v.put("place_points", String.valueOf(t.getPoints()));
        v.put("winner", t.getColor() + t.getDisplayName() + "§f");
        return v;
    }

    /** The host presents the whole ceremony as one show (see {@code win-*} in host.yml); true when it does. */
    private boolean startScript(List<KMCTeam> standings) {
        var host = plugin.getHost();
        if (host == null) return false;
        List<nl.kmc.kmccore.host.HostManager.Part> parts = new ArrayList<>();
        if (from >= 3) {
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("win-intro", new HashMap<>(), () ->
                    titleAll("§6§l★ TOURNOOI VOLTOOID ★", "§7De uitslag...", Sound.BLOCK_NOTE_BLOCK_BELL)));
        }
        if (standings.size() >= 3 && from >= 3) {
            KMCTeam t = standings.get(2);
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("win-third-build", new HashMap<>(), null));
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("finale-third", placeValues(t, 3), () -> revealPlace(t, 3)));
        }
        if (standings.size() >= 2 && from >= 2) {
            KMCTeam t = standings.get(1);
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("win-second-build", new HashMap<>(), null));
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("finale-second", placeValues(t, 2), () -> revealPlace(t, 2)));
        }
        if (from >= 1) {
            KMCTeam champ = standings.get(0);
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("champion-build", placeValues(champ, 1), null));
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("champion-reveal", placeValues(champ, 1), () -> revealChampions(champ)));
        }
        MvpInfo mvp = mvpInfo();
        if (mvp != null) {
            Map<String, String> v = new HashMap<>();
            v.put("mvp_name", mvp.name());
            v.put("mvp_team", mvp.teamLabel());
            v.put("mvp_count", String.valueOf(mvp.count()));
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("mvp-build", v, null));
            parts.add(new nl.kmc.kmccore.host.HostManager.Part("mvp-reveal", v, () -> revealMvp(mvp)));
        }
        List<PlayerData> all = plugin.getPlayerDataManager().getLeaderboard();
        Map<String, String> rv = new HashMap<>();
        String kills = topRecord(all, Comparator.comparingInt(PlayerData::getKills).reversed(), PlayerData::getKills);
        String wins = topRecord(all, Comparator.comparingInt(PlayerData::getWins).reversed(), PlayerData::getWins);
        String streak = topRecord(all, Comparator.comparingInt(PlayerData::getBestWinStreak).reversed(), PlayerData::getBestWinStreak);
        if (kills != null) rv.put("record_kills", kills);
        if (wins != null) rv.put("record_wins", wins);
        if (streak != null) rv.put("record_streak", streak);
        if (!rv.isEmpty()) parts.add(new nl.kmc.kmccore.host.HostManager.Part("records-script", rv, this::revealRecords));
        parts.add(new nl.kmc.kmccore.host.HostManager.Part("win-outro", new HashMap<>(), null));

        // the teams on the podium keep their own view; everybody else follows the host's camera
        Set<UUID> onPodium = new HashSet<>();
        for (int i = 0; i < Math.min(3, standings.size()); i++) onPodium.addAll(standings.get(i).getMembers());
        int ticks = host.playScript(SCRIPT_KEY, parts, onPodium, demo, this::finishNow);
        if (ticks < 0) return false;
        scripted = true;
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, this::finishNow, ticks + 20L * 90));   // safety net
        return true;
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    private void start() {
        if (!demo && !plugin.getConfig().getBoolean("winner-ceremony.enabled", true)) { finishNow(); return; }

        List<KMCTeam> standings = plugin.getTournamentManager().getFinalStandings();
        if (standings.isEmpty()) { finishNow(); return; }
        if (startScript(standings)) return;

        int pause = plugin.getConfig().getInt("winner-ceremony.reveal-pause-seconds", 4);
        int hold  = plugin.getConfig().getInt("winner-ceremony.champion-hold-seconds", 7);

        try {
            var podium = plugin.getPodium();
            if (podium.isSet()) {
                java.util.Set<UUID> onPodium = new java.util.HashSet<>();
                for (int i = 0; i < Math.min(3, standings.size()); i++) onPodium.addAll(standings.get(i).getMembers());
                List<Player> viewers = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) if (!onPodium.contains(p.getUniqueId())) viewers.add(p);
                int total = 3 + (standings.size() >= 3 ? pause : 0) + (standings.size() >= 2 ? pause : 0) + hold
                        + plugin.getConfig().getInt("winner-ceremony.mvp-hold-seconds", 5)
                        + plugin.getConfig().getInt("winner-ceremony.records-hold-seconds", 6);
                podium.watch(viewers, total * 20);
            }
        } catch (Throwable t) { plugin.getLogger().warning("[KMC] Podium camera: " + t); }

        long t = 0;
        schedule(t, () -> titleAll("§6§l★ TOURNOOI VOLTOOID ★", "§7De uitslag...", Sound.BLOCK_NOTE_BLOCK_BELL));
        t += 3;

        if (standings.size() >= 3) { long d = t; schedule(d, () -> revealPlace(standings.get(2), 3)); t += pause; }
        if (standings.size() >= 2) { long d = t; schedule(d, () -> revealPlace(standings.get(1), 2)); t += pause; }

        long champAt = t;
        schedule(champAt, () -> revealChampions(standings.get(0)));
        t += hold;

        long mvpAt = t;
        schedule(mvpAt, () -> { MvpInfo m = mvpInfo(); if (m != null) revealMvp(m); });
        t += plugin.getConfig().getInt("winner-ceremony.mvp-hold-seconds", 5);

        schedule(t, this::revealRecords);
        t += plugin.getConfig().getInt("winner-ceremony.records-hold-seconds", 6);

        schedule(t, this::finishNow);
    }

    private void revealPlace(KMCTeam team, int place) {
        try { plugin.getPodium().place(team, place); } catch (Throwable t) { plugin.getLogger().warning("[KMC] Podium: " + t); }
        try { if (!scripted && plugin.getHost() != null) plugin.getHost().onPlaceReveal(team, place); }
        catch (Throwable ignored) { }
        String label = place == 3 ? "§c§l🥉 DERDE PLAATS" : "§7§l🥈 TWEEDE PLAATS";
        broadcastBlock(label, team.getColor() + team.getDisplayName() + " §8— §e" + team.getPoints() + " punten");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(label, team.getColor() + team.getDisplayName(), 8, 45, 12);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, place == 3 ? 1.0f : 1.3f);
        }
    }

    private void revealChampions(KMCTeam champ) {
        try { plugin.getPodium().place(champ, 1); } catch (Throwable t) { plugin.getLogger().warning("[KMC] Podium: " + t); }
        try { if (!scripted && plugin.getHost() != null) plugin.getHost().onChampions(champ); }
        catch (Throwable ignored) { }
        broadcastBlock("§6§l🏆 KAMPIOENEN 🏆", champ.getColor() + champ.getDisplayName()
                + " §8— §e" + champ.getPoints() + " punten");
        // Team showcase: list members.
        StringBuilder members = new StringBuilder("§7Team: ");
        var ids = champ.getMembers();
        for (int i = 0; i < ids.size(); i++) {
            members.append(i == 0 ? "" : "§7, ").append(champ.getColor()).append(name(ids.get(i)));
        }
        if (!ids.isEmpty()) Bukkit.broadcastMessage(MessageUtil.color("  " + members));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§6§l🏆 KAMPIOENEN", champ.getColor() + "§l" + champ.getDisplayName(), 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
        launchFireworks(champ.getColor());
        try { nl.kmc.kmccore.presentation.TeamJingles.playNow(plugin, champ); }
        catch (Throwable ignored) { }
    }

    private void revealMvp(MvpInfo mvp) {
        UUID id = null;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equals(mvp.name())) { id = p.getUniqueId(); break; }
        broadcastBlock("§b§l★ TOURNAMENT MVP ★",
                "§e" + mvp.name() + (mvp.teamLabel().isEmpty() ? "" : " §7(" + mvp.teamLabel() + "§7)")
                        + " §8— §e" + mvp.count() + " game-MVP's");
        Player mp = id == null ? null : Bukkit.getPlayer(id);
        if (mp != null) mp.sendTitle("§b§l★ TOURNAMENT MVP ★", "§eJij was de beste van het toernooi!", 10, 70, 15);
        for (Player p : Bukkit.getOnlinePlayers())
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.3f);
    }

    private void revealRecords() {
        List<PlayerData> all = plugin.getPlayerDataManager().getLeaderboard();
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
        Bukkit.broadcastMessage(MessageUtil.color("        &d&l📜 TOERNOOIRECORDS"));
        record(all, "&cMeeste kills",   Comparator.comparingInt(PlayerData::getKills).reversed(),  PlayerData::getKills);
        record(all, "&6Meeste wins",    Comparator.comparingInt(PlayerData::getWins).reversed(),   PlayerData::getWins);
        record(all, "&bHoogste streak", Comparator.comparingInt(PlayerData::getBestWinStreak).reversed(), PlayerData::getBestWinStreak);
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
    }

    private void record(List<PlayerData> all, String label, Comparator<PlayerData> cmp,
                        java.util.function.ToIntFunction<PlayerData> val) {
        all.stream().sorted(cmp).findFirst().ifPresent(pd -> {
            if (val.applyAsInt(pd) > 0)
                Bukkit.broadcastMessage(MessageUtil.color("  " + label + ": &f" + pd.getName() + " &7(" + val.applyAsInt(pd) + ")"));
        });
    }

    private void launchFireworks(ChatColor teamColor) {
        Color color = dye(teamColor);
        int seconds = plugin.getConfig().getInt("winner-ceremony.firework-seconds", 6);
        for (int t = 0; t < seconds * 20; t += 8) {
            schedule((long) Math.ceil(t / 20.0), () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (new Random().nextInt(3) != 0) continue; // ~1/3 of players each burst
                    Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class);
                    FireworkMeta meta = fw.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .withColor(color, Color.WHITE).withFade(Color.YELLOW)
                            .with(FireworkEffect.Type.values()[new Random().nextInt(FireworkEffect.Type.values().length)])
                            .flicker(true).trail(true).build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                }
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void titleAll(String title, String sub, Sound sound) {
        for (Player p : Bukkit.getOnlinePlayers()) { p.sendTitle(title, sub, 8, 45, 12); p.playSound(p.getLocation(), sound, 1f, 1f); }
    }

    private void broadcastBlock(String title, String line) {
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
        Bukkit.broadcastMessage(MessageUtil.color("        " + title));
        Bukkit.broadcastMessage(MessageUtil.color("  " + line));
        Bukkit.broadcastMessage(MessageUtil.color("&8&m                                        "));
    }

    private String name(UUID id) {
        var off = Bukkit.getOfflinePlayer(id);
        return off.getName() != null ? off.getName() : id.toString().substring(0, 8);
    }

    private void schedule(long delaySeconds, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!finished) r.run(); }, Math.max(0, delaySeconds * 20L)));
    }

    private void cancelTasks() { tasks.forEach(t -> { if (t != null) t.cancel(); }); tasks.clear(); }

    private void finishNow() {
        if (finished) return;
        finished = true;
        if (scripted) { try { plugin.getHost().cancelScript(SCRIPT_KEY); } catch (Throwable ignored) { } }
        cancelTasks();
        try { plugin.getPodium().finish(); } catch (Throwable ignored) { }
        if (active == this) active = null;
        if (onDone != null) try { onDone.run(); } catch (Throwable ignored) {}
    }

    private static Color dye(ChatColor c) {
        return switch (c) {
            case RED, DARK_RED -> Color.RED;
            case GOLD          -> Color.ORANGE;
            case YELLOW        -> Color.YELLOW;
            case GREEN, DARK_GREEN -> Color.LIME;
            case AQUA, DARK_AQUA   -> Color.AQUA;
            case BLUE, DARK_BLUE   -> Color.BLUE;
            case DARK_PURPLE       -> Color.PURPLE;
            case LIGHT_PURPLE      -> Color.FUCHSIA;
            default                -> Color.WHITE;
        };
    }
}
