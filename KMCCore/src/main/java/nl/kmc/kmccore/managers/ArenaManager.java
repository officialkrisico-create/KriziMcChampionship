package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Arena manager with per-game Multiverse world support.
 *
 * <p>Each game entry in config.yml can have an optional {@code world:}
 * field pointing to a Multiverse world. When that game starts, all
 * players are teleported into that world before spawn placement.
 *
 * <p>Readiness check added so auto-skip still works.
 */
public class ArenaManager {

    private final KMCCore plugin;
    private Location lobby;

    public ArenaManager(KMCCore plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        lobby = plugin.getConfig().getLocation("arena.lobby");
        if (lobby != null) plugin.getLogger().info("Lobby loaded at " + formatLoc(lobby));
        else plugin.getLogger().warning("No lobby set! Use /kmclobby set");
    }

    // ----------------------------------------------------------------
    // Lobby
    // ----------------------------------------------------------------

    public Location getLobby() { return lobby; }

    public void setLobby(Location loc) {
        this.lobby = loc.clone();
        plugin.getConfig().set("arena.lobby", this.lobby);
        plugin.saveConfig();
    }

    public void teleportAllToLobby() {
        if (lobby == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.teleport(lobby);
                p.setGameMode(GameMode.ADVENTURE);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.getInventory().clear();
            } catch (Exception e) {
                plugin.getLogger().warning("TP to lobby failed for " + p.getName() + ": " + e.getMessage());
            }
        }
    }

    // ----------------------------------------------------------------
    // Per-game world
    // ----------------------------------------------------------------

    /** Returns the configured Multiverse world for a game, or null. */
    public World getGameWorld(String gameId) {
        String worldName = plugin.getConfig().getString("games.list." + gameId + ".world");
        if (worldName == null || worldName.isBlank()) return null;
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            plugin.getLogger().warning("Configured world '" + worldName
                    + "' for game " + gameId + " does not exist on this server.");
        }
        return w;
    }

    // ----------------------------------------------------------------
    // Team spawns
    // ----------------------------------------------------------------

    public void setTeamSpawn(String gameId, String teamId, Location loc) {
        plugin.getConfig().set("games.list." + gameId + ".team-spawns." + teamId, loc);
        plugin.saveConfig();
    }

    public Location getTeamSpawn(String gameId, String teamId) {
        return plugin.getConfig().getLocation("games.list." + gameId + ".team-spawns." + teamId);
    }

    public Map<String, Location> getAllTeamSpawns(String gameId) {
        Map<String, Location> result = new LinkedHashMap<>();
        ConfigurationSection sec = plugin.getConfig()
                .getConfigurationSection("games.list." + gameId + ".team-spawns");
        if (sec == null) return result;
        for (String teamId : sec.getKeys(false)) {
            Location l = sec.getLocation(teamId);
            if (l != null) result.put(teamId, l);
        }
        return result;
    }

    // ----------------------------------------------------------------
    // Solo spawns
    // ----------------------------------------------------------------

    public void addSoloSpawn(String gameId, Location loc) {
        List<Location> list = new ArrayList<>(getSoloSpawns(gameId));
        list.add(loc.clone());
        plugin.getConfig().set("games.list." + gameId + ".solo-spawns", list);
        plugin.saveConfig();
    }

    @SuppressWarnings("unchecked")
    public List<Location> getSoloSpawns(String gameId) {
        List<Location> result = new ArrayList<>();
        Object raw = plugin.getConfig().get("games.list." + gameId + ".solo-spawns");
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o instanceof Location l) result.add(l);
        }
        return result;
    }

    public void clearSoloSpawns(String gameId) {
        plugin.getConfig().set("games.list." + gameId + ".solo-spawns", new ArrayList<>());
        plugin.saveConfig();
    }

    // ----------------------------------------------------------------
    // Readiness (auto-skip support)
    // ----------------------------------------------------------------

    public boolean isGameReady(String gameId) {
        if (plugin.getConfig().getBoolean("games.list." + gameId + ".external-arena", false)) {
            return true;
        }

        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);

        if (schematic != null) {
            if (origin == null) return false;
            if (!plugin.getSchematicManager().isWorldEditAvailable()) return false;
        }

        int teamSpawns = getAllTeamSpawns(gameId).size();
        int soloSpawns = getSoloSpawns(gameId).size();
        if (teamSpawns == 0 && soloSpawns == 0) return false;

        return true;
    }

    public String getReadinessReason(String gameId) {
        if (plugin.getConfig().getBoolean("games.list." + gameId + ".external-arena", false)) {
            return "external arena — ready";
        }

        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);
        int teamSpawns = getAllTeamSpawns(gameId).size();
        int soloSpawns = getSoloSpawns(gameId).size();

        List<String> issues = new ArrayList<>();
        if (schematic != null && origin == null) issues.add("origin not set");
        if (schematic != null && !plugin.getSchematicManager().isWorldEditAvailable()) issues.add("WorldEdit missing");
        if (teamSpawns == 0 && soloSpawns == 0) issues.add("no spawns");
        return issues.isEmpty() ? "ready" : String.join(", ", issues);
    }

    // ----------------------------------------------------------------
    // Arena lifecycle
    // ----------------------------------------------------------------

    public boolean loadArenaForGame(String gameId) {
        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);

        if (schematic == null || origin == null) {
            teleportPlayersForGame(gameId);
            return false;
        }

        boolean ok = plugin.getSchematicManager().pasteSchematic(schematic, origin);
        if (ok) teleportPlayersForGame(gameId);
        return ok;
    }

    public boolean resetArenaForGame(String gameId) {
        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);
        if (schematic == null || origin == null) return false;
        return plugin.getSchematicManager().resetArena(schematic, origin);
    }

    /**
     * Teleports players to their spawns for the given game.
     * If spawns are in a different world (per-game world), Minecraft
     * handles the cross-world teleport automatically.
     */
    public void teleportPlayersForGame(String gameId) {
        Map<String, Location> teamSpawns = getAllTeamSpawns(gameId);
        List<Location>        soloSpawns = getSoloSpawns(gameId);

        int soloIndex = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            KMCTeam team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
            Location dest = null;

            if (team != null && teamSpawns.containsKey(team.getId())) {
                dest = teamSpawns.get(team.getId());
            } else if (!soloSpawns.isEmpty()) {
                dest = soloSpawns.get(soloIndex % soloSpawns.size());
                soloIndex++;
            }

            if (dest != null) {
                try {
                    p.teleport(dest);
                    p.setGameMode(GameMode.SURVIVAL);
                    p.setHealth(20);
                    p.setFoodLevel(20);
                    p.getInventory().clear();
                } catch (Exception e) {
                    plugin.getLogger().warning("TP to arena failed for " + p.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    public String getStatusReport(String gameId) {
        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);
        int teamSpawns   = getAllTeamSpawns(gameId).size();
        int soloSpawns   = getSoloSpawns(gameId).size();
        World gameWorld  = getGameWorld(gameId);

        StringBuilder sb = new StringBuilder();
        sb.append("World:        ").append(gameWorld != null ? "✔ " + gameWorld.getName() : "none (uses any)").append("\n");
        sb.append("Schematic:    ").append(schematic != null ? "✔ " + schematic : "✘ niet ingesteld").append("\n");
        sb.append("Arena origin: ").append(origin != null ? "✔ " + formatLoc(origin) : "✘ niet ingesteld").append("\n");
        sb.append("Team spawns:  ").append(teamSpawns).append("\n");
        sb.append("Solo spawns:  ").append(soloSpawns).append("\n");
        sb.append("Ready:        ").append(isGameReady(gameId) ? "✔" : "✘ (" + getReadinessReason(gameId) + ")");
        return sb.toString();
    }

    private String formatLoc(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
