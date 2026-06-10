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

    private WinnerCeremony(KMCCore plugin, Runnable onDone) {
        this.plugin = plugin;
        this.onDone = onDone;
    }

    /** Starts the ceremony. Replaces any running one. */
    public static void run(KMCCore plugin, Runnable onDone) {
        if (active != null) active.cancelTasks();
        active = new WinnerCeremony(plugin, onDone);
        active.start();
    }

    /** Skips straight to the end of the active ceremony (admin). */
    public static void skip() {
        if (active != null) active.finishNow();
    }

    // ── Flow ──────────────────────────────────────────────────────────────────

    private void start() {
        if (!plugin.getConfig().getBoolean("winner-ceremony.enabled", true)) { finishNow(); return; }

        List<KMCTeam> standings = plugin.getTeamManager().getTeamsSortedByPoints();
        if (standings.isEmpty()) { finishNow(); return; }

        int pause = plugin.getConfig().getInt("winner-ceremony.reveal-pause-seconds", 4);
        int hold  = plugin.getConfig().getInt("winner-ceremony.champion-hold-seconds", 7);

        long t = 0;
        schedule(t, () -> titleAll("§6§l★ TOURNOOI VOLTOOID ★", "§7De uitslag...", Sound.BLOCK_NOTE_BLOCK_BELL));
        t += 3;

        if (standings.size() >= 3) { long d = t; schedule(d, () -> revealPlace(standings.get(2), 3)); t += pause; }
        if (standings.size() >= 2) { long d = t; schedule(d, () -> revealPlace(standings.get(1), 2)); t += pause; }

        long champAt = t;
        schedule(champAt, () -> revealChampions(standings.get(0)));
        t += hold;

        long mvpAt = t;
        schedule(mvpAt, this::revealMvp);
        t += plugin.getConfig().getInt("winner-ceremony.mvp-hold-seconds", 5);

        schedule(t, this::revealRecords);
        t += plugin.getConfig().getInt("winner-ceremony.records-hold-seconds", 6);

        schedule(t, this::finishNow);
    }

    private void revealPlace(KMCTeam team, int place) {
        String label = place == 3 ? "§c§l🥉 DERDE PLAATS" : "§7§l🥈 TWEEDE PLAATS";
        broadcastBlock(label, team.getColor() + team.getDisplayName() + " §8— §e" + team.getPoints() + " punten");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(label, team.getColor() + team.getDisplayName(), 8, 45, 12);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, place == 3 ? 1.0f : 1.3f);
        }
    }

    private void revealChampions(KMCTeam champ) {
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
    }

    private void revealMvp() {
        List<UUID> top = plugin.getTournamentDataStore().topMvp(false, 1);
        if (top.isEmpty()) return;
        UUID id = top.get(0);
        var store = plugin.getTournamentDataStore();
        var team = plugin.getTeamManager().getTeamByPlayer(id);
        String teamPart = team != null ? " §7(" + team.getColor() + team.getDisplayName() + "§7)" : "";
        broadcastBlock("§b§l★ TOURNAMENT MVP ★",
                "§e" + store.getMvpName(id) + teamPart + " §8— §e" + store.getTournamentMvp(id) + " game-MVP's");
        Player mp = Bukkit.getPlayer(id);
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
        cancelTasks();
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
