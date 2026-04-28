package nl.kmc.kmccore.discord;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.health.HealthMonitor;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.Bukkit;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Discord webhook integration.
 *
 * <p>Posts tournament events to a Discord channel via a webhook URL.
 * No external dependencies — uses {@link HttpURLConnection}.
 *
 * <p>Configure in KMCCore config.yml:
 * <pre>
 *   discord:
 *     enabled: false
 *     webhook-url: "https://discord.com/api/webhooks/..."
 *     username: "KMC Tournament"
 *     avatar-url: "https://example.com/icon.png"
 *     post:
 *       game-start: true
 *       game-end: true
 *       round-start: true
 *       tournament-start: true
 *       tournament-end: true
 *       health-alerts: true     # warnings/criticals only
 * </pre>
 *
 * <p>All HTTP calls are async — never blocks the main thread.
 */
public class DiscordWebhook {

    private final KMCCore plugin;

    public DiscordWebhook(KMCCore plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("discord.enabled", false)
            && getWebhookUrl() != null;
    }

    private String getWebhookUrl() {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        return url == null || url.isBlank() ? null : url;
    }

    // ----------------------------------------------------------------
    // Public API — high-level event posters
    // ----------------------------------------------------------------

    public void postGameStart(String gameId, String displayName, int round) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("discord.post.game-start", true)) return;
        String content = String.format("🎮 **Round %d** — %s starting!", round, displayName);
        postEmbed("Game Start", content, 0x3498db);
    }

    public void postGameEnd(String gameId, String displayName, String winner) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("discord.post.game-end", true)) return;
        String content = winner != null
                ? String.format("🏆 **%s won** %s!", winner, displayName)
                : String.format("⏹ %s ended (no winner)", displayName);
        postEmbed("Game Result", content, 0x2ecc71);
    }

    public void postRoundStart(int round, int total) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("discord.post.round-start", true)) return;
        String content = String.format("⚡ **Round %d/%d** begins!", round, total);
        postEmbed("Round Start", content, 0xf1c40f);
    }

    public void postTournamentStart() {
        if (!isEnabled() || !plugin.getConfig().getBoolean("discord.post.tournament-start", true)) return;
        int onlineCount = Bukkit.getOnlinePlayers().size();
        String content = String.format("🎉 **The tournament has started!** (%d players online)", onlineCount);
        postEmbed("Tournament", content, 0x9b59b6);
    }

    public void postTournamentEnd() {
        if (!isEnabled() || !plugin.getConfig().getBoolean("discord.post.tournament-end", true)) return;
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints();
        StringBuilder sb = new StringBuilder("🏆 **Tournament finished!**\n\n**Final Standings:**\n");
        String[] medals = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < Math.min(5, teams.size()); i++) {
            KMCTeam t = teams.get(i);
            String prefix = i < medals.length ? medals[i] : "#" + (i + 1);
            sb.append(prefix).append(" **").append(t.getDisplayName()).append("** — ")
              .append(t.getPoints()).append(" points\n");
        }
        postEmbed("Tournament Result", sb.toString(), 0xe91e63);
    }

    public void postHealthAlert(HealthMonitor.Severity sev, String code, String message) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("discord.post.health-alerts", true)) return;
        if (sev == HealthMonitor.Severity.INFO) return;  // skip info events
        String emoji = sev == HealthMonitor.Severity.CRITICAL ? "🚨" : "⚠️";
        int color = sev == HealthMonitor.Severity.CRITICAL ? 0xe74c3c : 0xf39c12;
        postEmbed("Health Alert: " + code, emoji + " " + message, color);
    }

    public void postCustom(String title, String content, int colorRgb) {
        if (!isEnabled()) return;
        postEmbed(title, content, colorRgb);
    }

    // ----------------------------------------------------------------
    // Low-level HTTP poster (async)
    // ----------------------------------------------------------------

    private void postEmbed(String title, String description, int color) {
        String url = getWebhookUrl();
        if (url == null) return;

        String username = plugin.getConfig().getString("discord.username", "KMC Tournament");
        String avatarUrl = plugin.getConfig().getString("discord.avatar-url", "");

        // Build JSON manually — no JSON lib dep
        String json = "{"
                + "\"username\":" + jsonString(username) + ","
                + (avatarUrl.isBlank() ? "" : "\"avatar_url\":" + jsonString(avatarUrl) + ",")
                + "\"embeds\":[{"
                + "\"title\":" + jsonString(title) + ","
                + "\"description\":" + jsonString(description) + ","
                + "\"color\":" + color + ","
                + "\"timestamp\":" + jsonString(java.time.Instant.now().toString())
                + "}]"
                + "}";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sendWebhook(url, json));
    }

    private void sendWebhook(String url, String json) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "KMCTournament/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                plugin.getLogger().warning("Discord webhook returned HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
        }
    }

    /** Properly escape a string as a JSON literal. */
    private String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
