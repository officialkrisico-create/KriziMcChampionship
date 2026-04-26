package nl.kmc.bridge.managers;

import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.models.BridgeTeam;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Stores Bridge arena: world + per-team spawns/goals + central
 * "play area" for block-placement scoping.
 *
 * <p>Bridge is normally 2 teams (RED vs BLUE) but admins can configure
 * up to 4. Each team has its own spawn, goal region, and wool color.
 *
 * <p>Setup workflow:
 * <pre>
 *   /bridge setworld &lt;world&gt;
 *   /bridge setteam red "Red Team" RED RED_WOOL
 *   /bridge editteam red spawn      ← stand at red team spawn
 *   /bridge editteam red goalpos1   ← inside red's goal hole
 *   /bridge editteam red goalpos2   ← opposite corner
 *   (repeat for blue, etc.)
 *   /bridge setvoidy &lt;y&gt;     ← Y level below which players die
 *   /bridge listteams
 * </pre>
 */
public class ArenaManager {

    private final TheBridgePlugin plugin;

    private World world;
    private final Map<String, BridgeTeam> teams = new LinkedHashMap<>();
    private int voidYLevel;

    private final Map<String, PartialTeam> partials = new HashMap<>();

    public ArenaManager(TheBridgePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        teams.clear();
        var cfg = plugin.getConfig();

        String worldName = cfg.getString("arena.world");
        world = worldName != null ? Bukkit.getWorld(worldName) : null;
        voidYLevel = cfg.getInt("arena.void-y", 0);

        ConfigurationSection ts = cfg.getConfigurationSection("arena.teams");
        if (ts != null && world != null) {
            for (String id : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(id);
                if (t == null) continue;
                String displayName = t.getString("display-name", id);
                ChatColor cc = parseColor(t.getString("chat-color", "WHITE"), ChatColor.WHITE);
                Material wool = parseMaterial(t.getString("wool", "WHITE_WOOL"), Material.WHITE_WOOL);
                Location spawn    = readLoc(t, "spawn", world);
                Location goalPos1 = readLoc(t, "goal-pos1", world);
                Location goalPos2 = readLoc(t, "goal-pos2", world);
                if (spawn == null || goalPos1 == null || goalPos2 == null) {
                    plugin.getLogger().warning("Team " + id + " is incomplete, skipping.");
                    continue;
                }
                teams.put(id, new BridgeTeam(id, displayName, cc, wool, spawn, goalPos1, goalPos2));
            }
        }

        plugin.getLogger().info("Loaded " + teams.size() + " Bridge teams.");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", world != null ? world.getName() : null);
        cfg.set("arena.void-y", voidYLevel);

        cfg.set("arena.teams", null);
        for (BridgeTeam t : teams.values()) {
            String path = "arena.teams." + t.getId();
            cfg.set(path + ".display-name", t.getDisplayName());
            cfg.set(path + ".chat-color", t.getChatColor().name());
            cfg.set(path + ".wool", t.getWoolMaterial().name());
            writeLoc(cfg, path + ".spawn",     t.getSpawn());
            writeLoc(cfg, path + ".goal-pos1", t.getGoalPos1());
            writeLoc(cfg, path + ".goal-pos2", t.getGoalPos2());
        }
        plugin.saveConfig();
    }

    private Location readLoc(ConfigurationSection sec, String key, World world) {
        ConfigurationSection s = sec.getConfigurationSection(key);
        if (s == null) return null;
        return new Location(world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
    }

    private void writeLoc(org.bukkit.configuration.file.FileConfiguration cfg, String path, Location l) {
        if (l == null) return;
        cfg.set(path + ".x", l.getX());
        cfg.set(path + ".y", l.getY());
        cfg.set(path + ".z", l.getZ());
        cfg.set(path + ".yaw", l.getYaw());
        cfg.set(path + ".pitch", l.getPitch());
    }

    private ChatColor parseColor(String s, ChatColor fb) {
        try { return ChatColor.valueOf(s.toUpperCase()); }
        catch (Exception e) { return fb; }
    }
    private Material parseMaterial(String s, Material fb) {
        try { return Material.valueOf(s.toUpperCase()); }
        catch (Exception e) { return fb; }
    }

    // ---- Public accessors --------------------------------------

    public World getWorld()         { return world; }
    public void  setWorld(World w)  { this.world = w; save(); }
    public int   getVoidYLevel()    { return voidYLevel; }
    public void  setVoidYLevel(int y) { this.voidYLevel = y; save(); }

    public Map<String, BridgeTeam> getTeams() { return Collections.unmodifiableMap(teams); }
    public BridgeTeam getTeam(String id)      { return teams.get(id); }

    /** Find the team whose goal region contains this location, or null. */
    public BridgeTeam findGoalTeam(Location loc) {
        for (BridgeTeam t : teams.values()) if (t.isInGoalRegion(loc)) return t;
        return null;
    }

    public BridgeTeam findTeamForPlayer(java.util.UUID uuid) {
        for (BridgeTeam t : teams.values()) if (t.getMembers().contains(uuid)) return t;
        return null;
    }

    // ---- Partial team builder ----------------------------------

    public PartialTeam getPartial(String id) {
        return partials.computeIfAbsent(id, k -> new PartialTeam(id));
    }
    public void clearPartial(String id) { partials.remove(id); }

    public void commitPartial(String id) {
        PartialTeam p = partials.get(id);
        if (p == null || !p.isComplete()) return;
        BridgeTeam team = new BridgeTeam(id, p.displayName, p.chatColor, p.woolMaterial,
                p.spawn, p.goalPos1, p.goalPos2);
        teams.put(id, team);
        partials.remove(id);
        save();
    }

    public void deleteTeam(String id) {
        teams.remove(id);
        partials.remove(id);
        save();
    }

    public static class PartialTeam {
        public final String id;
        public String        displayName;
        public ChatColor     chatColor;
        public Material      woolMaterial;
        public Location      spawn, goalPos1, goalPos2;

        public PartialTeam(String id) { this.id = id; }

        public boolean isComplete() {
            return displayName != null && chatColor != null && woolMaterial != null
                && spawn != null && goalPos1 != null && goalPos2 != null;
        }

        public String missing() {
            if (displayName == null)  return "display-name";
            if (chatColor == null)    return "chat-color";
            if (woolMaterial == null) return "wool-material";
            if (spawn == null)        return "spawn";
            if (goalPos1 == null)     return "goal-pos1";
            if (goalPos2 == null)     return "goal-pos2";
            return null;
        }
    }

    // ---- Validity ---------------------------------------------

    public boolean isReady() {
        return world != null && teams.size() >= 2;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:     ").append(world != null ? "✔ " + world.getName() : "✘").append("\n");
        sb.append("Teams:     ").append(teams.size())
                .append(teams.size() < 2 ? " &c(min 2)" : "").append("\n");
        sb.append("Void Y:    ").append(voidYLevel);
        return sb.toString();
    }
}
