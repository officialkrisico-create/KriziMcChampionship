package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Arena manager with readiness checks for auto-skip support.
 *
 * <p>A game is considered "ready" when it has:
 *   - A schematic file configured (or is marked schematic-less)
 *   - An arena origin set
 *   - At least one team spawn OR at least one solo spawn
 *
 * <p>Games that don't meet this bar are auto-skipped by the automation
 * engine so players don't get stranded in an empty arena.
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
            p.teleport(lobby);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(20);
            p.setFoodLevel(20);
            p.getInventory().clear();
        }
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
    // READINESS — used by auto-skip
    // ----------------------------------------------------------------

    /**
     * Checks if a game has enough config to be played.
     *
     * <p>A game is ready when:
     * <ol>
     *   <li>It has either a schematic OR is marked schematic-less (no schematic = external plugin handles arena)</li>
     *   <li>It has an arena origin SET (skipped if schematic-less)</li>
     *   <li>It has at least one spawn (team or solo)</li>
     * </ol>
     *
     * <p>External games that manage their own arenas (like Adventure Escape
     * with its own world) can be marked {@code external-arena: true} in
     * their game config — readiness will always return true for them.
     *
     * @param gameId game identifier
     * @return true if the game can be started safely
     */
    public boolean isGameReady(String gameId) {
        // External arena check — e.g. Adventure Escape runs in its own world
        if (plugin.getConfig().getBoolean("games.list." + gameId + ".external-arena", false)) {
            return true;
        }

        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);

        // Schematic path — needs BOTH schematic name AND origin
        if (schematic != null) {
            if (origin == null) return false;
            if (!plugin.getSchematicManager().isWorldEditAvailable()) return false;
        }

        // Needs at least one spawn
        int teamSpawns = getAllTeamSpawns(gameId).size();
        int soloSpawns = getSoloSpawns(gameId).size();
        if (teamSpawns == 0 && soloSpawns == 0) return false;

        return true;
    }

    /** Human-readable report of why a game isn't ready. */
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
            // No schematic to paste — just TP players to spawns
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
                p.teleport(dest);
                p.setGameMode(GameMode.SURVIVAL);
                p.setHealth(20);
                p.setFoodLevel(20);
                p.getInventory().clear();
            }
        }
    }

    public String getStatusReport(String gameId) {
        String schematic = plugin.getSchematicManager().getSchematicForGame(gameId);
        Location origin  = plugin.getSchematicManager().getOriginForGame(gameId);
        int teamSpawns   = getAllTeamSpawns(gameId).size();
        int soloSpawns   = getSoloSpawns(gameId).size();

        StringBuilder sb = new StringBuilder();
        sb.append("Schematic:    ").append(schematic != null ? "✔ " + schematic : "✘ niet ingesteld").append("\n");
        sb.append("Arena origin: ").append(origin != null ? "✔ " + formatLoc(origin) : "✘ niet ingesteld").append("\n");
        sb.append("Team spawns:  ").append(teamSpawns).append(" / 8 teams\n");
        sb.append("Solo spawns:  ").append(soloSpawns).append("\n");
        sb.append("Ready:        ").append(isGameReady(gameId) ? "✔" : "✘ (" + getReadinessReason(gameId) + ")");
        return sb.toString();
    }

    private String formatLoc(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
