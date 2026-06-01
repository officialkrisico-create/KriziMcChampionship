package nl.kmc.core.service;

import nl.kmc.core.api.PointsApi;
import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.domain.PointAward;
import nl.kmc.core.event.PointsAwardedEvent;
import nl.kmc.storage.StorageModule;
import nl.kmc.storage.model.StoredPointAudit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class PointsService implements PointsApi {

    private static final Logger LOG = Logger.getLogger(PointsService.class.getName());

    private final JavaPlugin    plugin;
    private final PlayerService players;
    private final TeamService   teams;
    private final StorageModule storage;
    private final TournamentService tournament;

    // End-of-game batch buffer — flushed to DB in bulk when signalGameEnd is called
    private final List<StoredPointAudit> pendingAudits = new ArrayList<>();

    // Config values loaded from points.yml
    private int    killPoints          = 50;
    private double multiplierForKills  = 1.0;  // kills usually bypass multiplier
    private boolean killsUseMultiplier = false;

    public PointsService(JavaPlugin plugin, PlayerService players, TeamService teams,
                         StorageModule storage, TournamentService tournament) {
        this.plugin     = plugin;
        this.players    = players;
        this.teams      = teams;
        this.storage    = storage;
        this.tournament = tournament;
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "points.yml");
        if (!file.exists()) { plugin.saveResource("points.yml", false); }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        killPoints         = cfg.getInt("kills.per-kill", 50);
        killsUseMultiplier = cfg.getBoolean("kills.apply-multiplier", false);
    }

    // ── PointsApi ─────────────────────────────────────────────────────────────

    @Override
    public void givePoints(UUID uuid, int amount, PointAward.Reason reason, String gameId) {
        Optional<KMCPlayer> opt = players.get(uuid);
        if (opt.isEmpty()) return;
        KMCPlayer player = opt.get();

        boolean useMultiplier = reason != PointAward.Reason.KILL || killsUseMultiplier;
        int finalAmount = useMultiplier
                ? (int) Math.round(amount * tournament.getMultiplier())
                : amount;

        PointAward award = new PointAward(uuid, player.getName(), player.getTeamId(),
                gameId, reason, finalAmount, tournament.getCurrentRound(), useMultiplier);

        PointsAwardedEvent event = new PointsAwardedEvent(award);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        int applied = event.getAward().getAmount();
        player.addPoints(applied);
        if (player.getTeamId() != null) teams.addPoints(player.getTeamId(), applied);

        bufferAudit(award);
    }

    @Override
    public void giveTeamPoints(String teamId, int amount, PointAward.Reason reason, String gameId) {
        int finalAmount = (int) Math.round(amount * tournament.getMultiplier());
        teams.addPoints(teamId, finalAmount);
        StoredPointAudit audit = new StoredPointAudit(null, "[TEAM:" + teamId + "]",
                teamId, gameId, reason.name(), finalAmount, tournament.getCurrentRound());
        pendingAudits.add(audit);
    }

    @Override
    public void awardPlayerPlacement(UUID uuid, int placement, int totalPlayers, String gameId) {
        int base = calculatePlacementPoints(placement, totalPlayers);
        givePoints(uuid, base, PointAward.Reason.PLACEMENT, gameId);
    }

    @Override
    public void awardTeamPlacement(String teamId, int placement, String gameId) {
        int base = calculateTeamPlacementPoints(placement);
        giveTeamPoints(teamId, base, PointAward.Reason.TEAM_PLACEMENT, gameId);
    }

    @Override public double getCurrentMultiplier() { return tournament.getMultiplier(); }

    @Override
    public void setPoints(UUID uuid, int points) {
        players.get(uuid).ifPresent(p -> {
            p.setPoints(points);
            players.persist(p);
        });
    }

    @Override
    public void setTeamPoints(String teamId, int points) {
        teams.setPoints(teamId, points);
    }

    @Override
    public void adjustPoints(UUID uuid, int delta, String reason) {
        players.get(uuid).ifPresent(p -> {
            p.addPoints(delta);
            players.persist(p);
            bufferAudit(new PointAward(uuid, p.getName(), p.getTeamId(),
                    "admin", PointAward.Reason.MANUAL, delta,
                    tournament.getCurrentRound(), false));
        });
    }

    @Override
    public void adjustTeamPoints(String teamId, int delta, String reason) {
        teams.addPoints(teamId, delta);
    }

    // ── Batch flush ───────────────────────────────────────────────────────────

    /** Call at game end to flush all buffered audits to DB in one transaction. */
    public void flushAudits() {
        if (pendingAudits.isEmpty()) return;
        List<StoredPointAudit> copy = new ArrayList<>(pendingAudits);
        pendingAudits.clear();
        storage.statistics().recordPointAwardBatch(copy);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void bufferAudit(PointAward award) {
        pendingAudits.add(new StoredPointAudit(
                award.getPlayerUuid(), award.getPlayerName(), award.getTeamId(),
                award.getGameId(), award.getReason().name(),
                award.getAmount(), award.getRound()));
    }

    private int calculatePlacementPoints(int placement, int total) {
        // Smooth curve: 1st=500, scales down; configurable in points.yml
        return switch (placement) {
            case 1  -> 500;
            case 2  -> 400;
            case 3  -> 300;
            case 4  -> 200;
            case 5  -> 150;
            case 6  -> 100;
            case 7  -> 75;
            case 8  -> 50;
            default -> Math.max(10, 40 - (placement - 9) * 3);
        };
    }

    private int calculateTeamPlacementPoints(int placement) {
        return switch (placement) {
            case 1  -> 1000;
            case 2  -> 600;
            case 3  -> 300;
            case 4  -> 100;
            default -> 0;
        };
    }
}
