package nl.kmc.mayhem.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.core.event.GameObjectiveEvent;
import nl.kmc.game.api.*;
import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import nl.kmc.mayhem.models.TeamGameState;
import nl.kmc.mayhem.waves.WaveLibrary;
import nl.kmc.mayhem.waves.WaveDefinition;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 Mob Mayhem manager — co-op wave survival, per-team arenas.
 *
 * <p>Each team fights waves in their own cloned world. The team that
 * survives the most waves wins. Points awarded per mob kill and per
 * completed wave. Placement determined by waves survived.
 */
public final class MobMayhemGameManagerV2 extends BaseGameManager {

    private final MobMayhemPlugin plugin;

    /** Per-team gameplay state. */
    private final Map<String, TeamGameState>  teamStates          = new LinkedHashMap<>();
    private final Map<String, WaveExecutor>   teamExecutors       = new LinkedHashMap<>();
    private final Map<String, Arena>          teamArenas          = new LinkedHashMap<>();
    private final Map<String, Location>       teamPasteOrigins    = new LinkedHashMap<>();
    private final Map<String, PowerupSpawner> teamPowerupSpawners = new LinkedHashMap<>();
    private final Map<UUID, Integer>          playerMobKills      = new HashMap<>();
    private final Map<String, StandardStartFlow> teamStartFlows   = new LinkedHashMap<>();
    private final List<WaveDefinition>        waves;

    private BossBar    bossBar;
    private BukkitTask heartbeatTask;

    /** Teams that cleared every wave: still alive, but nothing left to do. */
    private final Set<String> finishedTeams = new HashSet<>();
    private long gameStartMs;
    private long firstFinishMs;

    public MobMayhemGameManagerV2(MobMayhemPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.waves  = WaveLibrary.loadWaves(plugin.getConfig().getConfigurationSection("waves"));
        if (plugin.getUpgradeManager() != null) plugin.getUpgradeManager().setReviver(this::reviveOne);
    }

    /** "Wederopstanding" perk: brings one fallen teammate of {@code ts} back into the fight. */
    private void reviveOne(TeamGameState ts) {
        Arena arena = teamArenas.get(ts.getTeamId());
        if (arena == null || ts.isEliminated()) return;
        for (UUID id : ts.getAllPlayers()) {
            if (ts.getAlivePlayers().contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            ts.revivePlayer(id);
            p.setGameMode(GameMode.ADVENTURE);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            Location spawn = arena.getPlayerSpawn();
            if (spawn != null) p.teleport(spawn);
            plugin.getKitManager().giveStarterKit(p);
            plugin.getUpgradeManager().restoreGear(p);
            var maxHp = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            p.setHealth(maxHp != null ? maxHp.getValue() : 20.0);
            p.setFoodLevel(20);
            p.sendTitle(api.tr(p.getUniqueId(), "mm.revived.title"), api.tr(p.getUniqueId(), "mm.revived.sub"), 5, 40, 10);
            tellKey("mm.revived-chat", p.getName());
            return;
        }
    }

    @Override
    protected void onPrepare() {
        teamStates.clear();
        teamExecutors.clear();
        teamArenas.clear();
        teamPasteOrigins.clear();
        teamPowerupSpawners.clear();
        playerMobKills.clear();
        teamStartFlows.clear();
        finishedTeams.clear();
        firstFinishMs = 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            var kmcTeam = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
            if (kmcTeam == null) continue;
            String tid = kmcTeam.getId();
            teamStates.computeIfAbsent(tid, id -> new TeamGameState(id, id))
                    .addPlayer(p.getUniqueId());
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20); p.setFoodLevel(20);
        }

        bossBar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Mob Mayhem",
                BarColor.RED, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        pasteArenasForTeams(new ArrayList<>(teamStates.keySet()));
    }

    /**
     * Captures the admin's arena box fresh from the template world and pastes
     * one copy per team into the shared void world, each at its own
     * non-overlapping "pocket" offset. Synchronous (WorldEdit paste on a
     * handful of teams is fast) — unlike the old whole-world-file clone,
     * there's no async wait needed before waves can start.
     */
    private void pasteArenasForTeams(List<String> teamIds) {
        if (teamIds.isEmpty()) return;
        ArenaManager am = plugin.getArenaManager();

        World templateWorld = plugin.getWorldCloner().getOrLoadTemplateWorld();
        if (templateWorld == null) {
            arenaProblem("Template-wereld '" + plugin.getWorldCloner().getTemplateWorldName()
                    + "' bestaat niet of kon niet geladen worden (/mm settemplate <wereld>).");
            return;
        }
        if (!am.isBoxSet()) {
            arenaProblem("Arena-box niet gezet — gebruik /mm pos1 en /mm pos2 in de template-wereld.");
            return;
        }

        World voidWorld = plugin.getVoidWorldManager().getOrCreateVoidWorld();
        if (voidWorld == null) {
            arenaProblem("De void-wereld '" + plugin.getVoidWorldManager().getVoidWorldName() + "' kon niet worden aangemaakt.");
            return;
        }

        com.sk89q.worldedit.extent.clipboard.Clipboard clipboard;
        try {
            clipboard = ArenaPaster.capture(am.getPos1In(templateWorld), am.getPos2In(templateWorld));
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "[MobMayhem] Capturing the arena failed", t);
            arenaProblem("Arena kopiëren mislukt: " + t.getClass().getSimpleName()
                    + (t.getCause() != null ? " / " + t.getCause().getClass().getSimpleName() : "") + " (zie console).");
            return;
        }

        long solid = ArenaPaster.countNonAir(clipboard);
        plugin.getLogger().info("[MobMayhem] Captured arena box " + clipboard.getRegion().getWidth() + "x"
                + clipboard.getRegion().getHeight() + "x" + clipboard.getRegion().getLength()
                + " from '" + templateWorld.getName() + "' — " + solid + " non-air blocks.");
        if (solid == 0) {
            arenaProblem("De arena-box in '" + templateWorld.getName() + "' bevat geen enkel blok. Staan pos1/pos2 "
                    + "(" + am.describePos(true) + " → " + am.describePos(false) + ") op de juiste plek?");
            return;
        }

        for (int i = 0; i < teamIds.size(); i++) {
            String teamId = teamIds.get(i);
            var offset = plugin.getVoidWorldManager().pocketOffset(i);
            Location pasteOrigin = new Location(voidWorld, offset.getX(), 100, offset.getZ());
            try {
                ArenaPaster.pasteAtMinCorner(clipboard, pasteOrigin);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "[MobMayhem] Pasting the arena for team " + teamId + " failed", t);
                arenaProblem("Arena plakken voor team " + teamId + " mislukt: " + t.getClass().getSimpleName() + " (zie console).");
                continue;
            }
            teamPasteOrigins.put(teamId, pasteOrigin);
            Arena arena = am.buildForPastedPocket(teamId, voidWorld, pasteOrigin);
            if (arena != null) teamArenas.put(teamId, arena);
            else plugin.getLogger().severe("[MobMayhem] Failed to build arena for team " + teamId + " after paste.");
        }
    }

    /** Logs an arena-loading problem AND tells online admins, so nobody has to dig through the console. */
    private void arenaProblem(String message) {
        plugin.getLogger().severe("[MobMayhem] " + message);
        for (Player op : Bukkit.getOnlinePlayers())
            if (op.isOp() || op.hasPermission("mayhem.admin")) op.sendMessage("§c[Mob Mayhem] " + message);
    }

    @Override
    protected void onCountdownStart() {
        tellKey("mm.intro");
    }

    @Override
    protected void onGameStart() {
        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        WaveDefinition wave1 = waves.isEmpty() ? null : waves.get(0);
        if (wave1 == null) {
            plugin.getLogger().severe("[MobMayhem] No waves configured — ending game immediately.");
            end();
            return;
        }

        // Arenas were already pasted synchronously in onPrepare() — no async wait needed.
        for (TeamGameState ts : teamStates.values()) {
            Arena arena = teamArenas.get(ts.getTeamId());
            if (arena == null) {
                plugin.getLogger().severe("[MobMayhem] Team " + ts.getTeamId() + " has no pasted arena — eliminating.");
                tellKey("mm.no-arena", teamLabel(ts.getTeamId()));
                onTeamEliminated(ts.getTeamId());
                continue;
            }
            beginTeamPresentation(ts, arena, wave1);
        }

        // Heartbeat. The game ends when: every team is eliminated or has cleared all waves; or a time limit
        // passes (game.max-duration-seconds); or, once one team finished everything, the others get
        // game.finish-grace-seconds more and then the game closes.
        gameStartMs = System.currentTimeMillis();
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long active = teamStates.values().stream()
                    .filter(ts -> !ts.isEliminated() && !finishedTeams.contains(ts.getTeamId())).count();
            if (active == 0) { end(); return; }

            long now = System.currentTimeMillis();
            int maxSec = plugin.getConfig().getInt("game.max-duration-seconds", 1800);
            if (maxSec > 0 && now - gameStartMs > maxSec * 1000L) {
                tellKey("mm.time-limit");
                end();
                return;
            }
            int graceSec = plugin.getConfig().getInt("game.finish-grace-seconds", 120);
            if (firstFinishMs > 0 && graceSec > 0 && now - firstFinishMs > graceSec * 1000L) {
                tellKey("mm.grace-over");
                end();
                return;
            }
            updateBossBar();
        }, 20L, 20L);
    }

    /**
     * Runs the shared start-flow (teleport+freeze → intro → arena flyover →
     * tutorial tips → countdown → GO) for one team before their wave 1 —
     * giving MobMayhem the same polished start QuakeCraft/TNTTag have,
     * instead of a bare "GO!" title with no warning.
     */
    private void beginTeamPresentation(TeamGameState ts, Arena arena, WaveDefinition wave1) {
        List<Player> parts = ts.getAlivePlayers().stream()
                .map(Bukkit::getPlayer).filter(java.util.Objects::nonNull).toList();

        Location spawn = arena.getPlayerSpawn();
        warnIfEmbedded("player spawn", ts.getTeamId(), spawn);
        if (spawn != null) parts.forEach(p -> p.teleport(spawn));

        StandardStartFlow flow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning() && !ts.isEliminated(),
                // Every team runs its own flow: tell only that team, or each line shows up once per team (x4).
                msg -> parts.forEach(p -> p.sendMessage(msg)),
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§4§lMOB MAYHEM"; }
                    @Override public String introSubtitle() { return "@mm.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@mm.tut.1", "@mm.tut.2", "@mm.tut.3", "@mm.tut.4");
                    }
                    @Override public Location flyoverCenter() { return spawn; }
                    @Override public void onFinished() {
                        for (Player p : parts) plugin.getKitManager().giveStarterKit(p);
                        startWaveForTeam(ts, arena, wave1);
                    }
                });
        teamStartFlows.put(ts.getTeamId(), flow);
        flow.prepareAndFreeze();
        flow.start();
    }

    /** Removes every non-player creature near {@code centre} that is not one of this match's wave mobs. */
    private void clearStrayMobs(Location centre) {
        double r = plugin.getConfig().getDouble("game.clear-radius", 160);
        var key = new NamespacedKey(plugin, WaveExecutor.MOB_TEAM_KEY);
        for (org.bukkit.entity.Entity e : centre.getWorld().getNearbyEntities(centre, r, 128, r)) {
            if (e instanceof Player || !(e instanceof org.bukkit.entity.LivingEntity)) continue;
            if (e.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING)) continue;   // a wave mob of a running team
            if (e instanceof org.bukkit.entity.ArmorStand) continue;
            e.remove();
        }
    }

    private void startWaveForTeam(TeamGameState ts, Arena arena, WaveDefinition wave) {
        // Players are never put into the cloned arena world otherwise — without
        // this, mobs spawn in an empty world nobody is standing in, and vanilla
        // Minecraft's no-nearby-player despawn rule removes them within a tick
        // or two, making every wave look like it "clears" instantly.
        Location playerSpawn = arena.getPlayerSpawn();
        warnIfEmbedded("player spawn", ts.getTeamId(), playerSpawn);
        if (playerSpawn != null) {
            for (UUID uuid : ts.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.teleport(playerSpawn);
            }
        } else {
            plugin.getLogger().severe("[MobMayhem] Team " + ts.getTeamId()
                    + " has no player spawn set — players were NOT teleported into the arena!");
        }

        if (plugin.getUpgradeManager() != null) {
            for (UUID uuid : ts.getAlivePlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.getUpgradeManager().reapply(p);
            }
        }

        // No stray mobs from an earlier match or team in the arena before the wave begins.
        if (playerSpawn != null && playerSpawn.getWorld() != null) clearStrayMobs(playerSpawn);

        final String teamId = ts.getTeamId();
        WaveExecutor exec = new WaveExecutor(plugin, ts, arena, wave,
                survived -> {
                    if (survived) onTeamWaveComplete(teamId, wave.getWaveNumber());
                    else          onTeamEliminated(teamId);
                });
        teamExecutors.put(teamId, exec);
        exec.start();

        teamPowerupSpawners.computeIfAbsent(teamId, id -> {
            PowerupSpawner spawner = new PowerupSpawner(plugin, arena);
            spawner.start();
            return spawner;
        });
    }

    @Override
    protected void onGameEnd() {
        if (heartbeatTask != null) { heartbeatTask.cancel(); heartbeatTask = null; }
        teamExecutors.values().forEach(WaveExecutor::cancel);
        teamExecutors.clear();
        teamPowerupSpawners.values().forEach(PowerupSpawner::stop);
        teamPowerupSpawners.clear();
        teamStartFlows.values().forEach(StandardStartFlow::cancel);
        teamStartFlows.clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        // Rank by waves survived desc, tiebreak: total kills
        List<TeamGameState> ranked = new ArrayList<>(teamStates.values());
        ranked.sort((a, b) -> {
            int diff = Integer.compare(b.getHighestWaveSurvived(), a.getHighestWaveSurvived());
            return diff != 0 ? diff : Integer.compare(b.getMobsKilled(), a.getMobsKilled());
        });

        // Team placements
        for (int i = 0; i < ranked.size(); i++) {
            api.points().awardTeamPlacement(ranked.get(i).getTeamId(), i + 1, registration.getId());
        }

        // Build per-player finish order based on their team's rank
        Map<String, Integer> teamRankMap = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) teamRankMap.put(ranked.get(i).getTeamId(), i + 1);

        List<UUID> allPlayers = new ArrayList<>();
        UUID mvpUuid = null; String mvpName = null; int topKills = 0;
        int playerRank = 1;

        for (TeamGameState ts : ranked) {
            for (UUID uuid : ts.getAllPlayers()) {
                allPlayers.add(uuid);
                Player p = Bukkit.getPlayer(uuid);
                String name = p != null ? p.getName() : uuid.toString();
                api.points().awardPlayerPlacement(uuid, playerRank, allPlayers.size(), registration.getId());
                api.games().recordGameParticipation(uuid, name, registration.getId(),
                        teamRankMap.getOrDefault(ts.getTeamId(), 99) == 1);
            }
            playerRank += ts.getAllPlayers().size();
        }

        String winnerDesc = ranked.isEmpty() ? "No winner"
                : (ranked.get(0).getHighestWaveSurvived() + " waves — team " + teamLabel(ranked.get(0).getTeamId()));

        if (plugin.getUpgradeManager() != null) plugin.getUpgradeManager().clearAll();
        returnToLobby();
        clearAllPockets();
        teamStates.clear();
        teamArenas.clear();
        playerMobKills.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, allPlayers);
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
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage(api.tr(player.getUniqueId(), "game.state-restored"));
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        java.util.UUID id = viewer.getUniqueId();
        java.util.List<String> l = new java.util.ArrayList<>();
        var kt = plugin.getKmcCore().getTeamManager().getTeamByPlayer(id);
        TeamGameState mine = kt != null ? teamStates.get(kt.getId()) : null;
        if (mine != null) {
            l.add(api.tr(id, "sb.mob.your-team"));
            l.add(api.tr(id, "sb.mob.wave", mine.getCurrentWave()));
            l.add(api.tr(id, "sb.mob.mobs-left", mine.getActiveMobCount()));
            l.add(api.tr(id, "sb.mob.mob-kills", mine.getMobsKilled()));
            l.add("");
        }
        l.add(api.tr(id, "sb.mob.teams"));
        teamStates.values().stream()
                .sorted((a, b) -> Math.max(b.getCurrentWave(), b.getHighestWaveSurvived())
                        - Math.max(a.getCurrentWave(), a.getHighestWaveSurvived()))
                .limit(5)
                .forEach(ts -> l.add(api.tr(id, "sb.mob.team-entry", teamLabel(ts.getTeamId()),
                        Math.max(ts.getCurrentWave(), ts.getHighestWaveSurvived()))));
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Mob Mayhem"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Mob Mayhem arena not ready: " + plugin.getArenaManager().getReadinessReport());
                if (!plugin.getWorldCloner().templateExists())
                    r.addError("Template world '" + plugin.getWorldCloner().getTemplateWorldName() + "' not found.");
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void onMobKill(UUID killer, int mobPoints, boolean wasBoss) {
        if (!getState().isRunning()) return;
        api.points().givePoints(killer, mobPoints, PointAward.Reason.KILL, registration.getId());
        // Credit the kill to the team state too
        for (TeamGameState ts : teamStates.values()) {
            if (ts.getAlivePlayers().contains(killer)) {
                ts.recordKill(mobPoints);
                break;
            }
        }
        try { statsService.recordKill(killer); } catch (Throwable ignored) {}

        Player p = Bukkit.getPlayer(killer);
        int kills = playerMobKills.merge(killer, 1, Integer::sum);
        if (p != null) {
            if (wasBoss) fireObjective(p, GameObjectiveEvent.Type.BOSS_KILLED);
            if (kills == 50)  fireObjective(p, GameObjectiveEvent.Type.KILL_MILESTONE);
            if (kills == 100) fireObjective(p, GameObjectiveEvent.Type.KILL_MILESTONE_LEGENDARY);
        }
    }

    public Map<String, TeamGameState> getTeamStates() { return Collections.unmodifiableMap(teamStates); }

    // ── Callbacks from WaveExecutor ───────────────────────────────────────────

    private void onTeamWaveComplete(String teamId, int waveNumber) {
        if (!getState().isRunning()) return;
        TeamGameState ts = teamStates.get(teamId);
        if (ts == null) return;

        int wavePts = plugin.getConfig().getInt("points.per-wave", 100);
        for (UUID uuid : ts.getAlivePlayers()) {
            api.points().givePoints(uuid, wavePts, PointAward.Reason.OBJECTIVE, registration.getId());
        }
        tellKey("mm.wave-cleared", teamLabel(teamId), waveNumber, wavePts);

        for (UUID uuid : ts.getAlivePlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            fireObjective(p, GameObjectiveEvent.Type.WAVE_SURVIVED);
            if (waveNumber == 5) fireObjective(p, GameObjectiveEvent.Type.NO_DEATH_MILESTONE);
        }

        int maxWaves = plugin.getConfig().getInt("game.max-waves", waves.size());
        WaveDefinition next = getWaveByNumber(waveNumber + 1);
        if (waveNumber >= maxWaves || next == null) {
            tellKey("mm.all-cleared", teamLabel(teamId));
            finishedTeams.add(teamId);
            if (firstFinishMs == 0) firstFinishMs = System.currentTimeMillis();
            long stillPlaying = teamStates.values().stream()
                    .filter(t -> !t.isEliminated() && !finishedTeams.contains(t.getTeamId())).count();
            if (stillPlaying == 0) end();
            else tellKey("mm.others-grace", plugin.getConfig().getInt("game.finish-grace-seconds", 120));
            updateBossBar();
            return;
        }

        Arena arena = teamArenas.get(teamId);
        if (arena == null) {
            plugin.getLogger().warning("[MobMayhem] Lost arena for team " + teamId
                    + " when starting wave " + next.getWaveNumber() + ".");
            updateBossBar();
            return;
        }

        int intermissionSeconds = plugin.getConfig().getInt("game.intermission-seconds", 5);
        teamExecutors.remove(teamId);
        Runnable startNext = () -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!getState().isRunning() || ts.isEliminated()) return;
            startWaveForTeam(ts, arena, next);
        }, intermissionSeconds * 20L);

        // Upgrade pick (1 of 3 perks per player) before the next wave — makes later waves easier to handle.
        var upgrades = plugin.getUpgradeManager();
        if (upgrades != null && plugin.getConfig().getBoolean("upgrades.enabled", true)) {
            upgrades.offer(ts, waveNumber, plugin.getConfig().getInt("upgrades.choose-seconds", 15), startNext);
        } else {
            startNext.run();
        }

        updateBossBar();
    }

    private void onTeamEliminated(String teamId) {
        if (!getState().isRunning()) return;
        tellKey("mm.team-out", teamLabel(teamId));
        PowerupSpawner spawner = teamPowerupSpawners.remove(teamId);
        if (spawner != null) spawner.stop();
        long alive = teamStates.values().stream().filter(ts -> !ts.isEliminated()).count();
        if (alive == 0) end();
        updateBossBar();
    }

    /**
     * Warns loudly (console + in-chat to the team) if {@code loc} is inside a
     * solid block — a mob or player teleported here will spawn embedded in
     * terrain/suffocate, which looks exactly like "spawning in the ground".
     * Since the cloned arena is a byte-for-byte copy of the template world,
     * this means the recorded coordinate is bad in the TEMPLATE too — the
     * fix is re-running {@code /mm setspawn}/{@code /mm addmobspawn} while
     * standing in genuinely open air there, not a bug in the cloning itself.
     */
    private void warnIfEmbedded(String what, String teamId, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        var block = loc.getBlock();
        if (!block.getType().isSolid() && !block.getRelative(0, 1, 0).getType().isSolid()) return;
        String msg = "[MobMayhem] Team " + teamId + "'s " + what + " at (" + loc.getBlockX() + ", "
                + loc.getBlockY() + ", " + loc.getBlockZ() + ") in world " + loc.getWorld().getName()
                + " is INSIDE solid terrain (" + block.getType() + ") — re-run /mm setspawn or "
                + "/mm addmobspawn there while standing in open air in the TEMPLATE world.";
        plugin.getLogger().severe(msg);
        broadcast("§c⚠ " + msg);
    }

    private void fireObjective(Player p, GameObjectiveEvent.Type type) {
        try { new GameObjectiveEvent(p, registration.getId(), type).callEvent(); }
        catch (Throwable t) { plugin.getLogger().warning("GameObjectiveEvent failed: " + t); }
    }

    /** Checks whether exactly one player remains alive across all teams and, if so, grants Last Stand. */
    public void checkLastStanding() {
        List<UUID> allAlive = new ArrayList<>();
        for (TeamGameState ts : teamStates.values()) allAlive.addAll(ts.getAlivePlayers());
        if (allAlive.size() != 1) return;
        Player p = Bukkit.getPlayer(allAlive.get(0));
        if (p != null) fireObjective(p, GameObjectiveEvent.Type.LAST_STANDING);
    }

    public WaveDefinition getWaveByNumber(int number) {
        return waves.stream().filter(w -> w.getWaveNumber() == number).findFirst().orElse(null);
    }

    public List<WaveDefinition> getWaves() { return Collections.unmodifiableList(waves); }

    public PowerupSpawner getPowerupSpawner(String teamId) { return teamPowerupSpawners.get(teamId); }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Team name in its own colour (e.g. "§cRode Ratten"); falls back to the raw id if the team is unknown. */
    /** Team name in its own colour, for menus outside this class. */
    public String teamLabelFor(String teamId) { return teamLabel(teamId); }

    /** Team name in its own colour (e.g. "§cRode Ratten"); falls back to the raw id if the team is unknown. */
    private String teamLabel(String teamId) {
        try {
            return api.teams().getTeam(teamId).map(t -> t.getColor() + t.getDisplayName()).orElse(teamId);
        } catch (Throwable t) {
            return teamId;
        }
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        StringBuilder sb = new StringBuilder(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Mob Mayhem §8| ");
        teamStates.values().forEach(ts ->
                sb.append("§e").append(teamLabel(ts.getTeamId()))
                  .append(" §7w").append(ts.getHighestWaveSurvived())
                  .append(ts.isEliminated() ? " §c✗" : " §a✓").append(" §8| "));
        bossBar.setTitle(sb.toString());
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        teamStates.values().forEach(ts -> ts.getAllPlayers().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.setHealth(20); p.setFoodLevel(20);
            nl.kmc.game.api.GamePlayerUtil.teleportToLobby(p, lobby);
        }));
    }

    /**
     * Clears every team's pasted pocket back to air in the shared void
     * world — the void world itself is persistent (never deleted), only
     * each pocket's pasted content needs tidying between games.
     */
    private void clearAllPockets() {
        int[] size = plugin.getArenaManager().getBoxSize();
        for (Location origin : teamPasteOrigins.values()) {
            if (origin == null || origin.getWorld() == null) continue;
            ArenaPaster.clearPocket(origin.getWorld(), origin, size[0], size[1], size[2]);
        }
        teamPasteOrigins.clear();
    }
}
