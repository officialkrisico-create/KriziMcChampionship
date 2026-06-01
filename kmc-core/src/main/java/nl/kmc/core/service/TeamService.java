package nl.kmc.core.service;

import nl.kmc.core.api.TeamApi;
import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.storage.StorageModule;
import nl.kmc.storage.model.StoredPlayer;
import nl.kmc.storage.model.StoredTeam;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class TeamService implements TeamApi {

    private static final Logger LOG = Logger.getLogger(TeamService.class.getName());

    private final JavaPlugin plugin;
    private final StorageModule storage;
    private final PlayerService playerService;

    private final Map<String, KMCTeam> teamsById = new LinkedHashMap<>();

    public TeamService(JavaPlugin plugin, StorageModule storage, PlayerService playerService) {
        this.plugin        = plugin;
        this.storage       = storage;
        this.playerService = playerService;
    }

    public void load() {
        teamsById.clear();
        FileConfiguration cfg = plugin.getConfig();
        var section = cfg.getConfigurationSection("teams");
        if (section == null) { LOG.warning("[KMC/Teams] No teams section in config.yml"); return; }

        for (String id : section.getKeys(false)) {
            String displayName = section.getString(id + ".display-name", id);
            String colorName   = section.getString(id + ".color", "WHITE");
            String tagName     = section.getString(id + ".tag-color", "WHITE");

            ChatColor color    = parseChatColor(colorName);
            ChatColor tagColor = parseChatColor(tagName);

            KMCTeam team = new KMCTeam(id, displayName, color, tagColor);
            teamsById.put(id, team);
        }

        // Hydrate points/wins from DB
        storage.teams().findAll().thenAccept(stored -> {
            for (StoredTeam st : stored) {
                KMCTeam team = teamsById.get(st.id);
                if (team != null) {
                    team.setPoints(st.points);
                    team.setWins(st.wins);
                }
            }
        }).join();

        // Hydrate member lists from player cache
        playerService.getAllPlayers().forEach(p -> {
            if (p.getTeamId() != null) {
                KMCTeam team = teamsById.get(p.getTeamId());
                if (team != null) team.addMember(p.getUuid());
            }
        });

        LOG.info("[KMC/Teams] Loaded " + teamsById.size() + " teams.");
    }

    public void saveAll() {
        List<StoredTeam> stored = new ArrayList<>();
        for (KMCTeam t : teamsById.values()) {
            StoredTeam st = new StoredTeam(t.getId(), t.getDisplayName(),
                    t.getColor().name(), t.getTagColor().name());
            st.points = t.getPoints();
            st.wins   = t.getWins();
            stored.add(st);
        }
        storage.teams().saveAll(stored);
    }

    public void softResetAll() {
        teamsById.values().forEach(KMCTeam::softReset);
        storage.teams().softResetAll();
    }

    // ── TeamApi ──────────────────────────────────────────────────────────────

    @Override
    public Optional<KMCTeam> getTeam(String teamId) {
        return Optional.ofNullable(teamsById.get(teamId));
    }

    @Override
    public Optional<KMCTeam> getTeamByPlayer(UUID uuid) {
        return teamsById.values().stream()
                .filter(t -> t.hasMember(uuid))
                .findFirst();
    }

    @Override public List<KMCTeam> getAllTeams() { return List.copyOf(teamsById.values()); }

    @Override
    public List<KMCTeam> getStandings() {
        List<KMCTeam> sorted = new ArrayList<>(teamsById.values());
        sorted.sort(Comparator.comparingInt(KMCTeam::getPoints).reversed());
        return sorted;
    }

    @Override
    public Optional<KMCPlayer> getPlayer(UUID uuid) { return playerService.get(uuid); }

    @Override
    public KMCPlayer getOrCreatePlayer(UUID uuid, String name) {
        return playerService.getOrCreate(uuid, name);
    }

    @Override
    public List<KMCPlayer> getAllPlayers() { return playerService.getAllPlayers(); }

    // ── Mutation helpers (called by PointsService) ────────────────────────────

    public void addPoints(String teamId, int amount) {
        KMCTeam team = teamsById.get(teamId);
        if (team != null) team.addPoints(amount);
    }

    public void setPoints(String teamId, int points) {
        KMCTeam team = teamsById.get(teamId);
        if (team != null) {
            team.setPoints(points);
            storage.teams().findById(teamId).thenAccept(opt -> opt.ifPresent(st -> {
                st.points = points;
                storage.teams().save(st);
            }));
        }
    }

    // ── Team assignment ───────────────────────────────────────────────────────

    public boolean assignPlayerToTeam(UUID uuid, String teamId) {
        // Remove from current team
        teamsById.values().forEach(t -> t.removeMember(uuid));

        KMCTeam target = teamsById.get(teamId);
        if (target == null) return false;
        target.addMember(uuid);

        playerService.get(uuid).ifPresent(p -> {
            p.setTeamId(teamId);
            playerService.persist(p);
        });
        return true;
    }

    public boolean removePlayerFromTeam(UUID uuid) {
        teamsById.values().forEach(t -> t.removeMember(uuid));
        playerService.get(uuid).ifPresent(p -> {
            p.setTeamId(null);
            playerService.persist(p);
        });
        return true;
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private ChatColor parseChatColor(String name) {
        try { return ChatColor.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return ChatColor.WHITE; }
    }
}
