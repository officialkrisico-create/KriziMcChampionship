package nl.kmc.kmccore.host;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.core.event.GameObjectiveEvent;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.host.HostDialogues.Audience;
import nl.kmc.kmccore.host.HostDialogues.Dialogue;
import nl.kmc.kmccore.host.HostQueue.Speech;
import nl.kmc.kmccore.host.HostQueue.SpeechLine;
import nl.kmc.kmccore.managers.AutomationManager;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import nl.kmc.kmccore.scoring.PointsLedger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The KMC host. It is the voice of the lobby presenter: it announces, explains and tells stories above its
 * head, but it never starts, ends or changes anything in the tournament itself.
 *
 * <p><b>Activation.</b> Everything automatic goes through {@link #mayAuto()}: the host only speaks by itself
 * while a KMCAuto session runs ({@code /kmcauto start}) or while the end sequence is presented. Placing the
 * NPC, a player joining or a plugin reload never starts a speech; the state is in memory only, so a reload
 * comes back as IDLE like the automation itself.
 *
 * <p><b>One voice.</b> Every speech goes through one {@link HostQueue}; one task chain plays the current
 * speech line by line and is invalidated by a generation counter, so nothing outlives a session.
 */
public final class HostManager implements Listener {

    private final KMCCore plugin;
    private final HostStateMachine sm = new HostStateMachine();
    private final HostContent content;
    private final HostQueue queue;
    private final Random rnd = new Random();
    private final Set<String> warnedPlaceholders = new HashSet<>();
    private final Map<String, Sound> soundCache = new HashMap<>();
    private final HostDirector director;

    // playback
    private Speech current;
    private int lineIdx;
    private BukkitTask playTask;
    private int playGen;
    private BukkitTask finishTimeout;

    // session bookkeeping
    private String lastLeaderId;
    private int standingsCounter;
    private long lastLiveMs;
    private String lastIntroGameId;
    private final Map<UUID, Integer> loreCursor = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();

    public HostManager(KMCCore plugin) {
        this.plugin = plugin;
        this.content = new HostContent(plugin);
        this.queue = new HostQueue(plugin.getConfig().getInt("host.dialogue.queue-size", 16));
        this.director = new HostDirector(plugin, this::sound);
        content.load();
        reportWarnings();
        applyConfig();
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private boolean flag(String path, boolean def) { return plugin.getConfig().getBoolean("host." + path, def); }
    private int num(String path, int def) { return plugin.getConfig().getInt("host." + path, def); }
    private double dbl(String path, double def) { return plugin.getConfig().getDouble("host." + path, def); }
    private String str(String path, String def) { return plugin.getConfig().getString("host." + path, def); }
    private String lang() { return str("language", "nl"); }

    private void applyConfig() {
        sm.setEnabled(flag("enabled", true));
        var presenter = plugin.getPresenter();
        if (presenter != null) {
            presenter.configure(str("npc.name", "&6&lKMC Host"), flag("npc.show-name", true),
                    dbl("npc.dialogue-height", 2.6), str("npc.skin", ""));
        }
    }

    private void reportWarnings() {
        for (String w : content.warnings()) plugin.getLogger().warning("[KMC] host.yml: " + w);
    }

    /** Reloads host.yml and the {@code host:} settings. Safe at any time: it registers nothing new. */
    public void reload() {
        content.load();
        reportWarnings();
        warnedPlaceholders.clear();
        applyConfig();
    }

    public String slogan(String teamId) { return content.slogan(teamId); }

    // ── State ─────────────────────────────────────────────────────────────────

    public HostState state() { return sm.state(); }

    public boolean canSpeakAutomatically() { return mayAuto(); }

    public boolean setEnabled(boolean on) {
        boolean changed = sm.setEnabled(on);
        if (!on) { stopCurrent(); queue.clearPending(); }
        return changed;
    }

    private boolean mayAuto() {
        if (!sm.isEnabled() || !flag("dialogue.enabled", true)) return false;
        if (sm.canSpeakAutomatically()) return true;
        return !flag("activation.require-kmcauto", true);
    }

    private AutomationManager auto() { return plugin.getAutomationManager(); }

    private int gamesPlayed() { return auto() != null ? auto().getGamesPlayedTotal() : 0; }

    // ── Lifecycle (called by the automation) ─────────────────────────────────

    /** {@code /kmcauto start}: the host becomes active — once. The opening itself follows the first stage. */
    public void onAutomationStart() {
        if (!flag("activation.start-on-kmcauto-start", true)) return;
        if (!sm.startSession()) return;           // a second callback for the same start does nothing
        stopCurrent();
        content.invalidateCaches();
        queue.reset();
        lastLeaderId = null;
        standingsCounter = 0;
        lastLiveMs = 0;
        lastIntroGameId = null;
        cancelFinishTimeout();
        takePodium();
    }

    /**
     * The host presents from the podium (beside it) for the whole session, so the opening, the team showcase,
     * the places and the awards all happen in the same spot. Without a podium it stays where it was placed.
     */
    private void takePodium() {
        var pod = plugin.getPodium();
        var presenter = plugin.getPresenter();
        if (pod == null || presenter == null || !pod.isSet() || !presenter.isPlaced()) return;
        if (!flag("podium.host-on-podium", true)) return;
        var where = pod.hostSpot();
        if (where != null) presenter.takeStage(where);
    }

    /** The team showcase starts: the host steps behind the teams, in the middle of the gold column. */
    public void onShowcaseBegin() {
        var pod = plugin.getPodium();
        var presenter = plugin.getPresenter();
        if (pod == null || presenter == null || !pod.isSet() || !presenter.isPlaced()) return;
        if (!flag("podium.host-on-podium", true)) return;
        var where = pod.showcaseHostSpot();
        if (where != null) presenter.takeStage(where);
    }

    /** The team showcase is over (or was skipped): back to the spot beside the podium. */
    public void onShowcaseEnd() {
        var presenter = plugin.getPresenter();
        if (presenter == null || !presenter.isOnStage()) return;
        if (flag("podium.host-on-podium", true)) takePodium();
        else presenter.leaveStage();
    }

    private void leavePodium() {
        var presenter = plugin.getPresenter();
        if (presenter != null && presenter.isOnStage()) presenter.leaveStage();
    }

    /** The coming stop is the natural end of the tournament: the host presents the end sequence first. */
    public void prepareFinishing() {
        if (flag("finale.enabled", true)) sm.finishOnNextStop();
    }

    /** {@code /kmcauto stop} or the end of the tournament. Pending speech is dropped; nothing comes back. */
    public void onAutomationStop() {
        if (!flag("activation.stop-on-kmcauto-end", true) && sm.state() == HostState.ACTIVE) return;
        sm.stopSession();                       // ACTIVE → IDLE, or → FINISHING after a natural end
        stopCurrent();
        queue.clearPending();
        if (sm.state() == HostState.FINISHING) armFinishTimeout();
        else { cancelFinishTimeout(); leavePodium(); }
    }

    /** The end sequence is done (or was cancelled): back to IDLE, silent until the next /kmcauto start. */
    public void onSessionEnd() {
        cancelFinishTimeout();
        if (sm.endFinishing()) {
            stopCurrent();
            queue.reset();
        }
        leavePodium();
    }

    private void armFinishTimeout() {
        cancelFinishTimeout();
        long ticks = Math.max(30, num("finale.max-seconds", 600)) * 20L;
        final int gen = sm.generation();
        finishTimeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gen == sm.generation()) onSessionEnd();
        }, ticks);
    }

    private void cancelFinishTimeout() {
        if (finishTimeout != null) { finishTimeout.cancel(); finishTimeout = null; }
    }

    /** Plugin disable: no tasks, no display. */
    public void shutdown() {
        cancelFinishTimeout();
        stopCurrent();
        queue.reset();
        leavePodium();
        if (plugin.getPresenter() != null) plugin.getPresenter().clearBubble();
    }

    // ── Hooks: presentation phases ───────────────────────────────────────────

    /** A stage of the opening presentation has actually begun. */
    public void onCeremonyStage(String phase) {
        if (!mayAuto() || !flag("intro.enabled", true)) return;
        String id = switch (phase) {
            case "opening" -> "opening";
            case "how-it-works" -> "how-it-works";
            case "tournament-overview" -> "tournament-overview";
            case "team-showcase" -> flag("team-intros.enabled", true) ? "teams-start" : null;
            case "game-lineup" -> "games-lineup";
            default -> null;
        };
        if (id == null) return;
        speak(id, HostValues.tournament(plugin, content), "stage:" + id, true, null, null, null);
    }

    /** The team showcase has reached {@code team}. */
    public void onTeamIntro(KMCTeam team) {
        if (!mayAuto() || !flag("team-intros.enabled", true) || team == null) return;
        Map<String, String> v = HostValues.tournament(plugin, content);
        HostValues.team(plugin, v, team);
        speak("team-intro", v, "team:" + team.getId(), true, null, team.getId(), null);
    }

    /** A game is about to start (pre-start countdown). */
    public void onGameIntro(KMCGame game, int countdownSeconds) {
        if (game == null) return;
        lastIntroGameId = game.getId();
        if (!mayAuto() || !flag("game-intros.enabled", true)) return;
        List<String> sections = plugin.getConfig().getStringList("host.game-intros.prestart-sections");
        if (sections.isEmpty()) sections = List.of("intro", "goal", "closing");
        Dialogue d = content.gameIntro(game.getId(), sections, num("game-intros.max-scoring-lines", 3));
        Map<String, String> v = HostValues.tournament(plugin, content);
        HostValues.game(v, game, content);
        Speech s = build(d, v, "game-intro:" + game.getId() + ":" + gamesPlayed(), null, null, null, null, true);
        if (s == null) return;
        s = fit(s, Math.max(5, countdownSeconds) * 20L);
        offer(s);
    }

    /** A game ended and its points are booked ({@code seq} = the points-ledger sequence). */
    public void onGameResults(String gameName, int seq) {
        if (!mayAuto() || !flag("results.enabled", true)) return;
        Map<String, String> v = HostValues.tournament(plugin, content);
        v.put("game", gameName);
        try {
            var entries = plugin.getPointsLedger().forGame(seq);
            var byTeam = PointsLedger.byTeam(entries);
            if (!byTeam.isEmpty()) {
                KMCTeam top = plugin.getTeamManager().getTeam(byTeam.keySet().iterator().next());
                if (top != null) v.put("game_winner", HostValues.colored(top));
            }
            var byPlayer = PointsLedger.byPlayer(entries);
            if (!byPlayer.isEmpty()) {
                var first = byPlayer.entrySet().iterator().next();
                var data = plugin.getPlayerDataManager().get(first.getKey());
                String name = data != null ? data.getName() : Bukkit.getOfflinePlayer(first.getKey()).getName();
                if (name != null) {
                    v.put("top_player", name);
                    v.put("top_player_points", String.valueOf(first.getValue()));
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Host: could not read the game results: " + t);
        }
        speak("results-game", v, "results:" + seq, true, null, null, null);
    }

    /** The leaderboard animation is over: the standings are final for this moment. */
    public void onStandings() {
        if (!mayAuto() || !flag("leaderboard-announcements.enabled", true)) return;
        var standings = plugin.getTeamManager().getTeamsSortedByPoints();
        if (standings.isEmpty() || standings.get(0).getPoints() <= 0) return;
        KMCTeam leader = standings.get(0);
        boolean newLeader = lastLeaderId != null && !leader.getId().equals(lastLeaderId);
        lastLeaderId = leader.getId();
        standingsCounter++;
        int every = Math.max(1, num("leaderboard-announcements.every-n-games", 1));
        if (!newLeader && standingsCounter % every != 0) return;
        int gap = standings.size() >= 2 ? leader.getPoints() - standings.get(1).getPoints() : Integer.MAX_VALUE;
        String id;
        if (newLeader && flag("leaderboard-announcements.announce-new-leader", true)) id = "standings-new-leader";
        else if (gap <= num("leaderboard-announcements.close-gap", 150)) id = "standings-close";
        else if (gap >= num("leaderboard-announcements.runaway-gap", 500)) id = "standings-runaway";
        else id = "standings-normal";
        speak(id, HostValues.tournament(plugin, content), "standings:" + gamesPlayed() + ":" + id, true, null, null, null);
    }

    public void onFinaleAnnounce(KMCTeam a, KMCTeam b) {
        if (!mayAuto() || !flag("finale.enabled", true) || a == null || b == null) return;
        Map<String, String> v = HostValues.tournament(plugin, content);
        v.put("finalist_a", HostValues.colored(a));
        v.put("finalist_b", HostValues.colored(b));
        speak("finale-announce", v, "finale:announce", true, null, null, null);
    }

    /** The podium reveals 3rd or 2nd place. */
    public void onPlaceReveal(KMCTeam team, int place) {
        if (!mayAuto() || !flag("finale.enabled", true) || team == null) return;
        Map<String, String> v = HostValues.tournament(plugin, content);
        v.put("place", String.valueOf(place));
        v.put("place_team", HostValues.colored(team));
        v.put("place_points", String.valueOf(team.getPoints()));
        speak(place == 3 ? "finale-third" : "finale-second", v, "finale:place:" + place, true, null, null, null);
    }

    public void onChampions(KMCTeam team) {
        if (!mayAuto() || !flag("finale.enabled", true) || team == null) return;
        Map<String, String> v = HostValues.tournament(plugin, content);
        v.put("place", "1");
        v.put("place_team", HostValues.colored(team));
        v.put("place_points", String.valueOf(team.getPoints()));
        v.put("winner", HostValues.colored(team));
        speak("finale-champion", v, "finale:champion", true, null, null, null);
    }

    public void onAwards() {
        if (!mayAuto() || !flag("finale.enabled", true)) return;
        speak("finale-awards", HostValues.tournament(plugin, content), "finale:awards", true, null, null, null);
    }

    /** Short free text from older call sites ({@code presenter.*} language keys). */
    public void autoSay(String legacyText, int seconds) {
        if (!mayAuto() || legacyText == null || legacyText.isBlank()) return;
        offer(single(legacyText, seconds, "auto:" + Math.abs(legacyText.hashCode()), Audience.ALL, null, null,
                HostDialogues.NORMAL));
    }

    // ── Live announcements ───────────────────────────────────────────────────

    private boolean liveAllowed(String type) {
        if (!mayAuto() || !flag("live-announcements.enabled", true)) return false;
        if (!plugin.getConfig().getBoolean("host.live-announcements.types." + type, !type.equals("checkpoint"))) return false;
        var a = auto();
        if (a == null || a.getState() != AutomationManager.State.GAME_ACTIVE) return false;   // not paused, not between games
        if (plugin.getGameManager().getActiveGame() == null) return false;
        long now = System.currentTimeMillis();
        return now - lastLiveMs >= num("live-announcements.global-cooldown-seconds", 8) * 1000L;
    }

    private void live(String dialogueId, String type, Player player, String gameId) {
        if (!liveAllowed(type)) return;
        Map<String, String> v = new HashMap<>();
        HostValues.player(plugin, v, player, null);
        KMCGame g = gameId != null ? plugin.getGameManager().getGame(gameId) : plugin.getGameManager().getActiveGame();
        if (g != null) HostValues.game(v, g, content);
        String teamId = null;
        if (player != null) {
            KMCTeam t = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
            if (t != null) teamId = t.getId();
        }
        if (speak(dialogueId, v, null, false, null, teamId, null)) lastLiveMs = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onObjective(GameObjectiveEvent e) {
        try {
            switch (e.getType()) {
                case GOAL_SCORED -> live("live-goal", "goal", e.getPlayer(), e.getGameId());
                case PUZZLE_SOLVED -> live("live-puzzle", "puzzle", e.getPlayer(), e.getGameId());
                case ORDER_COMPLETED -> live("live-order", "order", e.getPlayer(), e.getGameId());
                case ROOM_CLEARED -> live("live-room", "room", e.getPlayer(), e.getGameId());
                case BOSS_KILLED -> live("live-boss", "boss", e.getPlayer(), e.getGameId());
                case CHECKPOINT_HIT -> live("live-checkpoint", "checkpoint", e.getPlayer(), e.getGameId());
                case LAST_STANDING -> live("live-last-standing", "last-standing", e.getPlayer(), e.getGameId());
                default -> { }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Host live announcement failed: " + t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClutch(ClutchMomentEvent e) {
        try { live("live-clutch", "clutch", e.getPlayer(), e.getGameId()); }
        catch (Throwable t) { plugin.getLogger().warning("[KMC] Host live announcement failed: " + t); }
    }

    // ── Speaking ──────────────────────────────────────────────────────────────

    /**
     * Builds and queues the dialogue {@code id}.
     *
     * @param key  de-duplication key (null = none)
     * @param once the key may only be spoken once per session
     * @return true when the speech was queued
     */
    private boolean speak(String id, Map<String, String> values, String key, boolean once, UUID target,
                          String teamId, Audience audience) {
        Dialogue d = content.dialogue(id);
        if (d == null) {
            plugin.getLogger().warning("[KMC] Host: no dialogue '" + id + "' in host.yml");
            return false;
        }
        if (d.onlyOnStart() && gamesPlayed() > 0 && sm.state() == HostState.ACTIVE) return false;
        Speech s = build(d, values, key, target, teamId, audience, null, once);
        if (s == null) return false;
        return offer(s);
    }

    private boolean offer(Speech s) {
        if (s == null) return false;
        var r = queue.offer(s, System.currentTimeMillis(), current != null ? current.key : "");
        if (r != HostQueue.Offer.QUEUED) return false;
        pump();
        return true;
    }

    /** Fills a dialogue's placeholders and turns it into short pages. Null when no line is left to say. */
    private Speech build(Dialogue d, Map<String, String> values, String key, UUID target, String teamId,
                         Audience audience, Integer priority, boolean once) {
        List<HostDialogues.Line> src = d.lines();
        if (src.isEmpty() && !d.variants().isEmpty()) src = List.of(d.variants().get(rnd.nextInt(d.variants().size())));
        int maxChars = Math.max(10, num("dialogue.max-chars-per-line", 26));
        int maxLines = Math.max(1, num("dialogue.max-lines", 3));
        int delayDef = (int) Math.round(dbl("dialogue.default-delay", 0.5) * 20);
        int minHold = (int) Math.round(dbl("dialogue.default-duration", 3.0) * 20);
        int maxHold = (int) Math.round(dbl("dialogue.max-duration", 9.0) * 20);
        double perChar = dbl("dialogue.ticks-per-char", 2.0);

        List<SpeechLine> out = new ArrayList<>();
        for (HostDialogues.Line line : src) {
            boolean missing = false;
            for (String r : line.requires()) {
                String val = values.get(r);
                if (val == null || val.isBlank()) { missing = true; break; }
            }
            if (missing) continue;
            var ex = HostText.expand(line.text(lang()), values, HostDialogues.KNOWN);
            for (String u : ex.unknown()) {
                if (warnedPlaceholders.add(d.id() + "|" + u))
                    plugin.getLogger().warning("[KMC] Host: dialogue '" + d.id() + "' uses unknown placeholder {" + u + "}");
            }
            if (!ex.empty().isEmpty()) continue;        // a value that does not exist yet: do not invent it
            String text = ex.text().trim();
            if (text.isBlank()) continue;
            HostDialogues.Stage stage = expandStage(line.stage(), values);
            List<String> pages = HostText.paginate(text, maxChars, maxLines);
            for (int i = 0; i < pages.size(); i++) {
                String page = pages.get(i);
                int delay = i > 0 ? 0 : (line.delayTicks() >= 0 ? line.delayTicks() : delayDef);
                int hold = line.holdTicks() >= 0 ? line.holdTicks()
                        : (int) Math.max(minHold, Math.min(maxHold, HostText.visibleLength(page) * perChar));
                out.add(new SpeechLine(page, delay, hold, i == 0 ? line.sound() : null,
                        i == 0 ? stage : HostDialogues.Stage.continuation(stage.tension())));
            }
        }
        if (out.isEmpty()) return null;
        int cooldown = d.cooldownSeconds() > 0 ? d.cooldownSeconds() : num("cooldowns.default", 0);
        Speech built = new Speech(key, d.id(), out, priority != null ? priority : d.priority(),
                audience != null ? audience : d.audience(), target, teamId, d.skippable(), cooldown,
                d.maxWaitSeconds() * 1000L, once);
        built.cutscene = d.cutscene();
        built.focus = d.focus();
        built.staged = d.staged();
        return built;
    }

    /** The title and subtitle of a staged line may use placeholders too; one without a value stays out. */
    private HostDialogues.Stage expandStage(HostDialogues.Stage st, Map<String, String> values) {
        if (st.title().isEmpty() && st.subtitle().isEmpty()) return st;
        return new HostDialogues.Stage(st.shot(), st.walk(), st.act(), st.fx(),
                expandPlain(st.title(), values), expandPlain(st.subtitle(), values), st.tension());
    }

    private String expandPlain(String s, Map<String, String> values) {
        if (s == null || s.isBlank()) return "";
        var ex = HostText.expand(s, values, HostDialogues.KNOWN);
        return ex.empty().isEmpty() ? ex.text().trim() : "";
    }

    private Speech single(String text, int seconds, String key, Audience audience, UUID target, String teamId, int priority) {
        int maxChars = Math.max(10, num("dialogue.max-chars-per-line", 26));
        int maxLines = Math.max(1, num("dialogue.max-lines", 3));
        List<SpeechLine> lines = new ArrayList<>();
        List<String> pages = HostText.paginate(text, maxChars, maxLines);
        int hold = Math.max(40, seconds * 20 / Math.max(1, pages.size()));
        for (int i = 0; i < pages.size(); i++) lines.add(new SpeechLine(pages.get(i), i == 0 ? 0 : 5, hold, null));
        return new Speech(key, "free", lines, priority, audience, target, teamId, true, 0, 20_000L, false);
    }

    /** Trims a speech from the end (keeping its last line) until it fits the time that is available. */
    private Speech fit(Speech s, long budgetTicks) {
        List<SpeechLine> lines = new ArrayList<>(s.lines);
        long total = 0;
        for (SpeechLine l : lines) total += l.delayTicks() + l.holdTicks();
        while (total > budgetTicks && lines.size() > 2) {
            SpeechLine removed = lines.remove(lines.size() - 2);     // keep the closing line
            total -= removed.delayTicks() + removed.holdTicks();
        }
        Speech fitted = new Speech(s.key, s.dialogueId, lines, s.priority, s.audience, s.target, s.teamId, s.skippable,
                s.cooldownSeconds, s.maxWaitMs, s.once);
        return fitted.withMetaOf(s);
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void pump() {
        long now = System.currentTimeMillis();
        if (current != null) {
            // an important announcement may interrupt a plainer one; the interrupted one is gone
            if (current.skippable && queue.topPriority(now) > current.priority) stopCurrent();
            else return;
        }
        Speech next = queue.poll(now);
        if (next == null) return;
        queue.markPlayed(next, now);
        current = next;
        lineIdx = 0;
        playGen++;
        begin(next);
    }

    // ── Cutscenes ─────────────────────────────────────────────────────────────

    private static final String FOCUS_KEY = "host-focus";
    private boolean focusRunning;
    private String cutsceneRunning;

    /** Camera work never takes players away from a running game or over another cinematic. */
    private boolean cutscenesAllowed() {
        if (!flag("cutscenes.enabled", true)) return false;
        var presenter = plugin.getPresenter();
        if (presenter == null || !presenter.isPlaced()) return false;
        var a = auto();
        if (a != null && (a.getState() == AutomationManager.State.GAME_ACTIVE
                || a.getState() == AutomationManager.State.PAUSED)) return false;
        var cm = plugin.getCinematicManager();
        if (cm == null) return false;
        for (Player p : Bukkit.getOnlinePlayers()) if (cm.isInCinematic(p.getUniqueId())) return false;
        return true;
    }

    /**
     * Starts a speech: first its recorded cutscene (when set and it exists), then the lines; a focus or a
     * staged scene (walking host, camera shots) runs along with the lines.
     */
    private void begin(Speech s) {
        boolean camera = (s.cutscene != null || s.focus || s.staged) && cutscenesAllowed();
        if (camera) {
            var cm = plugin.getCinematicManager();
            if (s.cutscene != null && cm.routeExists(s.cutscene)) {
                final int gen = playGen;
                cutsceneRunning = s.cutscene;
                cm.playRouteForAll(s.cutscene, () -> {
                    cutsceneRunning = null;
                    if (gen != playGen || current != s) return;
                    visuals(s, cutscenesAllowed());
                    step();
                });
                return;
            }
        }
        visuals(s, camera);
        step();
    }

    /** What accompanies the lines: the staged scene (host action + camera) or just a focus push-in. */
    private void visuals(Speech s, boolean camera) {
        if (s.staged) {
            if (director.start(s, camera, viewers(s))) return;
        }
        if (camera && s.focus) startFocus(s);
    }

    /** A short push-in on the host, as long as the speech lasts (capped), generated on the spot. */
    private void startFocus(Speech s) {
        var presenter = plugin.getPresenter();
        var cm = plugin.getCinematicManager();
        Location spot = presenter.getSpot();
        if (spot == null || spot.getWorld() == null || cm == null) return;
        long total = 0;
        for (SpeechLine l : s.lines) total += l.delayTicks() + l.holdTicks();
        int cap = Math.max(3, num("cutscenes.max-focus-seconds", 30)) * 20;
        int ticks = (int) Math.max(60, Math.min(cap, total));
        double dist = Math.max(3.0, dbl("cutscenes.distance", 7.0));
        double height = dbl("cutscenes.height", 2.2);
        org.bukkit.util.Vector dir = spot.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new org.bukkit.util.Vector(0, 0, 1); else dir.normalize();
        Location head = spot.clone().add(0, 1.7, 0);
        Location far = spot.clone().add(dir.clone().multiply(dist)).add(0, height + 0.6, 0);
        Location near = spot.clone().add(dir.clone().multiply(dist * 0.55)).add(0, height, 0);
        far.setDirection(head.toVector().subtract(far.toVector()));
        near.setDirection(head.toVector().subtract(near.toVector()));
        CameraRoute route = new CameraRoute(FOCUS_KEY, "Host focus");
        route.addWaypoint(new CameraWaypoint(far, Math.max(1, ticks * 6 / 10), InterpolationType.SMOOTH, "", "", ""));
        route.addWaypoint(new CameraWaypoint(near, Math.max(1, ticks * 4 / 10), InterpolationType.SMOOTH, "", "", ""));
        route.addWaypoint(new CameraWaypoint(near, 10, InterpolationType.SMOOTH, "", "", ""));
        List<Player> viewers = new ArrayList<>();
        Predicate<Player> filter = viewers(s);
        for (Player p : Bukkit.getOnlinePlayers()) if (filter == null || filter.test(p)) viewers.add(p);
        if (viewers.isEmpty()) return;
        focusRunning = true;
        cm.playRouteInstance(FOCUS_KEY, route, viewers, false, () -> focusRunning = false);
    }

    /** Ends any camera work this speech started (the camera never outlives the speech). */
    private void stopCutscenes() {
        director.stop();
        var cm = plugin.getCinematicManager();
        if (cm == null) return;
        if (focusRunning) { focusRunning = false; cm.stopRoute(FOCUS_KEY); }
        if (cutsceneRunning != null) { String r = cutsceneRunning; cutsceneRunning = null; cm.stopRoute(r); }
    }


    private void step() {
        if (current == null) return;
        if (lineIdx >= current.lines.size()) { finishCurrent(); return; }
        final int gen = playGen;
        final int smGen = sm.generation();
        final Speech speech = current;
        final SpeechLine line = current.lines.get(lineIdx);
        playTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gen != playGen || speech != current) return;
            if (smGen != sm.generation() && !manual(speech)) { stopCurrent(); return; }
            try { present(speech, line); }
            catch (Throwable t) { plugin.getLogger().warning("[KMC] Host could not show a line: " + t); }
            playTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (gen != playGen || speech != current) return;
                plugin.getPresenter().clearBubble();
                lineIdx++;
                step();
            }, Math.max(10, line.holdTicks()));
        }, Math.max(0, line.delayTicks()));
    }

    /** Manual speeches (admin commands, interaction) do not depend on the session. */
    private boolean manual(Speech s) { return s.dialogueId.startsWith("manual") || s.audience == Audience.PLAYER; }

    private void finishCurrent() {
        Speech done = current;
        current = null;
        stopCutscenes();
        plugin.getPresenter().clearBubble();
        fireDone(done);
        Bukkit.getScheduler().runTask(plugin, this::pump);
    }

    /** Tells whoever waits for a speech that it has ended — once, however it ended. */
    private void fireDone(Speech s) {
        if (s == null || s.onDone == null) return;
        Runnable r = s.onDone;
        s.onDone = null;
        try { r.run(); }
        catch (Throwable t) { plugin.getLogger().warning("[KMC] Host: end-of-speech callback failed: " + t); }
    }

    /** Stops what is being said now and takes the bubble away (an interrupted speech does not return). */
    private void stopCurrent() {
        playGen++;
        if (playTask != null) { playTask.cancel(); playTask = null; }
        Speech done = current;
        current = null;
        stopCutscenes();
        if (plugin.getPresenter() != null) plugin.getPresenter().clearBubble();
        fireDone(done);
    }

    /** Skips the speech that is playing now. */
    public boolean skip() {
        if (current == null) return false;
        stopCurrent();
        Bukkit.getScheduler().runTask(plugin, this::pump);
        return true;
    }

    /** Skips what is being said and everything that waits (an admin skipping the opening presentation). */
    public void skipAll() {
        queue.clearPending();
        if (current != null) stopCurrent();
    }

    // ── The KMC lore (the start of the tournament) ───────────────────────────

    /**
     * The tournament begins: the host tells the KMC lore, staged with a walking host and camera work
     * ({@code kmc-lore} in host.yml). {@code done} runs when the story is over — finished, skipped or
     * stopped. Nothing else is said before it.
     *
     * @return the length of the story in ticks, or -1 when there is none (no host placed, switched off, no
     *         such dialogue): then {@code done} is NOT called and the caller simply goes on.
     */
    public int playLoreIntro(Runnable done) {
        if (!mayAuto() || !flag("lore-intro.enabled", true)) return -1;
        var presenter = plugin.getPresenter();
        if (presenter == null || !presenter.isPlaced()) return -1;
        Dialogue d = content.dialogue(str("lore-intro.dialogue", "kmc-lore"));
        if (d == null) return -1;
        Speech s = build(d, HostValues.tournament(plugin, content), "lore-intro", null, null, Audience.ALL,
                HostDialogues.CRITICAL, true);
        if (s == null) return -1;
        s.onDone = done;
        s.staged = true;
        if (!offer(s)) return -1;
        return (int) Math.min(Integer.MAX_VALUE, SceneMath.totalTicks(s.lines));
    }

    /**
     * Calls {@code done} as soon as the host has nothing left to say (nothing playing and nothing waiting),
     * at the latest after {@code maxSeconds}. The countdown to the vote waits for this, so it never runs while
     * the host is still talking. Dropped silently when the session ends meanwhile.
     */
    public void whenQuiet(int maxSeconds, Runnable done) {
        final int smGen = sm.generation();
        final long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (smGen != sm.generation()) { cancel(); return; }
                boolean quiet = !mayAuto() || (current == null && queue.size() == 0);
                if (!quiet && System.currentTimeMillis() < deadline) return;
                cancel();
                done.run();
            }
        }.runTaskTimer(plugin, 5L, 10L);
    }

    public boolean isSpeaking() { return current != null; }

    public int queued() { return queue.size(); }

    private Predicate<Player> viewers(Speech s) {
        return switch (s.audience) {
            case ALL -> null;
            case TEAM -> p -> {
                if (s.teamId == null) return true;
                KMCTeam t = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
                return t != null && t.getId().equals(s.teamId);
            };
            case SPECTATORS -> p -> {
                try { return plugin.getSpectatorManager().isSpectating(p); } catch (Throwable t) { return false; }
            };
            case PLAYER -> p -> p.getUniqueId().equals(s.target);
            case ADMINS -> p -> p.hasPermission("kmc.host.admin");
        };
    }

    private void present(Speech s, SpeechLine l) {
        var presenter = plugin.getPresenter();
        Predicate<Player> viewers = viewers(s);
        if (flag("dialogue.show-above-head", true)) presenter.showBubble(l.text(), viewers);
        if (flag("npc.look-at-players", true)) presenter.lookAtNearest(40);
        Location spot = presenter.getSpot();

        // the NPC stands somewhere the viewer cannot see: tell them in the action bar instead
        var autoMgr = auto();
        boolean inGame = autoMgr != null && autoMgr.getState() == AutomationManager.State.GAME_ACTIVE
                && s.dialogueId.startsWith("live-");     // only live remarks: the start flow uses the action bar itself
        String remote = inGame ? str("dialogue.remote-mirror", "ACTIONBAR").toUpperCase() : "NONE";   // lobby: the chat and the bubble are enough
        boolean chat = flag("dialogue.chat-mirror", false);
        double range = dbl("dialogue.visible-range", 48.0);
        String plain = l.text().replace('\n', ' ');
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewers != null && !viewers.test(p)) continue;
            boolean sees = spot != null && p.getWorld().equals(spot.getWorld())
                    && p.getLocation().distanceSquared(spot) <= range * range;
            if (chat) p.sendMessage(HostText.color(str("npc.name", "&6&lKMC Host")) + " §8» §f" + plain);
            if (!sees && remote.equals("ACTIONBAR")) p.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().deserialize("§6🎙 §f" + plain));
            else if (!sees && remote.equals("CHAT") && !chat)
                p.sendMessage(HostText.color(str("npc.name", "&6&lKMC Host")) + " §8» §f" + plain);
        }

        if (spot == null || spot.getWorld() == null) return;
        if (flag("effects.sounds", true)) {
            String name = l.sound() != null ? l.sound() : str("effects.talk-sound", "ENTITY_VILLAGER_YES");
            Sound snd = sound(name);
            if (snd != null) {
                float vol = (float) dbl("effects.volume", 0.8);
                float pitch = (float) dbl("effects.pitch", 1.1);
                if (viewers == null) spot.getWorld().playSound(spot, snd, vol, pitch);
                else for (Player p : Bukkit.getOnlinePlayers()) if (viewers.test(p)) p.playSound(spot, snd, vol, pitch);
            }
        }
        if (flag("effects.particles", true)) {
            spot.getWorld().spawnParticle(Particle.NOTE, spot.clone().add(0, 2.2, 0), 2, 0.3, 0.1, 0.3, 0);
        }
    }

    private Sound sound(String name) {
        if (name == null || name.isBlank()) return null;
        if (soundCache.containsKey(name)) return soundCache.get(name);
        Sound found = null;
        String lower = name.trim().toLowerCase();
        List<String> candidates = new ArrayList<>();
        if (lower.contains(".") || lower.contains(":")) candidates.add(lower);
        else {
            int[] pos = new int[12];
            int n = 0;
            for (int i = 0; i < lower.length() && n < pos.length; i++) if (lower.charAt(i) == '_') pos[n++] = i;
            for (int mask = 0; mask < (1 << n) && candidates.size() < 4096; mask++) {
                char[] c = lower.toCharArray();
                for (int b = 0; b < n; b++) if ((mask & (1 << b)) != 0) c[pos[b]] = '.';
                candidates.add(new String(c));
            }
        }
        for (String c : candidates) {
            try {
                NamespacedKey k = c.contains(":") ? NamespacedKey.fromString(c) : NamespacedKey.minecraft(c);
                Sound s = k == null ? null : Registry.SOUNDS.get(k);
                if (s != null) { found = s; break; }
            } catch (Throwable ignored) { }
        }
        soundCache.put(name, found);
        if (found == null) plugin.getLogger().warning("[KMC] Host: unknown sound '" + name + "'");
        return found;
    }

    // ── Admin actions ─────────────────────────────────────────────────────────

    /** An admin announcement: spoken at once, whatever the session state. */
    public boolean announce(String text) {
        if (!sm.isEnabled() || text == null || text.isBlank()) return false;
        Speech s = single(text, 6, "", Audience.ALL, null, null, HostDialogues.HIGH);
        return offerManual(s);
    }

    private boolean offerManual(Speech s) {
        Speech m = new Speech(s.key, "manual-" + s.dialogueId, s.lines, s.priority, s.audience, s.target, s.teamId,
                s.skippable, 0, 30_000L, false);
        return offer(m.withMetaOf(s));
    }

    /** Plays dialogue {@code id} as an admin (no session needed). */
    public boolean play(String id, Player admin) {
        Dialogue d = content.dialogue(id);
        if (d == null || !sm.isEnabled()) return false;
        Map<String, String> v = HostValues.tournament(plugin, content);
        if (admin != null) HostValues.player(plugin, v, admin, null);
        var game = plugin.getGameManager().getActiveGame();
        if (game != null) HostValues.game(v, game, content);
        Speech s = build(d, v, null, null, null, null, HostDialogues.HIGH, false);
        return s != null && offerManual(s);
    }

    /** The display test: shows a few lines above the head for the admin only, touching nothing else. */
    public boolean test(Player admin) {
        if (!sm.isEnabled()) return false;
        Dialogue d = content.dialogue("test-bubble");
        if (d == null) return false;
        Map<String, String> v = new HashMap<>();
        Speech s = build(d, v, null, admin != null ? admin.getUniqueId() : null, null,
                admin != null ? Audience.PLAYER : Audience.ALL, HostDialogues.HIGH, false);
        return s != null && offerManual(s);
    }

    /** Replays the opening, or the introduction of {@code gameId}, for everybody. */
    public boolean replayIntro(String gameId) {
        if (!sm.isEnabled()) return false;
        if (gameId == null || gameId.isBlank()) return play("opening", null);
        return playGameIntro(gameId, null, plugin.getConfig().getStringList("host.game-intros.sections"));
    }

    private boolean playGameIntro(String gameId, Player forPlayer, List<String> sections) {
        KMCGame g = plugin.getGameManager().getGame(gameId);
        if (g == null) return false;
        if (sections.isEmpty()) sections = List.of("intro", "lore", "goal", "rules", "controls", "scoring", "warnings",
                "duration", "closing");
        Dialogue d = content.gameIntro(gameId, sections, num("game-intros.max-scoring-lines", 4));
        Map<String, String> v = HostValues.tournament(plugin, content);
        HostValues.game(v, g, content);
        if (forPlayer != null) HostValues.player(plugin, v, forPlayer, null);
        Speech s = build(d, v, null, forPlayer != null ? forPlayer.getUniqueId() : null, null,
                forPlayer != null ? Audience.PLAYER : Audience.ALL, HostDialogues.HIGH, false);
        return s != null && offerManual(s);
    }

    public List<String> dialogueIds() { return new ArrayList<>(contentIds()); }

    private List<String> contentIds() {
        List<String> ids = new ArrayList<>();
        for (String s : List.of("opening", "how-it-works", "tournament-overview", "teams-start", "team-intro",
                "games-lineup", "next-game", "results-game", "standings-normal", "standings-close",
                "standings-new-leader", "standings-runaway", "finale-announce", "finale-third", "finale-second",
                "finale-champion", "finale-awards", "info-about", "info-how", "test-bubble", "kmc-lore")) {
            if (content.dialogue(s) != null) ids.add(s);
        }
        return ids;
    }

    public int dialogueCount() { return content.dialogueCount(); }

    /** A game text from host.yml (description, objective, ...): the fallback when a game registered none. */
    public String gameText(String gameId, String key) { return content.gameString(gameId, key); }

    /** The rules, controls and warnings that host.yml has for a game (empty when it has none). */
    public List<String> howToPlay(String gameId) {
        List<String> out = new ArrayList<>();
        out.addAll(content.gameList(gameId, "rules"));
        out.addAll(content.gameList(gameId, "controls"));
        for (String w : content.gameList(gameId, "warnings")) out.add("§c" + w);
        return out;
    }

    public List<String> contentWarnings() { return content.warnings(); }

    // ── Interaction ───────────────────────────────────────────────────────────

    private static final class MenuHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** The host cannot be hurt, not even by a creative-mode punch (which ignores invulnerability). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHostDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        var presenter = plugin.getPresenter();
        if (presenter != null && presenter.isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        var presenter = plugin.getPresenter();
        if (presenter == null || !presenter.isBody(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!sm.isEnabled() || !flag("interaction.enabled", true)) return;
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(p.getUniqueId());
        if (last != null && now - last < num("interaction.cooldown-seconds", 2) * 1000L) return;
        clickCooldown.put(p.getUniqueId(), now);

        boolean idle = !sm.canSpeakAutomatically();
        if (idle && !flag("interaction.menu-when-idle", true)) {
            respondTo(p, "idle-touch");
            return;
        }
        openMenu(p, idle);
    }

    private void openMenu(Player p, boolean idle) {
        ConfigurationSection menu = content.menu();
        if (menu == null) { respondTo(p, "info-about"); return; }
        List<String> allowed = idle ? menu.getStringList("idle-options") : plugin.getConfig().getStringList("host.interaction.options");
        if (allowed.isEmpty() && !idle) {
            ConfigurationSection items = menu.getConfigurationSection("items");
            if (items != null) allowed = new ArrayList<>(items.getKeys(false));
        }
        MenuHolder holder = new MenuHolder();
        holder.inv = Bukkit.createInventory(holder, 27, HostText.color(menu.getString("title", "&6&lKMC Host")));
        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items == null) return;
        for (String id : allowed) {
            ConfigurationSection it = items.getConfigurationSection(id);
            if (it == null) continue;
            Material mat = Material.matchMaterial(it.getString("material", "PAPER"));
            ItemStack stack = new ItemStack(mat != null ? mat : Material.PAPER);
            ItemMeta meta = stack.getItemMeta();
            meta.setDisplayName(HostText.color(it.getString("name", id)));
            List<String> lore = new ArrayList<>();
            for (String l : it.getStringList("lore")) lore.add(HostText.color(l));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "host_action"),
                    org.bukkit.persistence.PersistentDataType.STRING, id);
            stack.setItemMeta(meta);
            int slot = Math.max(0, Math.min(26, it.getInt("slot", holder.inv.firstEmpty())));
            holder.inv.setItem(slot, stack);
        }
        p.openInventory(holder.inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);                       // players can only click, never take anything
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String action = it.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "host_action"),
                org.bukkit.persistence.PersistentDataType.STRING);
        if (action == null) return;
        p.closeInventory();
        menuAction(p, action);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clickCooldown.remove(e.getPlayer().getUniqueId());
        loreCursor.remove(e.getPlayer().getUniqueId());
        if (current != null && e.getPlayer().getUniqueId().equals(current.target)) stopCurrent();
    }

    /** What a menu item does. None of these can change the tournament: they only make the host speak. */
    private void menuAction(Player p, String action) {
        switch (action) {
            case "about" -> respondTo(p, "info-about");
            case "how" -> respondTo(p, "info-how");
            case "game", "replay" -> {
                String gameId = relevantGameId();
                if (gameId == null) { respondTo(p, "info-no-game"); return; }
                List<String> sections = plugin.getConfig().getStringList(
                        action.equals("game") ? "host.game-intros.prestart-sections" : "host.game-intros.sections");
                if (!playGameIntro(gameId, p, sections)) respondTo(p, "info-no-game");
            }
            case "rules" -> {
                String gameId = relevantGameId();
                if (gameId == null) { respondTo(p, "info-no-game"); return; }
                if (!playGameIntro(gameId, p, List.of("goal", "rules", "controls", "scoring", "warnings"))) respondTo(p, "info-no-game");
            }
            case "standings" -> standingsFor(p);
            case "stats" -> {
                Map<String, String> v = HostValues.tournament(plugin, content);
                HostValues.player(plugin, v, p, null);
                speak("info-my-stats", v, null, false, p.getUniqueId(), null, Audience.PLAYER);
            }
            case "lore" -> lore(p);
            default -> { }
        }
    }

    private void respondTo(Player p, String dialogueId) {
        Map<String, String> v = HostValues.tournament(plugin, content);
        HostValues.player(plugin, v, p, null);
        speakManualFor(dialogueId, v, p);
    }

    private void speakManualFor(String id, Map<String, String> v, Player p) {
        Dialogue d = content.dialogue(id);
        if (d == null) return;
        Speech s = build(d, v, null, p.getUniqueId(), null, Audience.PLAYER, null, false);
        if (s != null) offerManual(s);
    }

    private void standingsFor(Player p) {
        var standings = plugin.getTeamManager().getTeamsSortedByPoints();
        if (standings.isEmpty() || standings.get(0).getPoints() <= 0) { respondTo(p, "info-no-standings"); return; }
        Map<String, String> v = HostValues.tournament(plugin, content);
        HostValues.player(plugin, v, p, null);
        speakManualFor("standings-normal", v, p);
    }

    private void lore(Player p) {
        if (!flag("lore.enabled", true)) { respondTo(p, "info-about"); return; }
        List<String> order = content.loreOrder();
        if (order.isEmpty()) { respondTo(p, "info-about"); return; }
        int i = loreCursor.merge(p.getUniqueId(), 1, Integer::sum) - 1;
        String id = order.get(i % order.size());
        Map<String, String> v = HostValues.tournament(plugin, content);
        HostValues.player(plugin, v, p, null);
        Dialogue d = content.dialogue("lore-" + id);
        Speech s = d != null ? build(d, v, null, p.getUniqueId(), null, Audience.PLAYER, null, false) : null;
        if (s == null) {                                           // real data missing: tell that, never make it up
            d = content.dialogue("lore-" + id + "-empty");
            if (d != null) s = build(d, v, null, p.getUniqueId(), null, Audience.PLAYER, null, false);
        }
        if (s != null) offerManual(s);
    }

    /** The game the player is most likely asking about: the running one, else the last one introduced. */
    private String relevantGameId() {
        KMCGame g = plugin.getGameManager().getActiveGame();
        String id = g != null ? g.getId() : lastIntroGameId;
        if (id == null) return null;
        if (plugin.getGameManager().isSurpriseHidden(id)) return null;     // a hidden surprise stays hidden
        return id;
    }

    /** For {@code /kmchost info}. */
    public Map<String, String> status() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("state", sm.state().name());
        m.put("kmcauto", auto() != null ? auto().getState().name() : "-");
        m.put("placed", String.valueOf(plugin.getPresenter() != null && plugin.getPresenter().isPlaced()));
        m.put("speaking", current != null ? current.dialogueId : "-");
        m.put("queued", String.valueOf(queue.size()));
        m.put("dialogues", String.valueOf(content.dialogueCount()));
        m.put("warnings", String.valueOf(content.warnings().size()));
        return m;
    }
}
