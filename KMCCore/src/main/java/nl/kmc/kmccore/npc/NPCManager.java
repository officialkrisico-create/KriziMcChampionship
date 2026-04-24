package nl.kmc.kmccore.npc;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages leaderboard display using hologram-stacked ArmorStands.
 *
 * <p>FancyNpcs support: if FancyNpcs is installed you can attach a
 * leaderboard hologram to any existing FancyNpc. Interact with the NPC
 * to open the leaderboard book (future feature). For now the NPC itself
 * is created via FancyNpcs' own commands — KMCCore just overlays the
 * hologram above the NPC's location.
 *
 * <p>Supported types: TOP_TEAMS, TOP_PLAYERS, CURRENT_GAME, MULTIPLIER
 */
public class NPCManager {

    public enum NpcType {
        TOP_TEAMS, TOP_PLAYERS, CURRENT_GAME, MULTIPLIER;
        public static NpcType fromString(String s) {
            try { return valueOf(s.toUpperCase()); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    public static class KmcNpc {
        public final String     id;
        public final NpcType    type;
        public final Location   location;
        /** FancyNpcs NPC ID this hologram is linked to (optional). */
        public final String     fancyNpcId;
        public final List<UUID> standUuids = new ArrayList<>();

        KmcNpc(String id, NpcType type, Location location, String fancyNpcId) {
            this.id = id;
            this.type = type;
            this.location = location;
            this.fancyNpcId = fancyNpcId;
        }
    }

    private final KMCCore plugin;
    private final Map<String, KmcNpc> npcs = new LinkedHashMap<>();
    private int nextId = 1;
    private final File npcFile;
    private final boolean fancyNpcsPresent;

    public NPCManager(KMCCore plugin) {
        this.plugin = plugin;
        this.npcFile = new File(plugin.getDataFolder(), "npcs.yml");

        fancyNpcsPresent = Bukkit.getPluginManager().getPlugin("FancyNpcs") != null;
        if (fancyNpcsPresent) {
            plugin.getLogger().info("FancyNpcs detected — leaderboard NPCs can link to FancyNpcs.");
        } else {
            plugin.getLogger().info("FancyNpcs not present — using ArmorStand holograms only.");
        }

        loadFromDisk();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 100L, 100L);
    }

    // ----------------------------------------------------------------
    // CRUD
    // ----------------------------------------------------------------

    /**
     * Creates a new leaderboard hologram.
     *
     * @param type       type of leaderboard
     * @param location   where to spawn the hologram stack
     * @param fancyNpcId optional FancyNpcs NPC ID to link with (may be null)
     */
    public KmcNpc createNpc(NpcType type, Location location, String fancyNpcId) {
        String id = "npc_" + nextId++;
        KmcNpc npc = new KmcNpc(id, type, location, fancyNpcId);
        npcs.put(id, npc);
        spawnStands(npc);
        save();
        return npc;
    }

    public boolean removeNpc(String id) {
        KmcNpc npc = npcs.remove(id);
        if (npc == null) return false;
        despawnStands(npc);
        save();
        return true;
    }

    public Collection<KmcNpc> getAllNpcs() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    // ----------------------------------------------------------------
    // ArmorStand rendering
    // ----------------------------------------------------------------

    private void spawnStands(KmcNpc npc) {
        List<String> lines = buildLines(npc.type);
        Location base = npc.location.clone();

        for (int i = 0; i < lines.size(); i++) {
            Location lineLoc = base.clone();
            lineLoc.setY(base.getY() + (lines.size() - i - 1) * 0.28);
            ArmorStand as = (ArmorStand) lineLoc.getWorld().spawnEntity(lineLoc, EntityType.ARMOR_STAND);
            as.setVisible(false);
            as.setCustomNameVisible(true);
            as.setGravity(false);
            as.setSmall(true);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setCustomName(MessageUtil.color(lines.get(i)));
            npc.standUuids.add(as.getUniqueId());
        }
    }

    private void despawnStands(KmcNpc npc) {
        for (UUID uuid : npc.standUuids) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) e.remove();
        }
        npc.standUuids.clear();
    }

    private void updateStandNames(KmcNpc npc) {
        List<String> lines = buildLines(npc.type);
        // Recreate if line count changed
        if (lines.size() != npc.standUuids.size()) {
            despawnStands(npc);
            spawnStands(npc);
            return;
        }
        for (int i = 0; i < npc.standUuids.size(); i++) {
            Entity e = Bukkit.getEntity(npc.standUuids.get(i));
            if (e instanceof ArmorStand as) {
                as.setCustomName(MessageUtil.color(lines.get(i)));
            }
        }
    }

    // ----------------------------------------------------------------
    // Line builders
    // ----------------------------------------------------------------

    private List<String> buildLines(NpcType type) {
        return switch (type) {
            case TOP_TEAMS    -> buildTopTeams();
            case TOP_PLAYERS  -> buildTopPlayers();
            case CURRENT_GAME -> buildCurrentGame();
            case MULTIPLIER   -> buildMultiplier();
        };
    }

    private List<String> buildTopTeams() {
        List<String> lines = new ArrayList<>();
        lines.add("&6&l⚔ Top Teams ⚔");
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints();
        int rank = 1;
        for (KMCTeam t : teams) {
            if (rank > 5) break;
            String medal = rank == 1 ? "&6🥇" : rank == 2 ? "&7🥈" : rank == 3 ? "&c🥉" : "&7#" + rank;
            lines.add(medal + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints());
            rank++;
        }
        return lines;
    }

    private List<String> buildTopPlayers() {
        List<String> lines = new ArrayList<>();
        lines.add("&e&l★ Top Spelers ★");
        List<PlayerData> players = plugin.getPlayerDataManager().getLeaderboard();
        int rank = 1;
        for (PlayerData pd : players) {
            if (rank > 5) break;
            String medal = rank == 1 ? "&6🥇" : rank == 2 ? "&7🥈" : rank == 3 ? "&c🥉" : "&7#" + rank;
            lines.add(medal + " &f" + pd.getName() + " &8- &e" + pd.getPoints());
            rank++;
        }
        return lines;
    }

    private List<String> buildCurrentGame() {
        List<String> lines = new ArrayList<>();
        lines.add("&b&l▶ Huidige Game");
        String gameName = plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getDisplayName()
                : "&8Geen";
        lines.add("&f" + gameName);
        return lines;
    }

    private List<String> buildMultiplier() {
        List<String> lines = new ArrayList<>();
        lines.add("&d&l★ Multiplier");
        lines.add("&e×" + plugin.getTournamentManager().getMultiplier());
        lines.add("&7Ronde " + plugin.getTournamentManager().getCurrentRound());
        return lines;
    }

    // ----------------------------------------------------------------

    public void refreshAll() {
        for (KmcNpc npc : npcs.values()) updateStandNames(npc);
    }

    public boolean isFancyNpcsPresent() { return fancyNpcsPresent; }

    // ----------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------

    private void loadFromDisk() {
        if (!npcFile.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(npcFile);
        ConfigurationSection sec = cfg.getConfigurationSection("npcs");
        if (sec == null) return;

        for (String id : sec.getKeys(false)) {
            ConfigurationSection nc = sec.getConfigurationSection(id);
            if (nc == null) continue;
            NpcType type = NpcType.fromString(nc.getString("type", "TOP_TEAMS"));
            if (type == null) continue;
            Location loc = (Location) nc.get("location");
            if (loc == null) continue;
            String fancyId = nc.getString("fancy-npc-id");
            KmcNpc npc = new KmcNpc(id, type, loc, fancyId);
            npcs.put(id, npc);
            spawnStands(npc);
            try {
                int num = Integer.parseInt(id.replace("npc_", ""));
                if (num >= nextId) nextId = num + 1;
            } catch (NumberFormatException ignored) {}
        }
        plugin.getLogger().info("Loaded " + npcs.size() + " leaderboard NPCs.");
    }

    public void save() {
        FileConfiguration cfg = new YamlConfiguration();
        for (KmcNpc n : npcs.values()) {
            String path = "npcs." + n.id + ".";
            cfg.set(path + "type",          n.type.name());
            cfg.set(path + "location",      n.location);
            if (n.fancyNpcId != null) cfg.set(path + "fancy-npc-id", n.fancyNpcId);
        }
        try { cfg.save(npcFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Save NPCs failed", e); }
    }
}
