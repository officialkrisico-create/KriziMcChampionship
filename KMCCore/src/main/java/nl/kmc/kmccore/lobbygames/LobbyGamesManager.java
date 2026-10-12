package nl.kmc.kmccore.lobbygames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Small lobby minigames that never touch tournament points: tic-tac-toe duels, darts at target blocks and a
 * fishing contest. They only run while no game is going on and the player is in the lobby zone. Results are
 * kept in {@code lobby-games.yml} (wins, best dart turn, biggest fish).
 */
public final class LobbyGamesManager {

    /** Marks the snowballs from /darts. */
    public final NamespacedKey dartKey;

    private final KMCCore plugin;
    private final File file;
    private YamlConfiguration data;

    private final Map<UUID, TicTacToeMatch> matches = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();   // target -> invite
    private final Map<UUID, int[]> dartTurns = new HashMap<>();  // uuid -> {thrown, total}
    private final List<Location> boards = new ArrayList<>();
    private LobbyParkour parkour;
    private LobbySpleef spleef;

    private record Invite(UUID from, long expiresMs) {}

    public LobbyGamesManager(KMCCore plugin) {
        this.plugin = plugin;
        this.dartKey = new NamespacedKey(plugin, "lobby_dart");
        this.file = new File(plugin.getDataFolder(), "lobby-games.yml");
        load();
        this.parkour = new LobbyParkour(plugin, this);
        this.spleef = new LobbySpleef(plugin, this);
    }

    public LobbyParkour parkour() { return parkour; }
    public LobbySpleef spleef() { return spleef; }
    YamlConfiguration data() { return data; }
    void saveData() { save(); }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        boards.clear();
        for (String s : data.getStringList("darts.boards")) {
            String[] p = s.split(",");
            if (p.length != 4) continue;
            var w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            try { boards.add(new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]))); }
            catch (NumberFormatException ignored) { }
        }
    }

    private void save() {
        try { data.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Could not save lobby-games.yml: " + e.getMessage()); }
    }

    // ── Availability ──────────────────────────────────────────────────────────

    public boolean enabled() { return plugin.getConfig().getBoolean("lobby-games.enabled", true); }

    /** True when a player may play lobby games right now: enabled, no game running, in the lobby zone. */
    public boolean available(Player p) {
        if (!enabled()) return false;
        if (plugin.getGameManager().isGameActive() || nl.kmc.core.RunningGames.anyRunning()) return false;
        Location lobby = plugin.getArenaManager().getLobby();
        if (lobby == null || p.getWorld() != lobby.getWorld()) return false;
        double r = plugin.getConfig().getDouble("arena.lobby-protection-radius", 100.0);
        return p.getLocation().distanceSquared(lobby) <= r * r;
    }

    /** Sends the reason a lobby game is not available (when it is not). Returns true when available. */
    public boolean checkAvailable(Player p) {
        var lm = plugin.getLanguageManager();
        if (!enabled()) { p.sendMessage(lm.tr(p, "lobbygames.disabled")); return false; }
        if (plugin.getGameManager().isGameActive() || nl.kmc.core.RunningGames.anyRunning()) {
            p.sendMessage(lm.tr(p, "lobbygames.busy"));
            return false;
        }
        if (!available(p)) { p.sendMessage(lm.tr(p, "lobbygames.not-in-lobby")); return false; }
        return true;
    }

    // ── Tic-tac-toe ───────────────────────────────────────────────────────────

    public void challenge(Player from, Player to) {
        var lm = plugin.getLanguageManager();
        if (!checkAvailable(from)) return;
        if (from.equals(to)) { from.sendMessage(lm.tr(from, "lobbygames.ttt.self")); return; }
        if (matches.containsKey(from.getUniqueId()) || matches.containsKey(to.getUniqueId())) {
            from.sendMessage(lm.tr(from, "lobbygames.ttt.already"));
            return;
        }
        invites.put(to.getUniqueId(), new Invite(from.getUniqueId(), System.currentTimeMillis() + 30_000L));
        from.sendMessage(lm.tr(from, "lobbygames.ttt.invite-sent", to.getName()));
        Component btn = LegacyComponentSerializer.legacySection().deserialize(lm.tr(to, "lobbygames.ttt.accept-btn"))
                .clickEvent(ClickEvent.runCommand("/ttt accept"));
        to.sendMessage(lm.tr(to, "lobbygames.ttt.invited", from.getName()));
        to.sendMessage(btn);
    }

    public void accept(Player to) {
        var lm = plugin.getLanguageManager();
        Invite inv = invites.remove(to.getUniqueId());
        if (inv == null || System.currentTimeMillis() > inv.expiresMs()) {
            to.sendMessage(lm.tr(to, "lobbygames.ttt.none"));
            return;
        }
        Player from = Bukkit.getPlayer(inv.from());
        if (from == null || !from.isOnline()) { to.sendMessage(lm.tr(to, "lobbygames.ttt.none")); return; }
        if (!checkAvailable(to) || !available(from)) return;
        TicTacToeMatch m = new TicTacToeMatch(this, plugin, from, to);
        matches.put(from.getUniqueId(), m);
        matches.put(to.getUniqueId(), m);
        m.open(from);
        m.open(to);
    }

    public void decline(Player to) {
        Invite inv = invites.remove(to.getUniqueId());
        if (inv == null) return;
        Player from = Bukkit.getPlayer(inv.from());
        if (from != null) from.sendMessage(plugin.getLanguageManager().tr(from, "lobbygames.ttt.declined", to.getName()));
    }

    /** Re-opens the board of the match this player is in; false when there is none. */
    public boolean reopen(Player p) {
        TicTacToeMatch m = matches.get(p.getUniqueId());
        if (m == null) return false;
        m.open(p);
        return true;
    }

    /** The player gives up (command, quit or leaving the lobby). */
    public void forfeit(Player p) {
        TicTacToeMatch m = matches.get(p.getUniqueId());
        if (m != null) m.forfeit(p);
    }

    void matchEnded(TicTacToeMatch m) {
        matches.values().removeIf(x -> x == m);
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    void recordName(UUID id, String name) { data.set("names." + id, name); }

    void tttResult(UUID winner, UUID loser, boolean draw, UUID a, UUID b) {
        for (UUID u : new UUID[] {a, b}) data.set("ttt.played." + u, data.getInt("ttt.played." + u) + 1);
        if (!draw && winner != null) data.set("ttt.wins." + winner, data.getInt("ttt.wins." + winner) + 1);
        save();
    }

    public int tttWins(UUID id) { return data.getInt("ttt.wins." + id); }

    // ── Darts ─────────────────────────────────────────────────────────────────

    public List<Location> getBoards() { return Collections.unmodifiableList(boards); }

    public boolean addBoard(Location l) {
        Location b = l.getBlock().getLocation();
        for (Location x : boards) if (x.equals(b)) return false;
        boards.add(b);
        saveBoards();
        return true;
    }

    public void clearBoards() { boards.clear(); saveBoards(); }

    private void saveBoards() {
        List<String> out = new ArrayList<>();
        for (Location l : boards) out.add(l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
        data.set("darts.boards", out);
        save();
    }

    public boolean isBoard(Location block) {
        if (block.getBlock().getType() == org.bukkit.Material.TARGET
                && plugin.getConfig().getBoolean("lobby-games.darts.any-target-block", true)) return true;
        Location b = block.getBlock().getLocation();
        for (Location x : boards) if (x.equals(b)) return true;
        return false;
    }

    /** Registers one dart. Returns {thrown, total, turnDone(0/1), newBest(0/1), best}. */
    int[] dart(Player p, int score) {
        int dartsPerTurn = Math.max(1, plugin.getConfig().getInt("lobby-games.darts.per-turn", 5));
        int[] t = dartTurns.computeIfAbsent(p.getUniqueId(), k -> new int[2]);
        t[0]++;
        t[1] += score;
        int[] out = {t[0], t[1], 0, 0, data.getInt("darts.best." + p.getUniqueId())};
        if (t[0] >= dartsPerTurn) {
            out[2] = 1;
            int best = out[4];
            if (t[1] > best) {
                out[3] = 1;
                out[4] = t[1];
                data.set("darts.best." + p.getUniqueId(), t[1]);
                recordName(p.getUniqueId(), p.getName());
                save();
            }
            dartTurns.remove(p.getUniqueId());
        }
        return out;
    }

    // ── Fishing ───────────────────────────────────────────────────────────────

    /** Records a catch; returns true when it is the player's personal best. */
    boolean fishCaught(Player p, double kg) {
        String key = "fish.best." + p.getUniqueId();
        recordName(p.getUniqueId(), p.getName());
        data.set("fish.count." + p.getUniqueId(), data.getInt("fish.count." + p.getUniqueId()) + 1);
        boolean best = kg > data.getDouble(key);
        if (best) data.set(key, Math.round(kg * 100) / 100.0);
        save();
        return best;
    }

    public double serverFishRecord() {
        double max = 0;
        ConfigurationSection s = data.getConfigurationSection("fish.best");
        if (s != null) for (String k : s.getKeys(false)) max = Math.max(max, s.getDouble(k));
        return max;
    }

    // ── Leaderboards ──────────────────────────────────────────────────────────

    /** Top entries of "ttt", "darts" or "fish" as {name, value-text}. */
    public List<String[]> top(String game, int limit) {
        String path = switch (game) {
            case "ttt" -> "ttt.wins";
            case "darts" -> "darts.best";
            case "parkour" -> "parkour.best";
            case "spleef" -> "spleef.wins";
            default -> "fish.best";
        };
        ConfigurationSection s = data.getConfigurationSection(path);
        List<String[]> out = new ArrayList<>();
        if (s == null) return out;
        List<String> keys = new ArrayList<>(s.getKeys(false));
        if (game.equals("parkour")) keys.sort((a, b) -> Double.compare(s.getDouble(a), s.getDouble(b)));   // fastest first
        else keys.sort((a, b) -> Double.compare(s.getDouble(b), s.getDouble(a)));
        for (String k : keys) {
            if (out.size() >= limit) break;
            String name = data.getString("names." + k);
            if (name == null) {
                try {
                    var op = Bukkit.getOfflinePlayer(UUID.fromString(k));
                    name = op.getName() != null ? op.getName() : k.substring(0, 8);
                } catch (IllegalArgumentException e) { name = k; }
            }
            String value = game.equals("fish")
                    ? String.format(Locale.ROOT, "%.2f kg", s.getDouble(k))
                    : game.equals("parkour") ? LobbyParkour.fmt((long) s.getDouble(k))
                    : String.valueOf((int) s.getDouble(k));
            out.add(new String[] {name, value});
        }
        return out;
    }

    public void shutdown() {
        try { spleef.shutdown(); } catch (Throwable ignored) { }
        for (TicTacToeMatch m : new HashSet<>(matches.values())) m.abort();
        matches.clear();
    }
}
