package nl.kmc.bingo.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.models.BingoCard;
import nl.kmc.bingo.models.TeamCardState;
import nl.kmc.bingo.objectives.BingoObjective;
import nl.kmc.bingo.util.SafeSpawnHelper;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import java.util.*;

/**
 * V2 Bingo manager — teams race to complete bingo objectives on a shared card.
 *
 * <p>All teams get the same card (same seed). First team to complete a line,
 * or the team with the most squares when time runs out, wins.
 */
public final class BingoGameManagerV2 extends BaseGameManager {

    private final BingoPlugin plugin;

    private BingoCard                   currentCard;
    private final Map<String, TeamCardState> teamStates  = new LinkedHashMap<>();
    private final Set<UUID>             participants = new HashSet<>();
    /** squareIndex → how many teams have completed it (for "1e/2e/3e" ordinals). */
    private final Map<Integer, Integer> squareCompletions = new HashMap<>();

    private BukkitTask gameTimerTask;
    private BossBar    bossBar;
    private int        remainingSeconds;

    public BingoGameManagerV2(BingoPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    @Override
    protected void onPrepare() {
        teamStates.clear();
        participants.clear();
        squareCompletions.clear();

        long seed = System.currentTimeMillis();
        currentCard = plugin.getCardGenerator().generate(seed);

        // Build team states and spawn players
        List<nl.kmc.core.domain.KMCTeam> activeTeams = new ArrayList<>();
        for (nl.kmc.core.domain.KMCTeam team : plugin.getKmcCore().getTeamManager().getAllTeams()) {
            boolean anyOnline = team.getMembers().stream().anyMatch(u -> Bukkit.getPlayer(u) != null);
            if (!anyOnline) continue;
            activeTeams.add(team);
            teamStates.put(team.getId(), new TeamCardState(team.getId(), currentCard));
            participants.addAll(team.getMembers());
        }

        // Clone a fresh world from the template so the template stays pristine.
        // (Falls back to the template directly if cloning fails.)
        plugin.getWorldManager().createGameWorldSync();

        // Spawn inside the freshly-cloned game world (or the template as fallback).
        Location baseSpawn = plugin.getWorldManager().getDefaultSpawn();

        // Bingo always plays in clear daylight — force day + dry weather and lock it.
        if (baseSpawn != null && baseSpawn.getWorld() != null) {
            var w = baseSpawn.getWorld();
            w.setTime(1000);
            w.setStorm(false);
            w.setThundering(false);
            w.setClearWeatherDuration(Integer.MAX_VALUE);
            try { w.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false); } catch (Exception ignored) {}
            try { w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false); } catch (Exception ignored) {}
        }

        List<Location> anchors = SafeSpawnHelper.findTeamSpawns(baseSpawn, activeTeams.size());

        PotionEffectType jumpType;
        try { jumpType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); } catch (Exception e) { jumpType = null; }
        int countdownSec = plugin.getConfig().getInt("game.countdown-seconds", 15);

        for (int i = 0; i < activeTeams.size(); i++) {
            nl.kmc.core.domain.KMCTeam team = activeTeams.get(i);
            Location anchor = i < anchors.size() ? anchors.get(i) : baseSpawn;
            List<UUID> online = team.getMembers().stream()
                    .filter(u -> Bukkit.getPlayer(u) != null).toList();
            List<Location> spots = SafeSpawnHelper.findPlayerSpawnsNearAnchor(anchor, online.size());
            for (int m = 0; m < online.size(); m++) {
                UUID uuid = online.get(m);
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                Location spot = m < spots.size() ? spots.get(m) : anchor;
                GamePlayerUtil.safeTeleport(p, spot);
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                p.setHealth(20); p.setFoodLevel(20);
                int ticks = countdownSec * 20;
                GamePlayerUtil.freezePlayer(p, ticks);
                if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
            }
        }

        bossBar = Bukkit.createBossBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Bingo!",
                BarColor.YELLOW, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        broadcast("§6§l[Bingo] §eVerzamel items om vakjes op je kaart te voltooien!");
    }

    @Override
    protected void onGameStart() {
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 900);

        PotionEffectType jumpType;
        try { jumpType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); } catch (Exception e) { jumpType = null; }
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            GamePlayerUtil.unfreezePlayer(p);
            if (jumpType != null) p.removePotionEffect(jumpType);
            p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "BINGO!",
                    ChatColor.YELLOW + "Voltooi je kaart!", 0, 40, 10);
        }

        // How-to-play intro.
        broadcast("§6§l═══════ BINGO ═══════");
        broadcast("§e• §7Verzamel de items op je §ebingokaart§7.");
        broadcast("§e• §7Elk voltooid item kleurt een §evakje§7.");
        broadcast("§e• §7Voltooi een §6lijn§7 (rij, kolom of diagonaal) voor bonuspunten.");
        broadcast("§e• §7Het team met de meeste lijnen/vakjes wint!");
        broadcast("§6§l═════════════════════");

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) end();
        }, 20L, 20L);
    }

    @Override
    protected void onGameEnd() {
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (bossBar       != null) { bossBar.removeAll();   bossBar       = null; }

        // Rank teams: first by lines completed, then squares completed
        List<TeamCardState> ranked = new ArrayList<>(teamStates.values());
        ranked.sort((a, b) -> {
            int diff = Integer.compare(b.getCompletedLineCount(), a.getCompletedLineCount());
            return diff != 0 ? diff : Integer.compare(b.getCompletedSquareCount(), a.getCompletedSquareCount());
        });

        for (int i = 0; i < ranked.size(); i++) {
            api.points().awardTeamPlacement(ranked.get(i).getTeamId(), i + 1, registration.getId());
        }

        Map<String, Integer> teamRankMap = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) teamRankMap.put(ranked.get(i).getTeamId(), i + 1);

        List<UUID> finishOrder = new ArrayList<>();
        int playerRank = 1;
        for (TeamCardState ts : ranked) {
            List<UUID> teamMembers = new ArrayList<>();
            for (UUID uuid : participants) {
                var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(uuid);
                if (kmcTeam != null && kmcTeam.getId().equals(ts.getTeamId())) teamMembers.add(uuid);
            }
            for (UUID uuid : teamMembers) {
                finishOrder.add(uuid);
                Player p = Bukkit.getPlayer(uuid);
                String name = p != null ? p.getName() : uuid.toString();
                api.points().awardPlayerPlacement(uuid, playerRank, participants.size(), registration.getId());
                api.games().recordGameParticipation(uuid, name, registration.getId(),
                        teamRankMap.getOrDefault(ts.getTeamId(), 99) == 1);
            }
            playerRank += teamMembers.size();
        }

        String winnerDesc = ranked.isEmpty() ? "No winner"
                : "Team " + ranked.get(0).getTeamId() + " (" + ranked.get(0).getCompletedLineCount() + " lines)";

        returnToLobby();
        teamStates.clear();
        participants.clear();
        fireResult(winnerDesc, null, null, finishOrder);
    }

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState s = new PlayerGameState();
        s.inventory = player.getInventory().getContents().clone();
        s.armor     = player.getInventory().getArmorContents().clone();
        s.health    = player.getHealth();
        s.maxHealth = 20;
        s.location  = player.getLocation();
        s.effects   = new ArrayList<>(player.getActivePotionEffects());
        var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(player.getUniqueId());
        if (kmcTeam != null) {
            TeamCardState ts = teamStates.get(kmcTeam.getId());
            if (ts != null) s.extra.put("squares", ts.getCompletedSquareCount());
        }
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§6[Bingo] State restored!");
    }

    @Override
    protected java.util.List<String> getScoreboardLines(org.bukkit.entity.Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add(api.tr(id, "sb.common.time", String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)));
        var kt = plugin.getKmcCore().getTeamManager().getTeamByPlayer(id);
        TeamCardState mine = kt != null ? teamStates.get(kt.getId()) : null;
        if (mine != null) {
            l.add("");
            l.add(api.tr(id, "sb.bingo.your-team"));
            l.add(api.tr(id, "sb.bingo.squares", mine.getCompletedSquareCount()));
            l.add(api.tr(id, "sb.bingo.lines", mine.getCompletedLineCount()));
        }
        l.add("");
        l.add(api.tr(id, "sb.bingo.teams"));
        teamStates.values().stream()
                .sorted((a, b) -> b.getCompletedLineCount() - a.getCompletedLineCount())
                .limit(4)
                .forEach(ts -> l.add(api.tr(id, "sb.bingo.team-entry", ts.getTeamId(),
                        ts.getCompletedLineCount(), ts.getCompletedSquareCount())));
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Bingo"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getWorldManager().templateExists())
                    r.addError("Bingo template world '" + plugin.getWorldManager().getTemplateWorldName() + "' not found.");
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called when a player completes an objective (collect item, craft, etc.). */
    public void onObjectiveCompleted(Player player, int squareIndex, BingoObjective objective) {
        if (!getState().isRunning()) return;
        var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(player.getUniqueId());
        if (kmcTeam == null) return;
        TeamCardState ts = teamStates.get(kmcTeam.getId());
        if (ts == null || ts.isCompleted(squareIndex)) return;

        int prevLines = ts.getCompletedLineCount();
        ts.addProgress(squareIndex, objective.getTargetAmount());

        int squarePts = plugin.getConfig().getInt("points.per-square", 25);
        api.points().givePoints(player.getUniqueId(), squarePts, PointAward.Reason.OBJECTIVE, registration.getId());

        // Ordinal: how manieth team completed THIS item (1e/2e/3e ...).
        int ordinal = squareCompletions.merge(squareIndex, 1, Integer::sum);
        broadcast("§e" + player.getName() + " §7van " + kmcTeam.getColor() + kmcTeam.getDisplayName()
                + " §7heeft §b" + objective.getDisplayName() + " §7als §6" + ordinal + "e §7voltooid! §8(+" + squarePts + " ptn)");

        // Check for line completion (lines are recalculated automatically inside addProgress)
        int newLines = ts.getCompletedLineCount();
        if (newLines > prevLines) {
            int linePts = plugin.getConfig().getInt("points.per-line", 100);
            for (UUID memberId : kmcTeam.getMembers()) {
                api.points().givePoints(memberId, linePts, PointAward.Reason.OBJECTIVE, registration.getId());
            }
            broadcast("§6§l[Bingo] §e🟡 Team " + kmcTeam.getDisplayName() + " §6completed a line! §8(+" + linePts + " each)");

            // Check full card
            if (ts.getCompletedSquareCount() >= currentCard.getObjectives().length) {
                broadcast("§6§l[Bingo] §e🏆 Team " + kmcTeam.getDisplayName() + " §6completed the FULL card!");
                end();
                return;
            }
        }

        updateBossBar();
    }

    public BingoCard getCurrentCard() { return currentCard; }
    public Map<String, TeamCardState> getTeamStates() { return Collections.unmodifiableMap(teamStates); }

    /** Returns the TeamCardState for a given teamId, or null if not active. */
    public TeamCardState getTeamState(String teamId) { return teamStates.get(teamId); }

    /**
     * Recounts a team's CollectObjective progress by tallying all online
     * team-member inventories. Called by InventoryListener on every item change.
     *
     * @param teamId      the team to recount
     * @param triggeredBy the player whose inventory changed (for credit); may be null
     */
    public void recountTeamInventory(String teamId, java.util.UUID triggeredBy) {
        if (!getState().isRunning()) return;
        TeamCardState ts = teamStates.get(teamId);
        if (ts == null) return;
        nl.kmc.core.domain.KMCTeam team =
                plugin.getKmcCore().getTeamManager().getTeam(teamId);
        if (team == null) return;

        // Aggregate item counts across all online team members
        java.util.Map<org.bukkit.Material, Integer> totals =
                new java.util.EnumMap<>(org.bukkit.Material.class);
        for (java.util.UUID memberId : team.getMembers()) {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(memberId);
            if (p == null) continue;
            for (org.bukkit.inventory.ItemStack stack : p.getInventory().getContents()) {
                if (stack == null || stack.getType() == org.bukkit.Material.AIR) continue;
                totals.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }

        // Update progress for every CollectObjective square
        if (currentCard == null) return;
        for (int idx = 0; idx < currentCard.getObjectives().length; idx++) {
            nl.kmc.bingo.objectives.BingoObjective obj = currentCard.getObjectives()[idx];
            if (!(obj instanceof nl.kmc.bingo.objectives.CollectObjective co)) continue;
            int have = totals.getOrDefault(co.getTarget(), 0);
            boolean wasComplete = ts.isCompleted(idx);
            ts.setProgress(idx, have);
            if (!wasComplete && ts.isCompleted(idx)) {
                onObjectiveCompleted(
                        org.bukkit.Bukkit.getPlayer(triggeredBy != null ? triggeredBy
                                : team.getMembers().iterator().next()),
                        idx, obj);
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void updateBossBar() {
        if (bossBar == null) return;
        int total = currentCard != null ? currentCard.getObjectives().length : 25;
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        StringBuilder sb = new StringBuilder(ChatColor.GOLD + "" + ChatColor.BOLD + "Bingo §8| ");
        teamStates.values().forEach(ts ->
                sb.append("§e").append(ts.getTeamId())
                  .append(" §7").append(ts.getCompletedSquareCount()).append("/").append(total).append(" §8| "));
        sb.append("§b").append(String.format("%02d:%02d", min, sec));
        bossBar.setTitle(sb.toString());
        bossBar.setProgress(Math.max(0, Math.min(1.0, 1.0 - (double) remainingSeconds
                / plugin.getConfig().getInt("game.max-duration-seconds", 900))));
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        participants.forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            GamePlayerUtil.resetPlayer(p);
            if (lobby != null) p.teleport(lobby);
        });
        plugin.getWorldManager().disposeGameWorld();
    }

}
