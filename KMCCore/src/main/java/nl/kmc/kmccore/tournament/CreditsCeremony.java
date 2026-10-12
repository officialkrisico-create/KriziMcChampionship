package nl.kmc.kmccore.tournament;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.PointsLedger;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * The end credits: the tournament in numbers, every team with its players, the champions and a thank-you,
 * shown one card at a time (title + chat) like a film roll. Texts per language ({@code credits.*}); the
 * thank-you lines come from {@code credits.thanks} in config.yml.
 */
public final class CreditsCeremony {

    private static CreditsCeremony active;

    private final KMCCore plugin;
    private final int edition;
    private final Runnable onDone;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean finished;

    private CreditsCeremony(KMCCore plugin, int edition, Runnable onDone) {
        this.plugin = plugin;
        this.edition = edition;
        this.onDone = onDone;
    }

    public static void run(KMCCore plugin, int edition, Runnable onDone) {
        if (active != null) active.finishNow();
        if (!plugin.getConfig().getBoolean("credits.enabled", true)) {
            if (onDone != null) onDone.run();
            return;
        }
        active = new CreditsCeremony(plugin, edition, onDone);
        active.start();
    }

    public static boolean skip() {
        if (active == null) return false;
        active.finishNow();
        return true;
    }

    private void start() {
        int per = Math.max(3, plugin.getConfig().getInt("credits.seconds-per-card", 5));
        List<Card> cards = new ArrayList<>();

        // 1. The tournament in numbers.
        var ledger = plugin.getPointsLedger();
        int games = (int) ledger.games().stream().filter(g -> !ledger.forGame(g.seq()).isEmpty()).count();
        int totalPoints = 0, kills = 0;
        for (var e : ledger.all()) {
            if (e.teamOnly()) continue;
            totalPoints += e.points();
            if ("KILL".equals(e.reason())) kills++;
        }
        int players = plugin.getPlayerDataManager().getLeaderboard().size();
        final int fGames = games, fPoints = totalPoints, fKills = kills, fPlayers = players;
        cards.add(new Card(p -> List.of(
                plugin.getLanguageManager().tr(p, "credits.numbers.title", plugin.getConfig().getString("tournament.name", "KMC"), edition),
                plugin.getLanguageManager().tr(p, "credits.numbers.games", fGames),
                plugin.getLanguageManager().tr(p, "credits.numbers.players", fPlayers),
                plugin.getLanguageManager().tr(p, "credits.numbers.points", fPoints),
                plugin.getLanguageManager().tr(p, "credits.numbers.kills", fKills))));

        // 2. Every team with its players, in final order.
        for (KMCTeam t : plugin.getTournamentManager().getFinalStandings()) {
            final KMCTeam team = t;
            cards.add(new Card(p -> {
                List<String> names = new ArrayList<>();
                for (UUID u : team.getMembers()) {
                    var op = Bukkit.getOfflinePlayer(u);
                    names.add(op.getName() != null ? op.getName() : u.toString().substring(0, 8));
                }
                return List.of(team.getColor() + "§l" + team.getDisplayName(),
                        "§7" + String.join("§8, §7", names),
                        plugin.getLanguageManager().tr(p, "credits.team.points", team.getPoints()));
            }));
        }

        // 3. The thank-you.
        List<String> thanks = plugin.getConfig().getStringList("credits.thanks");
        if (thanks.isEmpty()) thanks = List.of("&6Bedankt voor het spelen!", "&7Tot het volgende KMC-event!");
        final List<String> fThanks = thanks;
        cards.add(new Card(p -> {
            List<String> l = new ArrayList<>();
            for (String s : fThanks) l.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', s));
            return l;
        }));

        long t = 1;
        for (Card c : cards) {
            final Card card = c;
            schedule(t, () -> show(card));
            t += per;
        }
        schedule(t, this::finishNow);
    }

    private record Card(java.util.function.Function<Player, List<String>> lines) {}

    private void show(Card c) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<String> lines = c.lines().apply(p);
            if (lines.isEmpty()) continue;
            p.sendMessage("");
            for (String l : lines) p.sendMessage("   " + l);
            p.sendTitle(lines.get(0), lines.size() > 1 ? lines.get(1) : "", 8, 55, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
        }
    }

    private void schedule(long seconds, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!finished) r.run(); }, seconds * 20L));
    }

    private void finishNow() {
        if (finished) return;
        finished = true;
        tasks.forEach(t -> { if (t != null) t.cancel(); });
        tasks.clear();
        if (active == this) active = null;
        if (onDone != null) try { onDone.run(); } catch (Throwable ignored) { }
    }
}
