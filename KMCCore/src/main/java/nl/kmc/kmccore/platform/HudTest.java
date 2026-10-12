package nl.kmc.kmccore.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.ChatLayout;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /kmchud test}: shows every kind of on-screen text at once for {@link #SECONDS} seconds, so you can
 * judge the HUD with your own GUI scale: a sidebar with short to over-long lines, a bossbar, an actionbar and
 * three titles of growing width (with the width in pixels in the subtitle).
 */
public final class HudTest {

    public static final int SECONDS = 15;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Map<UUID, Long> UNTIL = new ConcurrentHashMap<>();

    private HudTest() { }

    /** Sidebar lines while a test runs for this player, otherwise {@code null}. */
    public static List<String> sidebar(Player p) {
        Long until = UNTIL.get(p.getUniqueId());
        if (until == null) return null;
        if (System.currentTimeMillis() > until) {
            UNTIL.remove(p.getUniqueId());
            return null;
        }
        return List.of(
                "§7Stand: §f" + "test",
                "§aKort",
                "§eMiddel: twintig tekens",
                "§cLang: dit past net niet meer in de sidebar",
                "§6§lVET: nog langer, dit moet worden afgekapt",
                "§dRode Ratten §f1234 punten",
                "§bGroene Goudvissen §f1234 punten",
                "§7Alles met een … is afgekapt");
    }

    public static String sidebarTitle() {
        return "§e§lHUD-TEST";
    }

    public static void start(KMCCore plugin, Player p) {
        var lm = plugin.getLanguageManager();
        UNTIL.put(p.getUniqueId(), System.currentTimeMillis() + SECONDS * 1000L);
        try { plugin.getScoreboardManager().forceRefreshPlayer(p); } catch (Throwable ignored) { }

        p.sendMessage(lm.tr(p, "platform.hud.test-start", HudScale.get(plugin, p), HudScale.maxPx(plugin, p), SECONDS));
        p.sendMessage(lm.tr(p, "platform.hud.test-hint"));

        final String longBar = "§6§lBOSSBAR-TEST §7— §fdit is een erg lange tekst die netjes moet worden afgekapt voor hij de rand raakt";
        BossBar bar = Bukkit.createBossBar(HudScale.fit(longBar, 330, 120), BarColor.YELLOW, BarStyle.SOLID);
        bar.addPlayer(p);

        final String[] titles = {"§6§lKORT", "§6§lMIDDELLANG", "§6§lEEN HEEL LANGE TITEL"};
        final String longAction = "§eACTIONBAR-TEST §7— §fdit is een lange actionbar-tekst om te zien of hij in beeld past";

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= SECONDS) {
                    bar.removeAll();
                    bar.setVisible(false);
                    UNTIL.remove(p.getUniqueId());
                    try { plugin.getScoreboardManager().forceRefreshPlayer(p); } catch (Throwable ignored) { }
                    cancel();
                    return;
                }
                bar.setProgress(Math.max(0.0, 1.0 - t / (double) SECONDS));

                if (t % 2 == 0) p.sendActionBar(LEGACY.deserialize(longAction));

                if (t % 5 == 0 && t / 5 < titles.length) {
                    int idx = t / 5;
                    int px = ChatLayout.px(titles[idx]);
                    // A title is drawn four times as large as normal text: it fits when 4 * px * GUI scale <= window width.
                    int maxScale = Math.max(1, 480 / Math.max(1, px));
                    Component sub = LEGACY.deserialize(lm.tr(p, "platform.hud.test-title", idx + 1, titles.length, px, maxScale));
                    p.showTitle(Title.title(LEGACY.deserialize(titles[idx]), sub,
                            Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(4), Duration.ofMillis(300))));
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}
