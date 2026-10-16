package nl.kmc.kmccore.tournament;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.scoring.AwardsEngine;
import nl.kmc.kmccore.scoring.EditionArchive;
import nl.kmc.kmccore.scoring.StatsBook;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Consumer;

/**
 * Everything that happens when a tournament ends, in one fixed order — nothing is reset until the very
 * last step, so every ceremony still sees the real numbers:
 *
 * <ol>
 *   <li>work out the awards, write the edition archive, record the history,</li>
 *   <li>announcement + top players,</li>
 *   <li>the podium ({@link WinnerCeremony}: 3rd, 2nd, champions, MVP, records),</li>
 *   <li>the awards ceremony ({@link AwardsCeremony}),</li>
 *   <li>the credits ({@link CreditsCeremony}),</li>
 *   <li>the Fan Favorite vote,</li>
 *   <li>the feedback question (clickable stars),</li>
 *   <li>the statsbooks,</li>
 *   <li>reset of points and stats.</li>
 * </ol>
 * Every part can be skipped with {@code /kmcauto skip}.
 */
public final class EventEndSequence {

    private static EventEndSequence active;
    private static volatile int feedbackEdition = -1;
    private static List<AwardsEngine.Award> lastAwards = List.of();

    private final KMCCore plugin;
    private final int edition;
    private final Runnable resetAction;
    private final List<BukkitTask> stepTasks = new ArrayList<>();
    private final List<Consumer<Runnable>> steps = new ArrayList<>();
    private int index = -1;
    private boolean cancelled;
    private Runnable skipCurrent;

    private EventEndSequence(KMCCore plugin, int edition, Runnable resetAction) {
        this.plugin = plugin;
        this.edition = edition;
        this.resetAction = resetAction;
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    /** Starts the end sequence; {@code resetAction} runs as the very last step (points/stats reset). */
    public static void run(KMCCore plugin, int edition, Runnable resetAction) {
        if (active != null) active.cancel();
        active = new EventEndSequence(plugin, edition, resetAction);
        plugin.getChatGate().mute("end:sequence");
        active.build();
        active.next();
    }

    /** Skips the part that is playing now. @return false when no end sequence is running. */
    public static boolean skip() {
        if (active == null) return false;
        active.skipStep();
        return true;
    }

    public static boolean isRunning() { return active != null; }

    /** Awards of the last finished tournament (for the statsbook / profile). */
    public static List<AwardsEngine.Award> lastAwards() { return lastAwards; }

    /** Edition number the crowd can still rate, or -1. */
    public static int feedbackEdition() { return feedbackEdition; }

    public static void closeFeedback() { feedbackEdition = -1; }

    // ── Steps ─────────────────────────────────────────────────────────────────

    private void build() {
        steps.add(this::prepare);
        steps.add(this::announce);
        steps.add(done -> { skipCurrent = WinnerCeremony::skip; WinnerCeremony.run(plugin, done); });
        steps.add(done -> { skipCurrent = AwardsCeremony::skip; AwardsCeremony.run(plugin, lastAwards, done); });
        steps.add(done -> ConsolationCeremony.run(plugin, done));
        steps.add(done -> { skipCurrent = CreditsCeremony::skip; CreditsCeremony.run(plugin, edition, done); });
        steps.add(this::fanFavorite);
        steps.add(this::feedback);
        steps.add(this::books);
        steps.add(this::reset);
    }

    private void next() {
        if (cancelled) return;
        index++;
        skipCurrent = null;
        if (index >= steps.size()) { if (active == this) active = null; return; }
        final int mine = index;
        try {
            steps.get(index).accept(() -> {
                if (!cancelled && index == mine) next();   // each step may only advance once
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] End-of-tournament step " + mine + " failed: " + t);
            if (!cancelled && index == mine) next();
        }
    }

    private void skipStep() {
        stepTasks.forEach(t -> { if (t != null) t.cancel(); });
        stepTasks.clear();
        Runnable s = skipCurrent;
        if (s != null) {
            s.run();                 // the ceremony finishes itself and calls its done-callback → next()
        } else {
            next();
        }
    }

    private void cancel() {
        cancelled = true;
        plugin.getChatGate().unmute("end:sequence");
        stepTasks.forEach(t -> { if (t != null) t.cancel(); });
        stepTasks.clear();
        if (active == this) active = null;
    }

    private void wait(int seconds, Runnable done) {
        stepTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!cancelled) done.run();
        }, Math.max(1, seconds) * 20L));
    }

    // 1 ─ numbers first: awards, archive, history

    private void prepare(Runnable done) {
        var ledger = plugin.getPointsLedger();
        lastAwards = AwardsEngine.compute(ledger.all(), ledger.highlights(), ledger.games());
        try { EditionArchive.write(plugin, edition, lastAwards); }
        catch (Throwable t) { plugin.getLogger().warning("[KMC] Edition archive failed: " + t); }
        try {
            plugin.getTournamentHistoryManager().recordTournamentEnd(
                    plugin.getTournamentManager().getStartedAtMs(), plugin.getTournamentManager().getTotalRounds());
        } catch (Throwable t) { plugin.getLogger().warning("[KMC] Tournament history failed: " + t); }
        try { ledger.flush(); } catch (Throwable ignored) { }
        done.run();
    }

    // 2 ─ announcement

    private void announce(Runnable done) {
        var tm = plugin.getTournamentManager();
        KMCTeam winner = tm.getFinalStandings().stream().findFirst().orElse(null);
        String winnerName = winner != null ? winner.getColor() + winner.getDisplayName() : "?";

        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══════════════════════════"));
        Bukkit.broadcastMessage(MessageUtil.color("&6&l   KMC #" + edition + " AFGELOPEN!"));
        Bukkit.broadcastMessage(MessageUtil.color("&eWinnaar: " + winnerName));
        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══════════════════════════"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(MessageUtil.color("&6&l🏆 KMC #" + edition), MessageUtil.color("Winnaar: " + winnerName), 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        // the host reveals the podium itself, step by step, in WinnerCeremony (3rd, 2nd, champions)
        List<PlayerData> top = plugin.getPlayerDataManager().getLeaderboard();
        if (!top.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Bukkit.broadcastMessage(MessageUtil.color("&6&l═══ Top Spelers ═══"));
                String[] medals = {"&6🥇", "&7🥈", "&c🥉"};
                for (int i = 0; i < Math.min(3, top.size()); i++) {
                    Bukkit.broadcastMessage(MessageUtil.color(medals[i] + " &f" + top.get(i).getName()
                            + " &8- &e" + top.get(i).getPoints() + " punten"));
                }
            }, 80L);
        }
        wait(6, done);
    }

    // 6 ─ crowd vote

    private void fanFavorite(Runnable done) {
        int secs = plugin.getConfig().getInt("end-sequence.fan-favorite-seconds", 20);
        if (secs <= 0 || Bukkit.getOnlinePlayers().isEmpty()) { done.run(); return; }
        plugin.getFanFavoriteManager().startVote(secs);
        wait(secs + 3, done);
    }

    // 7 ─ "how was it?" with five clickable stars

    private void feedback(Runnable done) {
        plugin.getChatGate().unmute("end:sequence");   // the ceremonies are over — people may talk again
        int secs = plugin.getConfig().getInt("end-sequence.feedback-seconds", 15);
        if (secs <= 0 || Bukkit.getOnlinePlayers().isEmpty()) { done.run(); return; }
        feedbackEdition = edition;
        var lm = plugin.getLanguageManager();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage("");
            p.sendMessage(lm.tr(p, "feedback.header"));
            p.sendMessage(lm.tr(p, "feedback.question"));
            Component stars = Component.empty();
            for (int s = 1; s <= 5; s++) {
                stars = stars.append(LegacyComponentSerializer.legacySection().deserialize("§e§l ★" + s + " ")
                        .clickEvent(ClickEvent.runCommand("/kmcfeedback " + s))
                        .hoverEvent(HoverEvent.showText(Component.text(s + "/5"))));
            }
            p.sendMessage(stars);
            p.sendMessage(lm.tr(p, "feedback.comment-hint"));
            p.sendMessage("");
        }
        wait(secs, done);
    }

    // 8 ─ the personal statsbooks

    private void books(Runnable done) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                ItemStack book = StatsBook.build(plugin, p, edition);
                var left = p.getInventory().addItem(book);
                if (!left.isEmpty()) {
                    p.getWorld().dropItem(p.getLocation(), book);
                    p.sendMessage(plugin.getLanguageManager().tr(p, "book.dropped"));
                } else {
                    p.sendMessage(plugin.getLanguageManager().tr(p, "book.received", edition));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Book build failed for " + p.getName() + ": " + t);
            }
        }
        wait(3, done);
    }

    // 9 ─ finally reset

    private void reset(Runnable done) {
        plugin.getChatGate().unmute("end:sequence");
        try { resetAction.run(); }
        catch (Throwable t) { plugin.getLogger().warning("[KMC] End-of-tournament reset failed: " + t); }
        try { if (plugin.getHost() != null) plugin.getHost().onSessionEnd(); } catch (Throwable ignored) { }
        if (active == this) active = null;
        done.run();
    }
}
