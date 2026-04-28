package nl.kmc.kmccore.announce;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * Broadcasts a generic Dutch welcome message when the tournament starts.
 *
 * <p>Hooked into {@link nl.kmc.kmccore.api.KMCApi#onTournamentStart(Runnable)}
 * by KMCCore at plugin enable. Players see a multi-line chat broadcast
 * with: welkom, hoe punten werken, hoe teams werken, hoe rondes werken.
 *
 * <p>Customisable via {@code messages.yml} keys under
 * {@code tournament-welcome:} (with a sensible default fallback baked in).
 */
public class WelcomeBroadcaster {

    private final KMCCore plugin;

    public WelcomeBroadcaster(KMCCore plugin) {
        this.plugin = plugin;
    }

    /** Call this from KMCCore.onEnable: api.onTournamentStart(welcomeBroadcaster::broadcast). */
    public void broadcast() {
        // Defaults — used if messages.yml doesn't override them.
        String[] defaults = {
                "&6&l========================================",
                "&e&l           Welkom bij de KMC Tournament!",
                "&6&l========================================",
                "",
                "&7Een toernooi met meerdere mini-games waarin",
                "&7teams het tegen elkaar opnemen voor de hoogste score.",
                "",
                "&e&lHoe het werkt:",
                "&7• Elke ronde wordt een nieuwe game gekozen (door stemming of random)",
                "&7• Speel zo goed mogelijk om punten te verdienen",
                "&7• Persoonlijke punten tellen ook mee voor je team",
                "&7• In latere rondes worden punten vermenigvuldigd!",
                "",
                "&e&lTeams:",
                "&7• Iedereen zit in een team van max 4 spelers",
                "&7• Teamleden zien elkaar gekleurd in de tab + chat",
                "&7• Het team met de meeste punten aan het eind wint!",
                "",
                "&e&lCommando's voor jou:",
                "&f/kmcstats &7- bekijk je eigen statistieken",
                "&f/kmclb &7- bekijk de leaderboard",
                "&f/tc &7- praat alleen tegen je team",
                "",
                "&a&lVeel succes en plezier!",
                "&6&l========================================"
        };

        var cfg = plugin.getConfig();
        java.util.List<String> configLines = cfg.getStringList("tournament-welcome.lines");
        final java.util.List<String> lines =
                (configLines == null || configLines.isEmpty())
                        ? java.util.Arrays.asList(defaults)
                        : configLines;

        // Stagger by 1 tick so the chat has time to flush after game-start
        // events. Send ~3 ticks after tournament start.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String raw : lines) {
                String coloured = ChatColor.translateAlternateColorCodes('&', raw);
                Bukkit.broadcastMessage(coloured);
            }
        }, 60L);
    }
}