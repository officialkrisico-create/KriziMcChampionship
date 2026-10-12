package nl.kmc.kmccore.lobbygames;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Lobby spleef: a floor of blocks (set with {@code /lobbygames spleef pos1|pos2}); players join with
 * {@code /spleef}, get a shovel and break the floor under each other with left-clicks. Fall through = out.
 * Last one standing wins. The floor is rebuilt before every match. No tournament points.
 */
public final class LobbySpleef {

    private enum State { IDLE, COUNTDOWN, RUNNING }

    private final KMCCore plugin;
    private final LobbyGamesManager mgr;

    private final Set<UUID> queued = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Long> lastBreak = new HashMap<>();
    private State state = State.IDLE;
    private BukkitTask task;
    private int countdown;

    private Location pos1, pos2;
    private Material floorMaterial = Material.SNOW_BLOCK;

    public LobbySpleef(KMCCore plugin, LobbyGamesManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        load();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void load() {
        YamlConfiguration d = mgr.data();
        pos1 = LobbyParkour.readLoc(d.getString("spleef.pos1"));
        pos2 = LobbyParkour.readLoc(d.getString("spleef.pos2"));
        Material m = Material.matchMaterial(d.getString("spleef.material", "SNOW_BLOCK"));
        floorMaterial = m != null && m.isBlock() ? m : Material.SNOW_BLOCK;
    }

    private void saveSetup() {
        YamlConfiguration d = mgr.data();
        d.set("spleef.pos1", LobbyParkour.writeLoc(pos1));
        d.set("spleef.pos2", LobbyParkour.writeLoc(pos2));
        d.set("spleef.material", floorMaterial.name());
        mgr.saveData();
    }

    public void setPos1(Location l) { pos1 = l.getBlock().getLocation(); saveSetup(); }
    public void setPos2(Location l) { pos2 = l.getBlock().getLocation(); saveSetup(); }
    public boolean setMaterial(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isBlock()) return false;
        floorMaterial = m;
        saveSetup();
        return true;
    }
    public boolean isReady() {
        return pos1 != null && pos2 != null && pos1.getWorld() == pos2.getWorld();
    }
    public boolean isRunning() { return state != State.IDLE; }

    // ── Joining ───────────────────────────────────────────────────────────────

    public void join(Player p) {
        var lm = plugin.getLanguageManager();
        if (!isReady()) { p.sendMessage(lm.tr(p, "lobbygames.spleef.not-ready")); return; }
        if (!mgr.checkAvailable(p)) return;
        if (state == State.RUNNING) { p.sendMessage(lm.tr(p, "lobbygames.spleef.running")); return; }
        if (!queued.add(p.getUniqueId())) {
            queued.remove(p.getUniqueId());
            p.sendMessage(lm.tr(p, "lobbygames.spleef.left"));
            return;
        }
        for (UUID id : queued) {
            Player q = Bukkit.getPlayer(id);
            if (q != null) q.sendMessage(lm.tr(q, "lobbygames.spleef.joined", p.getName(), queued.size()));
        }
        if (queued.size() >= 2 && state == State.IDLE) startCountdown();
    }

    private void startCountdown() {
        state = State.COUNTDOWN;
        countdown = Math.max(5, plugin.getConfig().getInt("lobby-games.spleef.countdown-seconds", 15));
        rebuildFloor();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            queued.removeIf(id -> Bukkit.getPlayer(id) == null);
            if (queued.size() < 2) { abort(true); return; }
            if (countdown <= 0) { begin(); return; }
            if (countdown <= 5 || countdown % 5 == 0) {
                for (UUID id : queued) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(net.kyori.adventure.text.Component.text(
                            plugin.getLanguageManager().tr(p, "lobbygames.spleef.countdown", countdown)));
                }
            }
            countdown--;
        }, 20L, 20L);
    }

    private void begin() {
        if (task != null) { task.cancel(); task = null; }
        state = State.RUNNING;
        alive.clear();
        Floor floor = bounds();
        Random rnd = new Random();
        var lm = plugin.getLanguageManager();
        for (UUID id : queued) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            alive.add(id);
            double x = floor.minX + 1 + rnd.nextDouble() * Math.max(1, floor.maxX - floor.minX - 1) + 0.5;
            double z = floor.minZ + 1 + rnd.nextDouble() * Math.max(1, floor.maxZ - floor.minZ - 1) + 0.5;
            p.teleport(new Location(pos1.getWorld(), x, floor.topY + 1, z));
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().setItem(0, shovel(p));
            p.sendTitle(lm.tr(p, "lobbygames.spleef.go"), "", 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
        }
        queued.clear();
        if (alive.size() < 2) { abort(false); return; }

        // Fall check: below the floor = out.
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : new ArrayList<>(alive)) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) { out(id, null); continue; }
                if (p.getLocation().getY() < floor.minY - 1.5) out(id, p);
            }
            checkWinner();
        }, 5L, 5L);
    }

    private void out(UUID id, Player p) {
        if (!alive.remove(id)) return;
        if (p != null) {
            var lm = plugin.getLanguageManager();
            p.sendMessage(lm.tr(p, "lobbygames.spleef.out", alive.size()));
            p.getInventory().clear();
            returnToLobby(p);
        }
        checkWinner();
    }

    private void checkWinner() {
        if (state != State.RUNNING || alive.size() > 1) return;
        UUID winner = alive.isEmpty() ? null : alive.iterator().next();
        if (task != null) { task.cancel(); task = null; }
        state = State.IDLE;
        var lm = plugin.getLanguageManager();
        if (winner != null) {
            Player w = Bukkit.getPlayer(winner);
            String name = w != null ? w.getName() : "?";
            YamlConfiguration d = mgr.data();
            d.set("spleef.wins." + winner, d.getInt("spleef.wins." + winner) + 1);
            mgr.recordName(winner, name);
            mgr.saveData();
            for (Player v : Bukkit.getOnlinePlayers())
                v.sendMessage(lm.tr(v, "lobbygames.spleef.winner", name, d.getInt("spleef.wins." + winner)));
            if (w != null) {
                w.getInventory().clear();
                w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> returnToLobby(w), 60L);
            }
        }
        alive.clear();
        Bukkit.getScheduler().runTaskLater(plugin, this::rebuildFloor, 80L);
    }

    private void abort(boolean refund) {
        if (task != null) { task.cancel(); task = null; }
        state = State.IDLE;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) { p.getInventory().clear(); returnToLobby(p); }
        }
        alive.clear();
        if (refund) {
            for (UUID id : queued) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(plugin.getLanguageManager().tr(p, "lobbygames.spleef.cancelled"));
            }
        }
        queued.clear();
    }

    public void shutdown() { abort(false); }

    /** A participant left the server or the lobby. */
    public void onQuit(Player p) {
        queued.remove(p.getUniqueId());
        if (alive.contains(p.getUniqueId())) { out(p.getUniqueId(), null); }
    }

    // ── Breaking ──────────────────────────────────────────────────────────────

    /** Left-click on a block: breaks it instantly when it is part of the floor and the player is in the match. */
    public boolean tryBreak(Player p, org.bukkit.block.Block b) {
        if (state != State.RUNNING || !alive.contains(p.getUniqueId()) || !isReady()) return false;
        Floor f = bounds();
        if (b.getWorld() != pos1.getWorld() || b.getX() < f.minX || b.getX() > f.maxX || b.getZ() < f.minZ
                || b.getZ() > f.maxZ || b.getY() < f.minY || b.getY() > f.topY) return false;
        if (b.getType() != floorMaterial) return false;
        long now = System.currentTimeMillis();
        if (now - lastBreak.getOrDefault(p.getUniqueId(), 0L) < 150) return true;
        lastBreak.put(p.getUniqueId(), now);
        b.setType(Material.AIR, false);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_SNOW_BREAK, 0.6f, 1.2f);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record Floor(int minX, int maxX, int minY, int topY, int minZ, int maxZ) {}

    private Floor bounds() {
        return new Floor(Math.min(pos1.getBlockX(), pos2.getBlockX()), Math.max(pos1.getBlockX(), pos2.getBlockX()),
                Math.min(pos1.getBlockY(), pos2.getBlockY()), Math.max(pos1.getBlockY(), pos2.getBlockY()),
                Math.min(pos1.getBlockZ(), pos2.getBlockZ()), Math.max(pos1.getBlockZ(), pos2.getBlockZ()));
    }

    /** Refills the whole floor box with the floor material. */
    private void rebuildFloor() {
        if (!isReady()) return;
        Floor f = bounds();
        var w = pos1.getWorld();
        int blocks = (f.maxX - f.minX + 1) * (f.topY - f.minY + 1) * (f.maxZ - f.minZ + 1);
        if (blocks > 20000) {
            plugin.getLogger().warning("[LobbySpleef] Floor too large (" + blocks + " blocks) — not rebuilt.");
            return;
        }
        for (int x = f.minX; x <= f.maxX; x++)
            for (int y = f.minY; y <= f.topY; y++)
                for (int z = f.minZ; z <= f.maxZ; z++)
                    w.getBlockAt(x, y, z).setType(floorMaterial, false);
    }

    private ItemStack shovel(Player p) {
        ItemStack s = new ItemStack(Material.IRON_SHOVEL);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(plugin.getLanguageManager().tr(p, "lobbygames.spleef.shovel"));
        m.addEnchant(Enchantment.EFFICIENCY, 5, true);
        m.setUnbreakable(true);
        s.setItemMeta(m);
        return s;
    }

    private void returnToLobby(Player p) {
        Location lobby = plugin.getArenaManager().getLobby();
        if (lobby != null && p.isOnline()) p.teleport(lobby);
    }
}
