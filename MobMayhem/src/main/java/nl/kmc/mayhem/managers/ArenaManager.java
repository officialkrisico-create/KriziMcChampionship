package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-arena setup. Admin builds ONE arena in a normal "template" world
 * and marks its bounding box with {@code /mm pos1}/{@code pos2}. At game
 * start, that box is captured fresh (via WorldEdit) and pasted into a
 * shared void world once per team, at a large non-overlapping offset
 * ("pocket") — see {@link ArenaPaster} / {@link VoidWorldManager}.
 *
 * <p>This replaces the old whole-world-file-clone approach: pasting a
 * captured box into an already-empty void is cheap, and — the actual bug
 * this fixes — there's no leftover template terrain outside the arena for
 * a bad spawn point to end up buried inside.
 *
 * <p>Stored data (all coordinates are relative to the template world):
 * <ul>
 *   <li>Arena bounding box (pos1/pos2 corners)</li>
 *   <li>Player spawn (x,y,z + yaw/pitch)</li>
 *   <li>Mob spawn locations (list of x,y,z)</li>
 *   <li>Powerup spawn locations (list of x,y,z)</li>
 * </ul>
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>Create a template world manually (e.g. /mv create mm_template)</li>
 *   <li>Build the arena in that world</li>
 *   <li>/mm settemplate mm_template (registers it)</li>
 *   <li>Stand at one corner of the arena → /mm pos1, opposite corner → /mm pos2</li>
 *   <li>Stand at desired player spawn → /mm setspawn</li>
 *   <li>Stand at each desired mob spawn → /mm addmobspawn</li>
 *   <li>/mm status to verify</li>
 * </ol>
 */
public class ArenaManager {

    private final MobMayhemPlugin plugin;

    /** Arena bounding box corners IN THE TEMPLATE WORLD (coords only, order-independent). */
    private double p1X, p1Y, p1Z, p2X, p2Y, p2Z;
    private boolean pos1Set, pos2Set;

    /** Player spawn location IN THE TEMPLATE WORLD. */
    private double psX, psY, psZ;
    private float  psYaw, psPitch;
    private boolean playerSpawnSet;

    /** Mob spawn locations IN THE TEMPLATE WORLD (coords only). */
    private final List<double[]> mobSpawnsRaw = new ArrayList<>();

    /** Powerup spawn locations IN THE TEMPLATE WORLD (coords only). */
    private final List<double[]> powerupSpawnsRaw = new ArrayList<>();

    public ArenaManager(MobMayhemPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var cfg = plugin.getConfig();
        // Corners are whole block coordinates; floor() also normalises values saved by older
        // versions, which stored the admin's exact (fractional) position.
        if (cfg.contains("arena.pos1.x")) {
            p1X = Math.floor(cfg.getDouble("arena.pos1.x")); p1Y = Math.floor(cfg.getDouble("arena.pos1.y")); p1Z = Math.floor(cfg.getDouble("arena.pos1.z"));
            pos1Set = true;
        }
        if (cfg.contains("arena.pos2.x")) {
            p2X = Math.floor(cfg.getDouble("arena.pos2.x")); p2Y = Math.floor(cfg.getDouble("arena.pos2.y")); p2Z = Math.floor(cfg.getDouble("arena.pos2.z"));
            pos2Set = true;
        }
        if (cfg.contains("arena.player-spawn.x")) {
            psX = cfg.getDouble("arena.player-spawn.x");
            psY = cfg.getDouble("arena.player-spawn.y");
            psZ = cfg.getDouble("arena.player-spawn.z");
            psYaw = (float) cfg.getDouble("arena.player-spawn.yaw", 0);
            psPitch = (float) cfg.getDouble("arena.player-spawn.pitch", 0);
            playerSpawnSet = true;
        }

        mobSpawnsRaw.clear();
        var list = cfg.getList("arena.mob-spawns");
        if (list != null) {
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m)) continue;
                Object x = m.get("x"), y = m.get("y"), z = m.get("z");
                if (x == null || y == null || z == null) continue;
                mobSpawnsRaw.add(new double[]{
                        ((Number) x).doubleValue(),
                        ((Number) y).doubleValue(),
                        ((Number) z).doubleValue()
                });
            }
        }

        powerupSpawnsRaw.clear();
        var powerupList = cfg.getList("arena.powerup-spawns");
        if (powerupList != null) {
            for (Object o : powerupList) {
                if (!(o instanceof java.util.Map<?, ?> m)) continue;
                Object x = m.get("x"), y = m.get("y"), z = m.get("z");
                if (x == null || y == null || z == null) continue;
                powerupSpawnsRaw.add(new double[]{
                        ((Number) x).doubleValue(),
                        ((Number) y).doubleValue(),
                        ((Number) z).doubleValue()
                });
            }
        }

        plugin.getLogger().info("Arena loaded: spawn=" + (playerSpawnSet ? "✔" : "✘")
                + ", " + mobSpawnsRaw.size() + " mob spawns, " + powerupSpawnsRaw.size() + " powerup spawns");
    }

    public void save() {
        var cfg = plugin.getConfig();
        if (pos1Set) {
            cfg.set("arena.pos1.x", p1X); cfg.set("arena.pos1.y", p1Y); cfg.set("arena.pos1.z", p1Z);
        }
        if (pos2Set) {
            cfg.set("arena.pos2.x", p2X); cfg.set("arena.pos2.y", p2Y); cfg.set("arena.pos2.z", p2Z);
        }
        if (playerSpawnSet) {
            cfg.set("arena.player-spawn.x", psX);
            cfg.set("arena.player-spawn.y", psY);
            cfg.set("arena.player-spawn.z", psZ);
            cfg.set("arena.player-spawn.yaw", psYaw);
            cfg.set("arena.player-spawn.pitch", psPitch);
        }
        // Save raw coords as a list of maps
        List<java.util.Map<String, Double>> serialized = new ArrayList<>();
        for (double[] arr : mobSpawnsRaw) {
            serialized.add(java.util.Map.of("x", arr[0], "y", arr[1], "z", arr[2]));
        }
        cfg.set("arena.mob-spawns", serialized);

        List<java.util.Map<String, Double>> serializedPowerups = new ArrayList<>();
        for (double[] arr : powerupSpawnsRaw) {
            serializedPowerups.add(java.util.Map.of("x", arr[0], "y", arr[1], "z", arr[2]));
        }
        cfg.set("arena.powerup-spawns", serializedPowerups);

        plugin.saveConfig();
    }

    /** Sets corner 1 to the BLOCK the admin is standing on (not the air block at their feet). */
    public void setPos1(Location loc) {
        this.p1X = loc.getBlockX(); this.p1Y = loc.getBlockY() - 1; this.p1Z = loc.getBlockZ();
        this.pos1Set = true;
        save();
    }

    /** Sets corner 2 to the BLOCK the admin is standing on (not the air block at their feet). */
    public void setPos2(Location loc) {
        this.p2X = loc.getBlockX(); this.p2Y = loc.getBlockY() - 1; this.p2Z = loc.getBlockZ();
        this.pos2Set = true;
        save();
    }

    /** "x, y, z" of a corner, for chat feedback. */
    public String describePos(boolean first) {
        return first ? (int) p1X + ", " + (int) p1Y + ", " + (int) p1Z
                     : (int) p2X + ", " + (int) p2Y + ", " + (int) p2Z;
    }

    /**
     * Pos1/pos2 only mean something in the template world (that's where the box is captured
     * from) — returns an explanation if {@code player} is standing in some other world, else null.
     */
    public String templateWorldProblem(org.bukkit.entity.Player player) {
        String template = plugin.getWorldCloner().getTemplateWorldName();
        if (player.getWorld().getName().equals(template)) return null;
        return "Je staat in wereld '" + player.getWorld().getName() + "', maar de template-wereld is '" + template
                + "'. Ga naar '" + template + "' (of kies deze wereld met /mm settemplate "
                + player.getWorld().getName() + ") en probeer opnieuw.";
    }

    public boolean isBoxSet() { return pos1Set && pos2Set; }

    /** True if the point's block lies inside the arena box (inclusive). */
    public boolean isInsideBox(double x, double y, double z) {
        if (!isBoxSet()) return false;
        double[] min = getBoxMin();
        int[] size = getBoxSize();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return bx >= min[0] && bx < min[0] + size[0]
            && by >= min[1] && by < min[1] + size[1]
            && bz >= min[2] && bz < min[2] + size[2];
    }

    /** How many of the player/mob/powerup spawns lie outside the box — those would end up in the void. */
    public int countSpawnsOutsideBox() {
        if (!isBoxSet()) return 0;
        int outside = 0;
        if (playerSpawnSet && !isInsideBox(psX, psY, psZ)) outside++;
        for (double[] c : mobSpawnsRaw)     if (!isInsideBox(c[0], c[1], c[2])) outside++;
        for (double[] c : powerupSpawnsRaw) if (!isInsideBox(c[0], c[1], c[2])) outside++;
        return outside;
    }

    /** Minimum corner of the arena box (template-world coords). */
    public double[] getBoxMin() {
        return new double[]{Math.min(p1X, p2X), Math.min(p1Y, p2Y), Math.min(p1Z, p2Z)};
    }

    /** {dx, dy, dz} block dimensions of the arena box (inclusive). */
    public int[] getBoxSize() {
        return new int[]{
                (int) Math.abs(p1X - p2X) + 1,
                (int) Math.abs(p1Y - p2Y) + 1,
                (int) Math.abs(p1Z - p2Z) + 1
        };
    }

    /** pos1/pos2 resolved against {@code templateWorld}, for WorldEdit capture. */
    public Location getPos1In(World templateWorld) { return new Location(templateWorld, p1X, p1Y, p1Z); }
    public Location getPos2In(World templateWorld) { return new Location(templateWorld, p2X, p2Y, p2Z); }

    public void setPlayerSpawn(Location loc) {
        this.psX = loc.getX();
        this.psY = loc.getY();
        this.psZ = loc.getZ();
        this.psYaw = loc.getYaw();
        this.psPitch = loc.getPitch();
        this.playerSpawnSet = true;
        save();
    }

    public void addMobSpawn(Location loc) {
        mobSpawnsRaw.add(new double[]{loc.getX(), loc.getY(), loc.getZ()});
        save();
    }

    public void clearMobSpawns() {
        mobSpawnsRaw.clear();
        save();
    }

    public void addPowerupSpawn(Location loc) {
        powerupSpawnsRaw.add(new double[]{loc.getX(), loc.getY(), loc.getZ()});
        save();
    }

    public void clearPowerupSpawns() {
        powerupSpawnsRaw.clear();
        save();
    }

    public boolean isPlayerSpawnSet()      { return playerSpawnSet; }
    public int     getMobSpawnCount()      { return mobSpawnsRaw.size(); }
    public int     getPowerupSpawnCount()  { return powerupSpawnsRaw.size(); }

    /**
     * Builds a runtime {@link Arena} for a pasted pocket: every stored point
     * is translated from "absolute coord in the template world" to "offset
     * from the arena box's minimum corner, applied to {@code pasteOrigin}".
     *
     * @param pasteOrigin the void-world location the box's minimum corner was pasted at
     */
    public Arena buildForPastedPocket(String arenaId, World voidWorld, Location pasteOrigin) {
        if (!playerSpawnSet || !isBoxSet()) return null;
        double[] boxMin = getBoxMin();

        Location playerSpawn = pasteOrigin.clone().add(psX - boxMin[0], psY - boxMin[1], psZ - boxMin[2]);
        playerSpawn.setWorld(voidWorld);
        playerSpawn.setYaw(psYaw);
        playerSpawn.setPitch(psPitch);

        Arena arena = new Arena(arenaId, playerSpawn);
        for (double[] coords : mobSpawnsRaw) {
            Location loc = pasteOrigin.clone().add(coords[0] - boxMin[0], coords[1] - boxMin[1], coords[2] - boxMin[2]);
            loc.setWorld(voidWorld);
            arena.addMobSpawn(loc);
        }
        for (double[] coords : powerupSpawnsRaw) {
            Location loc = pasteOrigin.clone().add(coords[0] - boxMin[0], coords[1] - boxMin[1], coords[2] - boxMin[2]);
            loc.setWorld(voidWorld);
            arena.addPowerupSpawn(loc);
        }
        return arena;
    }

    public boolean isReady() {
        return isBoxSet() && playerSpawnSet && mobSpawnsRaw.size() >= 4 && countSpawnsOutsideBox() == 0;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        if (isBoxSet()) {
            int[] s = getBoxSize();
            sb.append("Arena box:    ✔ ").append(s[0]).append("x").append(s[1]).append("x").append(s[2])
              .append(" blokken (").append(describePos(true)).append(" → ").append(describePos(false)).append(")\n");
            int outside = countSpawnsOutsideBox();
            if (outside > 0)
                sb.append("Buiten de box: &c").append(outside)
                  .append(" spawn(s) liggen buiten pos1-pos2 en worden NIET meegekopieerd — pas de box of de spawns aan\n");
        } else {
            sb.append("Arena box:    ✘ (gebruik /mm pos1 en /mm pos2)\n");
        }
        sb.append("Player spawn: ").append(playerSpawnSet ? "✔" : "✘").append("\n");
        sb.append("Mob spawns:   ").append(mobSpawnsRaw.size())
                .append(mobSpawnsRaw.size() < 4 ? " &c(min 4)" : "").append("\n");
        sb.append("Powerup spawns: ").append(powerupSpawnsRaw.size())
                .append(powerupSpawnsRaw.isEmpty() ? " &7(optioneel)" : "");
        return sb.toString();
    }
}
