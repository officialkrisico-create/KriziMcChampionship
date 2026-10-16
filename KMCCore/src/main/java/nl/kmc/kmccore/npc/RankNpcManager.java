package nl.kmc.kmccore.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * The live player ranking on NPCs: place a mannequin at slot 1…10 ({@code /kmcnpc rank set <1-10>}) and it
 * wears the skin of whoever is at that rank, with the place, the points and the team above its head —
 * updated every few seconds. One extra display ({@code /kmcnpc rank own}) shows <em>each viewer</em> their
 * own position (other players do not see it).
 */
public final class RankNpcManager implements Listener {

    public static final int MAX_SLOT = 10;

    private final KMCCore plugin;
    private final File file;
    private final Map<Integer, Location> slots = new TreeMap<>();
    private Location ownSpot;

    private final Map<Integer, Mannequin> bodies = new HashMap<>();
    private final Map<Integer, TextDisplay> labels = new HashMap<>();
    private final Map<Integer, UUID> shown = new HashMap<>();
    private final Map<UUID, TextDisplay> ownLabels = new HashMap<>();
    private BukkitTask task;

    public RankNpcManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rank-npcs.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 60L, 100L);
        Bukkit.getScheduler().runTaskLater(plugin, this::sweepOrphans, 20L);   // leftovers of a reload or crash
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public void setSlot(int rank, Location where) {
        if (rank < 1 || rank > MAX_SLOT) return;
        removeSlotEntities(rank);
        slots.put(rank, where.clone());
        save();
        refresh();
    }

    public boolean removeSlot(int rank) {
        if (slots.remove(rank) == null) return false;
        removeSlotEntities(rank);
        save();
        return true;
    }

    public void setOwnSpot(Location where) {
        clearOwn();
        ownSpot = where.clone();
        save();
        refresh();
    }

    public boolean removeOwnSpot() {
        if (ownSpot == null) return false;
        clearOwn();
        ownSpot = null;
        save();
        return true;
    }

    public void removeAllSlots() {
        for (int r : new ArrayList<>(slots.keySet())) removeSlotEntities(r);
        slots.clear();
        removeOwnSpot();
        save();
        sweepOrphans();
    }

    public Map<Integer, Location> getSlots() { return Collections.unmodifiableMap(slots); }
    public Location getOwnSpot() { return ownSpot; }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Brings every slot in line with the current ranking. Safe to call at any time. */
    public void refresh() {
        List<PlayerData> board = plugin.getPlayerDataManager().getLeaderboard();
        for (var e : slots.entrySet()) {
            int rank = e.getKey();
            Location at = e.getValue();
            if (at.getWorld() == null || !at.getChunk().isLoaded()) continue;
            PlayerData pd = rank <= board.size() ? board.get(rank - 1) : null;
            try { renderSlot(rank, at, pd); }
            catch (Throwable t) { plugin.getLogger().fine("rank npc " + rank + ": " + t); }
        }
        refreshOwn(board);
    }

    private void renderSlot(int rank, Location at, PlayerData pd) {
        Mannequin body = bodies.get(rank);
        TextDisplay label = labels.get(rank);
        if (body == null || !body.isValid()) {
            body = at.getWorld().spawn(at, Mannequin.class, m -> {
                m.setImmovable(true);
                m.setGravity(false);
                m.setInvulnerable(true);
                m.setSilent(true);
                m.setPersistent(false);
                m.setCustomNameVisible(false);
                m.setDescription(null);
                m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "rank_npc"),
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            });
            bodies.put(rank, body);
            shown.remove(rank);
        }
        if (label == null || !label.isValid()) {
            label = at.getWorld().spawn(at.clone().add(0, 2.35, 0), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setSeeThrough(false);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                d.getPersistentDataContainer().set(tagKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            });
            labels.put(rank, label);
        }

        UUID who = pd != null ? pd.getUuid() : null;
        if (!Objects.equals(shown.get(rank), who)) {
            if (pd != null) applySkin(body, pd);
            if (who != null) shown.put(rank, who); else shown.remove(rank);
        }

        String text;
        if (pd == null) {
            text = "§f§l" + rank + ".\n§8—";
        } else {
            KMCTeam team = plugin.getTeamManager().getTeamByPlayer(pd.getUuid());
            String teamPart = team != null ? team.getColor() + "◆ " + team.getColor() + pd.getName() : "§f" + pd.getName();
            text = "§f§l" + rank + ".\n§e" + pd.getPoints() + " §6●\n" + teamPart;
        }
        label.text(legacy(text));
    }

    /**
     * Puts the skin of {@code pd} on the body. An online player's own profile already carries the textures;
     * for an offline player only the name is given, so the server looks the skin up (a profile with a uuid and
     * a name but no textures counted as complete and stayed on the default skin).
     */
    private void applySkin(Mannequin body, PlayerData pd) {
        try {
            Player online = Bukkit.getPlayer(pd.getUuid());
            if (online != null) {
                body.setProfile(ResolvableProfile.resolvableProfile(online.getPlayerProfile()));
            } else {
                body.setProfile(ResolvableProfile.resolvableProfile().name(trim16(pd.getName())).build());
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("rank npc skin " + pd.getName() + ": " + t);
        }
    }

    private void refreshOwn(List<PlayerData> board) {
        if (ownSpot == null || ownSpot.getWorld() == null) { clearOwn(); return; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            TextDisplay d = ownLabels.get(p.getUniqueId());
            if (d == null || !d.isValid()) {
                d = ownSpot.getWorld().spawn(ownSpot, TextDisplay.class, t -> {
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setPersistent(false);
                    t.setShadowed(true);
                    t.setVisibleByDefault(false);          // only its owner is shown it
                    t.setDefaultBackground(false);
                    t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                    t.getPersistentDataContainer().set(tagKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                });
                ownLabels.put(p.getUniqueId(), d);
                p.showEntity(plugin, d);
            }
            int rank = 0, pts = 0, gap = -1;
            for (int i = 0; i < board.size(); i++) {
                if (board.get(i).getUuid().equals(p.getUniqueId())) {
                    rank = i + 1;
                    pts = board.get(i).getPoints();
                    if (i > 0) gap = board.get(i - 1).getPoints() - pts;
                    break;
                }
            }
            var lm = plugin.getLanguageManager();
            String text = rank == 0
                    ? lm.tr(p, "ranknpc.own-none")
                    : lm.tr(p, "ranknpc.own", rank, board.size(), pts)
                      + (gap >= 0 ? "\n" + lm.tr(p, "ranknpc.own-gap", gap, rank - 1) : "");
            d.text(legacy(text));
        }
    }

    // ── Removal helpers (orphans, "the one next to me") ───────────────────────

    private org.bukkit.NamespacedKey tagKey() { return new org.bukkit.NamespacedKey(plugin, "rank_npc"); }

    private boolean tracked(UUID id) {
        for (Mannequin b : bodies.values()) if (b != null && b.getUniqueId().equals(id)) return true;
        for (TextDisplay l : labels.values()) if (l != null && l.getUniqueId().equals(id)) return true;
        for (TextDisplay l : ownLabels.values()) if (l != null && l.getUniqueId().equals(id)) return true;
        return false;
    }

    /**
     * Removes rank-NPC entities that the manager no longer knows about (left behind by a reload, a crash
     * or a failed removal). Everything it spawns carries a tag, so nothing else is touched.
     *
     * @return how many entities were removed
     */
    public int sweepOrphans() {
        int n = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClasses(Mannequin.class, TextDisplay.class)) {
                if (!e.getPersistentDataContainer().has(tagKey(), org.bukkit.persistence.PersistentDataType.BYTE)) continue;
                if (tracked(e.getUniqueId())) continue;
                e.remove();
                n++;
            }
        }
        return n;
    }

    /**
     * Removes the rank NPC (or the "own position" display) within {@code radius} blocks of {@code at}, plus
     * any tagged entity there that was left behind. Returns the number of things removed.
     */
    public int removeNear(Location at, double radius) {
        int n = 0;
        for (var e : new ArrayList<>(slots.entrySet())) {
            Location l = e.getValue();
            if (l.getWorld() != null && l.getWorld().equals(at.getWorld()) && l.distanceSquared(at) <= radius * radius) {
                if (removeSlot(e.getKey())) n++;
            }
        }
        if (ownSpot != null && ownSpot.getWorld() != null && ownSpot.getWorld().equals(at.getWorld())
                && ownSpot.distanceSquared(at) <= radius * radius) {
            if (removeOwnSpot()) n++;
        }
        if (at.getWorld() != null) {
            for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius + 3, radius)) {
                if (!(e instanceof Mannequin) && !(e instanceof TextDisplay)) continue;
                if (!e.getPersistentDataContainer().has(tagKey(), org.bukkit.persistence.PersistentDataType.BYTE)) continue;
                e.remove();
                n++;
            }
        }
        return n;
    }

    /** The slot numbers that exist, in order. */
    public List<Integer> slotNumbers() { return new ArrayList<>(slots.keySet()); }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void removeSlotEntities(int rank) {
        Mannequin b = bodies.remove(rank);
        if (b != null) b.remove();
        TextDisplay l = labels.remove(rank);
        if (l != null) l.remove();
        shown.remove(rank);
    }

    private void clearOwn() {
        for (TextDisplay d : ownLabels.values()) if (d != null) d.remove();
        ownLabels.clear();
    }

    /** Removes every spawned entity (plugin disable). */
    public void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        for (int r : new ArrayList<>(bodies.keySet())) removeSlotEntities(r);
        for (Entity e : new ArrayList<Entity>(labels.values())) e.remove();
        labels.clear();
        clearOwn();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        TextDisplay d = ownLabels.remove(e.getPlayer().getUniqueId());
        if (d != null) d.remove();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 40L);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("slots");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    Location l = sec.getLocation(k);
                    if (l != null) slots.put(Integer.parseInt(k), l);
                } catch (NumberFormatException ignored) { }
            }
        }
        ownSpot = y.getLocation("own");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : slots.entrySet()) y.set("slots." + e.getKey(), e.getValue());
        if (ownSpot != null) y.set("own", ownSpot);
        try { y.save(file); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save rank-npcs.yml: " + ex.getMessage()); }
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    private static String trim16(String name) {
        return name == null ? null : (name.length() > 16 ? name.substring(0, 16) : name);
    }
}
