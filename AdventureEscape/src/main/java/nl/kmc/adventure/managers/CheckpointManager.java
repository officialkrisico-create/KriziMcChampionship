package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.Checkpoint;
import nl.kmc.adventure.models.OutOfBoundsBox;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Manages checkpoints for the Adventure Escape arena.
 *
 * <p>Checkpoints persist to {@code config.yml} under {@code arena.checkpoints.<name>}:
 * <pre>
 *   arena:
 *     checkpoints:
 *       cp1:
 *         respawn: &lt;Location&gt;
 *         trigger:
 *           pos1: &lt;Location&gt;
 *           pos2: &lt;Location&gt;
 *         oob:
 *           water1:
 *             pos1: &lt;Location&gt;
 *             pos2: &lt;Location&gt;
 * </pre>
 *
 * <p>The manager also tracks per-player "last reached checkpoint" while a
 * race is active, and exposes {@link #getRespawnFor(UUID)} which returns
 * either the player's last CP respawn or the default spawn grid origin.
 */
public class CheckpointManager {

    private final AdventureEscapePlugin plugin;
    private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();

    /** Per-player last reached checkpoint name (null = none yet, use spawn). */
    private final Map<UUID, String> lastCheckpoint = new HashMap<>();

    public CheckpointManager(AdventureEscapePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ----------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------

    public void load() {
        checkpoints.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("arena.checkpoints");
        if (root == null) return;

        for (String cpName : root.getKeys(false)) {
            ConfigurationSection cpSec = root.getConfigurationSection(cpName);
            if (cpSec == null) continue;

            Location respawn = cpSec.getLocation("respawn");
            if (respawn == null) {
                plugin.getLogger().warning("Checkpoint '" + cpName + "' has no respawn — skipping.");
                continue;
            }

            Checkpoint cp = new Checkpoint(cpName, respawn);

            // Optional trigger region
            Location tp1 = cpSec.getLocation("trigger.pos1");
            Location tp2 = cpSec.getLocation("trigger.pos2");
            if (tp1 != null && tp2 != null) cp.setTrigger(tp1, tp2);

            // OOB boxes
            ConfigurationSection oobSec = cpSec.getConfigurationSection("oob");
            if (oobSec != null) {
                for (String oobName : oobSec.getKeys(false)) {
                    Location p1 = oobSec.getLocation(oobName + ".pos1");
                    Location p2 = oobSec.getLocation(oobName + ".pos2");
                    if (p1 != null && p2 != null) {
                        cp.addOobBox(new OutOfBoundsBox(oobName, p1, p2));
                    }
                }
            }

            checkpoints.put(cpName.toLowerCase(), cp);
        }

        plugin.getLogger().info("Loaded " + checkpoints.size() + " checkpoint(s).");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.checkpoints", null);  // wipe and rewrite

        for (Checkpoint cp : checkpoints.values()) {
            String base = "arena.checkpoints." + cp.getName();
            cfg.set(base + ".respawn", cp.getRespawn());
            if (cp.hasTrigger()) {
                cfg.set(base + ".trigger.pos1", cp.getTriggerPos1());
                cfg.set(base + ".trigger.pos2", cp.getTriggerPos2());
            }
            for (OutOfBoundsBox box : cp.getOobBoxes()) {
                String boxBase = base + ".oob." + box.getName();
                cfg.set(boxBase + ".pos1", box.getPos1());
                cfg.set(boxBase + ".pos2", box.getPos2());
            }
        }
        plugin.saveConfig();
    }

    // ----------------------------------------------------------------
    // Checkpoint mutation
    // ----------------------------------------------------------------

    /**
     * Creates or updates a checkpoint with the given respawn location.
     * @return the (new or existing) Checkpoint
     */
    public Checkpoint setOrCreate(String name, Location respawn) {
        String key = name.toLowerCase();
        Checkpoint existing = checkpoints.get(key);
        if (existing != null) {
            // Replace respawn — keep OOB boxes and trigger
            Checkpoint replacement = new Checkpoint(name, respawn);
            if (existing.hasTrigger()) {
                replacement.setTrigger(existing.getTriggerPos1(), existing.getTriggerPos2());
            }
            for (OutOfBoundsBox box : existing.getOobBoxes()) {
                replacement.addOobBox(box);
            }
            checkpoints.put(key, replacement);
            save();
            return replacement;
        }
        Checkpoint cp = new Checkpoint(name, respawn);
        checkpoints.put(key, cp);
        save();
        return cp;
    }

    public boolean remove(String name) {
        boolean removed = checkpoints.remove(name.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    public Checkpoint get(String name) {
        return checkpoints.get(name.toLowerCase());
    }

    public Collection<Checkpoint> getAll() {
        return Collections.unmodifiableCollection(checkpoints.values());
    }

    public boolean addOob(String cpName, String boxName, Location p1, Location p2) {
        Checkpoint cp = get(cpName);
        if (cp == null) return false;
        cp.removeOobBox(boxName);  // overwrite if exists
        cp.addOobBox(new OutOfBoundsBox(boxName, p1, p2));
        save();
        return true;
    }

    public boolean removeOob(String cpName, String boxName) {
        Checkpoint cp = get(cpName);
        if (cp == null) return false;
        boolean removed = cp.removeOobBox(boxName);
        if (removed) save();
        return removed;
    }

    // ----------------------------------------------------------------
    // Per-racer state
    // ----------------------------------------------------------------

    /** Called every tick by RaceManager — checks if any player has entered a CP trigger region. */
    public void tickCheckpointDetection(Set<UUID> activeRacers) {
        for (UUID uuid : activeRacers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            Location loc = p.getLocation();
            for (Checkpoint cp : checkpoints.values()) {
                if (!cp.hasTrigger()) continue;
                if (containsBox(loc, cp.getTriggerPos1(), cp.getTriggerPos2())) {
                    String prev = lastCheckpoint.get(uuid);
                    if (prev == null || !prev.equalsIgnoreCase(cp.getName())) {
                        lastCheckpoint.put(uuid, cp.getName());
                        p.sendMessage(ChatColor.GREEN + "✔ Checkpoint bereikt: "
                                + ChatColor.YELLOW + cp.getName());
                        p.playSound(p.getLocation(),
                                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                    }
                }
            }
        }
    }

    private boolean containsBox(Location loc, Location p1, Location p2) {
        if (p1 == null || p2 == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().equals(p1.getWorld())) return false;
        double minX = Math.min(p1.getX(), p2.getX());
        double maxX = Math.max(p1.getX(), p2.getX()) + 1;
        double minY = Math.min(p1.getY(), p2.getY());
        double maxY = Math.max(p1.getY(), p2.getY()) + 1;
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    /**
     * Returns the respawn point for this player.
     * <p>If they've reached a CP, returns that CP's respawn.
     * <p>Otherwise returns null — caller should fall back to the spawn grid.
     */
    public Location getRespawnFor(UUID uuid) {
        String cpName = lastCheckpoint.get(uuid);
        if (cpName == null) return null;
        Checkpoint cp = get(cpName);
        return cp != null ? cp.getRespawn() : null;
    }

    public String getLastCheckpointName(UUID uuid) {
        return lastCheckpoint.get(uuid);
    }

    /** Clears all per-racer state — call on race start/end. */
    public void resetAllRacerState() {
        lastCheckpoint.clear();
    }

    public void clearRacer(UUID uuid) {
        lastCheckpoint.remove(uuid);
    }
}
