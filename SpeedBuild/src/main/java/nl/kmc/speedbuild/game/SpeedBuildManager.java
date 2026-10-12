package nl.kmc.speedbuild.game;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.speedbuild.SpeedBuildPlugin;
import nl.kmc.speedbuild.schematic.SchematicLoader;
import nl.kmc.speedbuild.schematic.WorldEditAdapter;
import nl.kmc.speedbuild.scoring.BuildScoreEngine;
import nl.kmc.speedbuild.setup.ArenaConfig;
import nl.kmc.speedbuild.ui.ActionBarManager;
import nl.kmc.speedbuild.ui.BossBarManager;
import nl.kmc.speedbuild.ui.InventoryButtons;
import nl.kmc.speedbuild.util.PlayerTeleportUtil;
import nl.kmc.speedbuild.util.RegionUtil;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * V2 Speed Build Challenge manager. Each player runs solo through 10 schematic
 * builds in their own auto-offset slot. Scoring is fully deterministic
 * (block-by-block schematic comparison + time bonus − block penalty). Per-player
 * scores are pushed to the player and their KMC team total.
 */
public final class SpeedBuildManager extends BaseGameManager {

    private final SpeedBuildPlugin plugin;
    private final ArenaConfig      arena;
    private final SchematicLoader  loader;
    private final BuildScoreEngine engine;
    private final InventoryButtons buttons;
    private final BossBarManager   bossBars = new BossBarManager();

    private final Map<UUID, SpeedBuildSession> sessions = new LinkedHashMap<>();
    private final Set<UUID> readyToFinish = new HashSet<>();
    private StandardStartFlow startFlow;

    // Uniform slot dimensions (max across all schematics) so slots never overlap.
    private int slotDx = 16, slotDy = 16, slotDz = 16;

    public SpeedBuildManager(SpeedBuildPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin  = plugin;
        this.arena   = plugin.getArena();
        this.loader  = plugin.getLoader();
        this.engine  = new BuildScoreEngine(plugin.getConfig());
        this.buttons = plugin.getButtons();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        sessions.clear();
        readyToFinish.clear();
        computeSlotDimensions();

        int slot = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            sessions.put(p.getUniqueId(), new SpeedBuildSession(p.getUniqueId(), p.getName(), slot++));
            GamePlayerUtil.resetPlayer(p);
            PlayerTeleportUtil.toSpawn(p, arena.getSpawn());
            bossBars.show(p);
        }

        List<Player> parts = sessions.keySet().stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).toList();

        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§e§lSPEED BUILD"; }
                    @Override public String introSubtitle() { return "@speedbuild.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@speedbuild.tut.1", "@speedbuild.tut.2", "@speedbuild.tut.3", "@speedbuild.tut.4");
                    }
                    @Override public Location flyoverCenter() { return arena.getSpawn(); }
                    @Override public void onFinished() {
                        for (SpeedBuildSession s : sessions.values()) loadBuild(s, 0);
                    }
                });
        startFlow.prepareAndFreeze();
    }

    @Override
    protected void onCountdownStart() {
        // Presentation (intro/flyover/tutorial/countdown) runs from onGameStart instead.
    }

    @Override
    protected void onGameStart() {
        startFlow.start();
    }

    @Override
    protected void onGameEnd() {
        // Rank by total score for placement points + the result event.
        List<SpeedBuildSession> ranked = new ArrayList<>(sessions.values());
        ranked.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));

        List<UUID> finishOrder = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            SpeedBuildSession s = ranked.get(i);
            finishOrder.add(s.getPlayer());
            statsService.recordPlacement(s.getPlayer(), i + 1);
            // Anyone who never pressed FINISH still gets their accumulated score banked.
            if (!s.isFinished()) bankScore(s, i == 0);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            buttons.clear(p);
            PlayerTeleportUtil.toSpawn(p, plugin.getKmcCore().getArenaManager().getLobby());
        }
        bossBars.clearAll();
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }

        SpeedBuildSession winner = ranked.isEmpty() ? null : ranked.get(0);
        String winnerDesc = winner != null ? winner.getName() + " wint Speed Build!" : "Geen winnaar";
        UUID   mvpUuid    = winner != null ? winner.getPlayer() : null;
        String mvpName    = winner != null ? winner.getName() : null;

        fireResult(winnerDesc, mvpUuid, mvpName, finishOrder);
        sessions.clear();
    }

    // ── Build stage flow ──────────────────────────────────────────────────────

    private void loadBuild(SpeedBuildSession session, int index) {
        Player p = Bukkit.getPlayer(session.getPlayer());
        List<BuildDefinition> builds = arena.getBuilds();
        if (p == null || index >= builds.size()) return;

        BuildDefinition def = builds.get(index);
        Clipboard clip = loader.load(def.schematic());
        if (clip == null) {
            if (p != null) p.sendMessage(api.tr(p.getUniqueId(), "speedbuild.schematic-missing", def.schematic()));
            return;
        }
        int[] dims = WorldEditAdapter.dimensions(clip);

        Location buildMin     = RegionUtil.slotMin(arena.getAnchor(), session.getSlotIndex(), slotDx, arena.getSlotGap());
        Location blueprintMin = RegionUtil.blueprintMin(buildMin, slotDz, arena.getSlotGap());

        // Reset both areas, then show the reference blueprint.
        RegionUtil.clear(buildMin, slotDx, slotDy, slotDz);
        RegionUtil.clear(blueprintMin, slotDx, slotDy, slotDz);
        WorldEditAdapter.pasteAtMinCorner(clip, blueprintMin, true);

        session.setGeometry(buildMin, blueprintMin);
        session.markBuildStart();

        PlayerTeleportUtil.toStage(p, RegionUtil.standOn(buildMin, dims[0], dims[2]));
        buttons.give(p, index == builds.size() - 1);
        bossBars.update(p, index + 1, session.getTotalScore());

        UUID pid = p.getUniqueId();
        p.sendMessage(api.tr(pid, "speedbuild.build-line", index + 1, def.name(), def.difficulty()));
        p.sendTitle(api.tr(pid, "speedbuild.build-title", index + 1), "§f" + def.name(), 5, 35, 8);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
        ActionBarManager.send(p, api.tr(pid, "speedbuild.copy-hint"));
    }

    /** GREEN WOOL → score the current build and advance (or arm FINISH on the last). */
    public void completeBuild(Player p) {
        if (!getState().isRunning()) return;
        SpeedBuildSession s = sessions.get(p.getUniqueId());
        if (s == null || s.isFinished() || readyToFinish.contains(p.getUniqueId())) return;

        int index = s.getCurrentBuildIndex();
        BuildDefinition def = arena.getBuilds().get(index);
        Clipboard clip = loader.load(def.schematic());
        if (clip == null) return;

        long time = System.currentTimeMillis() - s.getBuildStartTime();
        BuildResult result = engine.score(index, def, clip, s.getBuildMin(), time);
        s.recordResult(result);

        UUID pid = p.getUniqueId();
        p.sendMessage(api.tr(pid, "speedbuild.scored", index + 1,
                String.format("%.1f", result.accuracyPercent()), String.format("%.0f", result.finalScore()),
                String.format("%.0f", result.bonusPoints()), String.format("%.0f", result.penaltyPoints())));
        p.sendTitle(String.format("§a%.0f%%", result.accuracyPercent()),
                api.tr(pid, "speedbuild.score-sub", (int) result.finalScore()), 3, 30, 8);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);

        // Clear the area before the next build (and tidy the blueprint).
        RegionUtil.clear(s.getBuildMin(), slotDx, slotDy, slotDz);
        RegionUtil.clear(s.getBlueprintMin(), slotDx, slotDy, slotDz);

        if (s.advance()) {
            loadBuild(s, s.getCurrentBuildIndex());
        } else {
            // Build 10 scored — leave only the GOLD finish button.
            readyToFinish.add(p.getUniqueId());
            buttons.giveFinishOnly(p);
            p.sendMessage(api.tr(pid, "speedbuild.all-done"));
            ActionBarManager.send(p, api.tr(pid, "speedbuild.all-done-bar"));
        }
    }

    /** GOLD BLOCK → finish the session and bank the score to the team. */
    public void finishSession(Player p) {
        if (!getState().isRunning()) return;
        SpeedBuildSession s = sessions.get(p.getUniqueId());
        if (s == null || s.isFinished()) return;
        // Only allowed once the final build has actually been scored.
        if (!readyToFinish.contains(p.getUniqueId()) && s.getResults().size() < arena.getBuilds().size()) {
            p.sendMessage(api.tr(p.getUniqueId(), "speedbuild.finish-first"));
            return;
        }

        bankScore(s, false);
        s.finish();
        readyToFinish.remove(p.getUniqueId());
        buttons.clear(p);
        showSummary(p, s);
        PlayerTeleportUtil.toSpawn(p, arena.getSpawn());
        bossBars.remove(p);

        // End the game once everyone is done.
        if (sessions.values().stream().allMatch(SpeedBuildSession::isFinished))
            Bukkit.getScheduler().runTaskLater(plugin, this::end, 40L);
    }

    /** RED WOOL → re-show the blueprint for the current build. */
    public void showBlueprint(Player p) {
        if (!getState().isRunning()) return;
        SpeedBuildSession s = sessions.get(p.getUniqueId());
        if (s == null || s.isFinished() || s.getBlueprintMin() == null) return;
        BuildDefinition def = arena.getBuilds().get(s.getCurrentBuildIndex());
        Clipboard clip = loader.load(def.schematic());
        if (clip != null) {
            RegionUtil.clear(s.getBlueprintMin(), slotDx, slotDy, slotDz);
            WorldEditAdapter.pasteAtMinCorner(clip, s.getBlueprintMin(), true);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
            ActionBarManager.send(p, api.tr(p.getUniqueId(), "speedbuild.blueprint-shown"));
        }
    }

    private void bankScore(SpeedBuildSession s, boolean isTop) {
        int pts = (int) Math.round(s.getTotalScore());
        api.points().givePoints(s.getPlayer(), pts, PointAward.Reason.OBJECTIVE, registration.getId());
        statsService.recordPointsEarned(s.getPlayer(), pts);
        api.games().recordGameParticipation(s.getPlayer(), s.getName(), registration.getId(), isTop);
    }

    private void showSummary(Player p, SpeedBuildSession s) {
        BuildResult best = s.bestBuild(), worst = s.worstBuild();
        UUID pid = p.getUniqueId();
        p.sendMessage("§8§m                                        ");
        p.sendMessage(api.tr(pid, "speedbuild.sum.title"));
        p.sendMessage(api.tr(pid, "speedbuild.sum.total", (int) s.getTotalScore()));
        p.sendMessage(api.tr(pid, "speedbuild.sum.accuracy", String.format("%.1f", s.averageAccuracy())));
        if (best != null)  p.sendMessage(api.tr(pid, "speedbuild.sum.best", best.buildName(), String.format("%.0f", best.finalScore())));
        if (worst != null) p.sendMessage(api.tr(pid, "speedbuild.sum.worst", worst.buildName(), String.format("%.0f", worst.finalScore())));
        p.sendMessage(api.tr(pid, "speedbuild.sum.time", String.format("%.1f", s.totalTimeSeconds())));
        p.sendMessage("§8§m                                        ");
        p.sendTitle(api.tr(pid, "speedbuild.done.title"), api.tr(pid, "speedbuild.done.sub", (int) s.getTotalScore()), 8, 50, 12);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void computeSlotDimensions() {
        int dx = 8, dy = 8, dz = 8;
        for (BuildDefinition def : arena.getBuilds()) {
            Clipboard c = loader.load(def.schematic());
            if (c == null) continue;
            int[] d = WorldEditAdapter.dimensions(c);
            dx = Math.max(dx, d[0]); dy = Math.max(dy, d[1]); dz = Math.max(dz, d[2]);
        }
        slotDx = dx; slotDy = dy; slotDz = dz;
    }

    // ── Anti-exploit: is this location inside the player's active build slot? ──

    public boolean isInsideBuildArea(Player p, Location loc) {
        SpeedBuildSession s = sessions.get(p.getUniqueId());
        if (s == null || s.getBuildMin() == null) return false;
        return RegionUtil.contains(s.getBuildMin(), slotDx, slotDy, slotDz, loc);
    }

    public boolean hasActiveSession(Player p) {
        SpeedBuildSession s = sessions.get(p.getUniqueId());
        return s != null && !s.isFinished();
    }

    // ── Scoreboard ────────────────────────────────────────────────────────────

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        SpeedBuildSession s = sessions.get(viewer.getUniqueId());
        java.util.List<String> l = new java.util.ArrayList<>();
        UUID vid = viewer.getUniqueId();
        if (s == null) { l.add(api.tr(vid, "speedbuild.sb.spectator")); return l; }
        l.add(api.tr(vid, "speedbuild.sb.build", Math.min(10, s.getCurrentBuildIndex() + 1)));
        l.add(api.tr(vid, "speedbuild.sb.score", (int) s.getTotalScore()));
        if (!s.getResults().isEmpty())
            l.add(api.tr(vid, "speedbuild.sb.accuracy", String.format("%.0f", s.averageAccuracy())));
        l.add("");
        l.add(s.isFinished() ? api.tr(vid, "speedbuild.sb.done") : api.tr(vid, "speedbuild.sb.building"));
        return l;
    }

    // ── Reconnect / validation ────────────────────────────────────────────────

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState st = new PlayerGameState();
        st.location = player.getLocation();
        SpeedBuildSession s = sessions.get(player.getUniqueId());
        if (s != null) st.extra.put("buildIndex", s.getCurrentBuildIndex());
        return st;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        SpeedBuildSession s = sessions.get(player.getUniqueId());
        if (s != null && !s.isFinished()) {
            bossBars.show(player);
            loadBuild(s, s.getCurrentBuildIndex());
        } else {
            PlayerTeleportUtil.toSpawn(player, arena.getSpawn());
        }
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Speed Build"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                for (String issue : arena.issues()) r.addError(issue);
                return r;
            }
        };
    }

    public Map<UUID, SpeedBuildSession> getSessions() { return Collections.unmodifiableMap(sessions); }
}
