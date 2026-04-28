package nl.kmc.kmccore.announce;

import nl.kmc.kmccore.KMCCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Shows a title-card and 5-second teleport countdown to all players
 * before a minigame's onGameStart hook fires.
 *
 * <p>Sequence (when fireGameStart is invoked by KMCCore):
 * <ol>
 *   <li>0s: big title with the game's display name appears, subtitle
 *       reads "Klaar maken..." for ~1s</li>
 *   <li>1s: subtitle changes to "Teleporting in: 5"</li>
 *   <li>2s: "Teleporting in: 4" — count down to 1</li>
 *   <li>6s: minigame's onGameStart hook is called (the hook is
 *       responsible for the actual TP and its own countdown)</li>
 * </ol>
 *
 * <p>The minigame's countdown ("X starts in 10... 9...") runs AFTER
 * this — so the player sees:
 * <pre>
 *   GameName              ← KMCCore title (5s)
 *   Teleporting in 5...1  ← KMCCore subtitle countdown
 *   [teleport happens]
 *   GameName starts in 10  ← Minigame's own countdown
 *   ...
 *   GO!
 * </pre>
 */
public class PreGameAnnouncer {

    /**
     * Shows the title sequence to all online players, then runs
     * {@code dispatch} after the countdown completes.
     *
     * <p>Safe to call from any thread — schedules itself on the main thread.
     */
    public static void announceAndDispatch(KMCCore plugin, String gameId, Runnable dispatch) {
        Bukkit.getScheduler().runTask(plugin, () -> runOnMainThread(plugin, gameId, dispatch));
    }

    private static void runOnMainThread(KMCCore plugin, String gameId, Runnable dispatch) {
        // Resolve the friendly display name from the game registry
        var game = plugin.getGameManager().getGame(gameId);
        String displayName = game != null ? game.getDisplayName() : prettify(gameId);

        Component titleLine    = Component.text(displayName, NamedTextColor.GOLD);
        Component subtitlePrep = Component.text("Klaar maken...", NamedTextColor.GRAY);

        // 1. Show the title with "Klaar maken" subtitle for 1 second
        Title openTitle = Title.title(
                titleLine, subtitlePrep,
                Title.Times.times(
                        Duration.ofMillis(250),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(250)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(openTitle);
            p.playSound(p.getLocation(),
                    org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.5f);
        }

        // 2. Schedule the 5..1 teleport countdown, one tick per second
        for (int i = 0; i < 5; i++) {
            int secondsLeft = 5 - i;
            long delay = 20L + i * 20L;  // start 1s after title appears
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Component countSub = Component.text(
                        "Teleporting in: " + secondsLeft, NamedTextColor.YELLOW);
                Title countTitle = Title.title(
                        titleLine, countSub,
                        Title.Times.times(
                                Duration.ofMillis(0),
                                Duration.ofSeconds(1),
                                Duration.ofMillis(250)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(countTitle);
                    // Tick sound rises in pitch as countdown drops
                    float pitch = 1.0f + (5 - secondsLeft) * 0.15f;
                    p.playSound(p.getLocation(),
                            org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, pitch);
                }
            }, delay);
        }

        // 3. After 6 seconds total, dispatch to the minigame's hook
        //    (1s opening title + 5s countdown = 6s)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Clear any lingering title before the minigame takes over
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.clearTitle();
            }
            try { dispatch.run(); }
            catch (Exception e) {
                plugin.getLogger().warning("PreGameAnnouncer dispatch failed: "
                        + e.getMessage());
            }
        }, 120L);  // 6 seconds
    }

    /** "team_skywars" → "Team Skywars" — fallback if game isn't registered. */
    private static String prettify(String id) {
        if (id == null || id.isBlank()) return "Game";
        StringBuilder sb = new StringBuilder();
        boolean capNext = true;
        for (char c : id.toCharArray()) {
            if (c == '_' || c == '-') { sb.append(' '); capNext = true; continue; }
            sb.append(capNext ? Character.toUpperCase(c) : c);
            capNext = false;
        }
        return sb.toString();
    }
}