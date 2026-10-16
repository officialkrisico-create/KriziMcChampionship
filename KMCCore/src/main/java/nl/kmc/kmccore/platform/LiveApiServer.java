package nl.kmc.kmccore.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.scoring.PointsLedger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Read-only live data for stream overlays and dashboards (OBS browser source).
 *
 * <ul>
 *   <li>{@code GET /api/state} — JSON: round, multiplier, current/next game, team and player standings,
 *       live game points per team, recent clip markers</li>
 *   <li>{@code GET /overlay} — a ready-made overlay page that polls the JSON</li>
 * </ul>
 *
 * <p>Off by default ({@code live-api.enabled}). Binds to 127.0.0.1 unless {@code live-api.bind} says otherwise;
 * set {@code live-api.token} to require {@code ?token=...}. The JSON is built on the main thread every second
 * and only a cached string is served from the HTTP thread.
 */
public final class LiveApiServer {

    private final KMCCore plugin;
    private HttpServer server;
    private BukkitTask refresher;
    private volatile String cached = "{}";
    private volatile String lastError = "";

    public LiveApiServer(KMCCore plugin) { this.plugin = plugin; }

    public boolean isRunning() { return server != null; }

    /** Why the last start failed (empty when it did not). */
    public String lastError() { return lastError; }

    public void start() {
        var cfg = plugin.getConfig();
        lastError = "";
        if (!cfg.getBoolean("live-api.enabled", false)) { lastError = "live-api.enabled staat uit"; return; }
        if (server != null) return;
        int port = cfg.getInt("live-api.port", 8765);
        String bind = cfg.getString("live-api.bind", "127.0.0.1");
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/api/state", this::handleState);
            server.createContext("/overlay", this::handleOverlay);
            server.createContext("/", ex -> {
                ex.getResponseHeaders().add("Location", "/overlay" + (ex.getRequestURI().getQuery() != null
                        ? "?" + ex.getRequestURI().getQuery() : ""));
                ex.sendResponseHeaders(302, -1);
                ex.close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "KMC-LiveApi");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            refresher = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, 20L);
            plugin.getLogger().info("[LiveApi] Listening on http://" + bind + ":" + port + "/overlay");
        } catch (IOException | RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            plugin.getLogger().warning("[LiveApi] Could not start on " + bind + ":" + port + " — " + e.getMessage());
            server = null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().warning("[LiveApi] Could not start: " + t);
            server = null;
        }
    }

    public void stop() {
        if (refresher != null) { refresher.cancel(); refresher = null; }
        if (server != null) { server.stop(0); server = null; }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private boolean authorised(HttpExchange ex) {
        String token = plugin.getConfig().getString("live-api.token", "");
        if (token == null || token.isBlank()) return true;
        String q = ex.getRequestURI().getQuery();
        if (q != null) for (String part : q.split("&")) if (part.equals("token=" + token)) return true;
        return token.equals(ex.getRequestHeaders().getFirst("X-Token"));
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!authorised(ex)) { send(ex, 401, "text/plain", "unauthorised"); return; }
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        send(ex, 200, "application/json; charset=utf-8", cached);
    }

    private void handleOverlay(HttpExchange ex) throws IOException {
        if (!authorised(ex)) { send(ex, 401, "text/plain", "unauthorised"); return; }
        try (InputStream in = plugin.getResource("overlay.html")) {
            if (in == null) { send(ex, 404, "text/plain", "overlay.html missing"); return; }
            send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    // ── Snapshot (main thread) ────────────────────────────────────────────────

    private void refresh() {
        try { cached = buildJson(); }
        catch (Throwable t) { plugin.getLogger().warning("[LiveApi] snapshot failed: " + t); }
    }

    String buildJson() {
        var gm = plugin.getGameManager();
        var tm = plugin.getTournamentManager();
        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        kv(sb, "time", System.currentTimeMillis()).append(',');
        kv(sb, "active", tm.isActive()).append(',');
        kv(sb, "round", tm.getCurrentRound()).append(',');
        kv(sb, "totalRounds", tm.getTotalRounds()).append(',');
        kv(sb, "multiplier", tm.getMultiplier()).append(',');
        kv(sb, "state", plugin.getAutomationManager().getState().name()).append(',');
        kv(sb, "countdown", plugin.getAutomationManager().getCountdownSeconds()).append(',');
        kv(sb, "online", Bukkit.getOnlinePlayers().size()).append(',');

        KMCGame active = gm.getActiveGame();
        sb.append("\"game\":");
        if (active == null) sb.append("null"); else game(sb, active, false);
        sb.append(",\"next\":");
        KMCGame next = gm.getNextGame();
        if (next == null) sb.append("null"); else game(sb, next, gm.isSurpriseHidden(next.getId()));
        sb.append(',');

        // live game points per team (current game only)
        Map<String, Integer> gameTeam = new LinkedHashMap<>();
        PointsLedger ledger = plugin.getPointsLedger();
        if (active != null && ledger != null) {
            gameTeam = PointsLedger.sortedDesc(PointsLedger.byTeam(ledger.forGame(ledger.lastGameSeq())));
        }

        sb.append("\"teams\":[");
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints();
        for (int i = 0; i < teams.size(); i++) {
            KMCTeam t = teams.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            kv(sb, "rank", i + 1).append(',');
            kv(sb, "id", t.getId()).append(',');
            kv(sb, "name", t.getDisplayName()).append(',');
            kv(sb, "color", hex(t.getColor())).append(',');
            kv(sb, "points", t.getPoints()).append(',');
            kv(sb, "gamePoints", gameTeam.getOrDefault(t.getId(), 0)).append(',');
            sb.append("\"members\":[");
            boolean first = true;
            for (UUID id : t.getMembers()) {
                var op = Bukkit.getOfflinePlayer(id);
                if (!first) sb.append(',');
                first = false;
                sb.append(q(op.getName() != null ? op.getName() : id.toString().substring(0, 8)));
            }
            sb.append("]}");
        }
        sb.append("],\"players\":[");
        List<PlayerData> board = plugin.getPlayerDataManager().getLeaderboard();
        for (int i = 0; i < Math.min(10, board.size()); i++) {
            PlayerData pd = board.get(i);
            if (i > 0) sb.append(',');
            var team = plugin.getTeamManager().getTeamByPlayer(pd.getUuid());
            sb.append('{');
            kv(sb, "rank", i + 1).append(',');
            kv(sb, "name", pd.getName()).append(',');
            kv(sb, "team", team != null ? team.getDisplayName() : "").append(',');
            kv(sb, "points", pd.getPoints());
            sb.append('}');
        }
        sb.append("],\"events\":[");
        var clips = plugin.getClipMarkers();
        if (clips != null) {
            List<ClipMarkers.Marker> recent = clips.recent(8);
            for (int i = 0; i < recent.size(); i++) {
                ClipMarkers.Marker m = recent.get(i);
                if (i > 0) sb.append(',');
                sb.append('{');
                kv(sb, "time", m.timecode()).append(',');
                kv(sb, "category", m.category()).append(',');
                kv(sb, "player", m.player()).append(',');
                kv(sb, "label", strip(m.label()));
                sb.append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private void game(StringBuilder sb, KMCGame g, boolean hidden) {
        sb.append('{');
        kv(sb, "id", hidden ? "?" : g.getId()).append(',');
        kv(sb, "name", hidden ? "???" : strip(g.getDisplayName()));
        sb.append('}');
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static StringBuilder kv(StringBuilder sb, String k, Object v) {
        sb.append('"').append(k).append("\":");
        if (v instanceof Number || v instanceof Boolean) sb.append(v);
        else sb.append(q(String.valueOf(v)));
        return sb;
    }

    static String q(String s) {
        StringBuilder o = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> o.append("\\\"");
                case '\\' -> o.append("\\\\");
                case '\n' -> o.append("\\n");
                case '\r' -> o.append("\\r");
                case '\t' -> o.append("\\t");
                default -> { if (c < 0x20) o.append(String.format("\\u%04x", (int) c)); else o.append(c); }
            }
        }
        return o.append('"').toString();
    }

    private static String strip(String s) { return s == null ? "" : ChatColor.stripColor(s.replace('&', '§')); }

    static String hex(ChatColor c) {
        if (c == null) return "#FFFFFF";
        return switch (c) {
            case BLACK -> "#000000"; case DARK_BLUE -> "#0000AA"; case DARK_GREEN -> "#00AA00";
            case DARK_AQUA -> "#00AAAA"; case DARK_RED -> "#AA0000"; case DARK_PURPLE -> "#AA00AA";
            case GOLD -> "#FFAA00"; case GRAY -> "#AAAAAA"; case DARK_GRAY -> "#555555";
            case BLUE -> "#5555FF"; case GREEN -> "#55FF55"; case AQUA -> "#55FFFF";
            case RED -> "#FF5555"; case LIGHT_PURPLE -> "#FF55FF"; case YELLOW -> "#FFFF55";
            default -> "#FFFFFF";
        };
    }
}
