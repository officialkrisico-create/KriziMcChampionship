package nl.kmc.tnttag.managers;

import net.kyori.adventure.text.Component;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.stats.service.StatisticsService;
import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.PlayerState;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * TNT Tag V3 — individual battle-royale-style tag. 32 players, points feed the
 * KMC team total. Cinematic start, dynamic TNT holders with accelerating
 * beeping, powerups, chaos events, final-stage drama and a shrinking showdown.
 */
public final class TNTTagGameManagerV2 extends BaseGameManager {

    public enum MatchPhase { INITIALIZING, INTRODUCTION, TUTORIAL, COUNTDOWN, RUNNING, ENDING }

    private final TNTTagPlugin plugin;
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final List<BukkitTask> flowTasks = new ArrayList<>();

    private MatchPhase phase = MatchPhase.INITIALIZING;
    private int  eliminationCounter;
    private int  currentRound;
    private int  remainingRoundSeconds;
    private int  tickCounter;              // drives the accelerating beep
    private int  nextRoundTntBonus;        // set by the Double Trouble chaos event
    private boolean showdown;              // Final Showdown (2 players)
    private String activeChaos;            // label of the active chaos event, or null

    private BukkitTask roundTimerTask, tickTask, hudTask, chaosTask;
    private BossBar bossBar;

    public TNTTagGameManagerV2(TNTTagPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    public MatchPhase getPhase()          { return phase; }
    public boolean    isLive()            { return phase == MatchPhase.RUNNING && getState().isRunning(); }
    public Map<UUID, PlayerState> getPlayersMap() { return Collections.unmodifiableMap(players); }

    // ── INITIALIZING ──────────────────────────────────────────────────────────
    @Override
    protected void onPrepare() {
        phase = MatchPhase.INITIALIZING;
        players.clear();
        eliminationCounter = 0;
        currentRound = 0;
        nextRoundTntBonus = 0;
        showdown = false;
        activeChaos = null;
        cancelFlow();

        List<Location> spawns = plugin.getArenaManager().getArena().getSpawns();
        if (spawns.isEmpty()) { log.severe("[TNTTag] No spawns set — cannot start."); end(); return; }

        int freezeTicks = Math.max(20, totalStartSeconds() * 20);
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            GamePlayerUtil.safeTeleport(p, spawns.get(i % spawns.size()));
            GamePlayerUtil.resetPlayer(p);
            GamePlayerUtil.freezePlayer(p, freezeTicks);
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
        }

        bossBar = Bukkit.createBossBar("§c§lTNT TAG", BarColor.RED, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override protected void onCountdownStart() { /* presentation runs from onGameStart */ }

    // ── Presentation flow ─────────────────────────────────────────────────────
    @Override
    protected void onGameStart() {
        runStartFlow();
    }

    private void runStartFlow() {
        cancelFlow();
        int intro = flowOn("introduction") ? seconds("intro-seconds", 5)    : 0;
        int tut   = flowOn("tutorial")     ? seconds("tutorial-seconds", 10) : 0;
        int cd    = flowOn("countdown")    ? seconds("countdown-seconds", 5) : 0;
        long t = 0;

        if (intro > 0) { schedule(t, this::showIntro); t += intro * 20L; }
        if (tut > 0) {
            long base = t; schedule(t, () -> phase = MatchPhase.TUTORIAL);
            List<String> msgs = tutorialLines();
            long step = msgs.isEmpty() ? 0 : Math.max(20L, (tut * 20L) / msgs.size());
            for (int i = 0; i < msgs.size(); i++) { String m = msgs.get(i); schedule(base + i * step, () -> broadcast(m)); }
            t += tut * 20L;
        }
        if (cd > 0) {
            long base = t; schedule(t, () -> phase = MatchPhase.COUNTDOWN);
            for (int n = cd; n >= 1; n--) { int num = n; schedule(base + (cd - n) * 20L, () -> countdownTitle(num)); }
            t += cd * 20L;
        }
        schedule(t, this::beginRunning);
    }

    private void showIntro() {
        phase = MatchPhase.INTRODUCTION;
        for (Player p : online()) {
            p.sendTitle("§c§lTNT TAG", "§eSurvive. Escape. Don't explode.", 8, 70, 12);
            p.getWorld().spawnParticle(Particle.LAVA, p.getLocation().add(0, 1, 0), 14, 0.4, 0.6, 0.4, 0);
            p.playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 0.8f);
        }
    }

    private void countdownTitle(int n) {
        for (Player p : online()) {
            p.sendTitle("§e§l" + n, "§7Get ready...", 0, 22, 4);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
        }
    }

    private List<String> tutorialLines() {
        return List.of(
                "§c§l» §fWelcome to §cTNT Tag§f!",
                "§c§l» §fAvoid holding TNT when the timer hits zero.",
                "§c§l» §fHit another player to transfer the TNT.",
                "§c§l» §fGrab §epowerups §fand survive the chaos events.",
                "§c§l» §fLast player alive wins. Good luck!");
    }

    private void beginRunning() {
        phase = MatchPhase.RUNNING;
        for (Player p : online()) {
            GamePlayerUtil.unfreezePlayer(p);
            p.sendTitle("§a§lGO!", "§eRun!", 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        }
        players.values().forEach(PlayerState::startSurvivalTimer);

        plugin.getPowerupManager().start();
        startHud();
        startChaosScheduler();
        beginRound();
    }

    // ── Rounds ────────────────────────────────────────────────────────────────
    private void beginRound() {
        currentRound++;
        long alive = aliveCount();
        if (alive <= 1) { end(); return; }

        players.values().forEach(ps -> ps.setIt(false));
        int count = tntCountFor(alive) + nextRoundTntBonus;
        nextRoundTntBonus = 0;

        List<PlayerState> pool = new ArrayList<>(players.values().stream().filter(PlayerState::isAlive).toList());
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            pool.get(i).setIt(true);
            pool.get(i).recordTagPass();
        }

        remainingRoundSeconds = roundDuration();
        tickCounter = 0;
        updateVisuals();
        broadcast("§c§l[TNT Tag] §eRound " + currentRound + " — " + count + " bomb" + (count == 1 ? "" : "s") + " armed!");
        online().forEach(p -> p.playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 0.7f, 1.2f));

        roundTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingRoundSeconds--;
            updateBossBar();
            if (remainingRoundSeconds <= 0) explodeRound();
        }, 20L, 20L);

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private void explodeRound() {
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (tickTask       != null) { tickTask.cancel();       tickTask       = null; }

        List<PlayerState> holders = players.values().stream()
                .filter(ps -> ps.isAlive() && ps.isIt()).toList();
        for (PlayerState ps : holders) {
            ps.eliminate(eliminationCounter++);
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p != null) {
                p.getWorld().createExplosion(p.getLocation(), 0f, false, false);
                p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 1);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1f);
                clearTnt(p);
                toSpectator(p);
                p.sendTitle("§c§lBOOM!", "§7You held the bomb!", 5, 40, 10);
            }
            broadcast("§c💥 §7" + ps.getName() + " §7exploded!");
        }

        int survivalPts = plugin.getConfig().getInt("points.round-survive", 60);
        players.values().stream().filter(PlayerState::isAlive).forEach(ps -> {
            ps.incrementRoundsSurvived();
            if (survivalPts > 0) api.points().givePoints(ps.getUuid(), survivalPts, PointAward.Reason.SURVIVAL_BONUS, registration.getId());
        });

        checkFinalStages();

        if (aliveCount() <= 1) { end(); return; }
        long gap = Math.max(20L, plugin.getConfig().getInt("game.intermission-seconds", 4) * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, this::beginRound, gap);
    }

    // ── Per-tick: proximity transfer + beeping + visuals ──────────────────────
    private void tick() {
        if (!isLive()) return;
        double range = plugin.getConfig().getDouble("game.tag-range", 1.6);
        double rangeSq = range * range;
        var holders = players.values().stream().filter(ps -> ps.isAlive() && ps.isIt()).toList();

        for (PlayerState itPs : holders) {
            Player itP = Bukkit.getPlayer(itPs.getUuid());
            if (itP == null) continue;
            // Red aura around the bomb-carrier.
            itP.getWorld().spawnParticle(Particle.FLAME, itP.getLocation().add(0, 1, 0), 4, 0.3, 0.4, 0.3, 0);
            itP.getWorld().spawnParticle(Particle.DUST, itP.getLocation().add(0, 1.2, 0), 3,
                    0.3, 0.3, 0.3, new Particle.DustOptions(Color.RED, 1.4f));

            // Proximity transfer.
            for (PlayerState other : players.values()) {
                if (!other.isAlive() || other.isIt()) continue;
                Player o = Bukkit.getPlayer(other.getUuid());
                if (o == null || !o.getWorld().equals(itP.getWorld())) continue;
                if (itP.getLocation().distanceSquared(o.getLocation()) <= rangeSq)
                    attemptTransfer(itPs.getUuid(), other.getUuid());
            }
        }

        // Accelerating beep + actionbar warning for current holders.
        boolean beat = (tickCounter++ % beepIntervalTicks()) == 0;
        for (PlayerState itPs : holders) {
            Player p = Bukkit.getPlayer(itPs.getUuid());
            if (p == null) continue;
            p.sendActionBar(Component.text("§c☢ §lYOU HAVE THE BOMB §r§c☢  §7" + remainingRoundSeconds + "s"));
            if (beat) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, remainingRoundSeconds <= 3 ? 2f : 1.2f);
        }
    }

    /** Beep faster as the timer drops. */
    private int beepIntervalTicks() {
        if (remainingRoundSeconds <= 2)  return 1;
        if (remainingRoundSeconds <= 5)  return 2;
        if (remainingRoundSeconds <= 10) return 4;
        return 8;
    }

    // ── Transfer ──────────────────────────────────────────────────────────────
    public void attemptTransfer(UUID from, UUID to) {
        if (!isLive()) return;
        PlayerState fromPs = players.get(from), toPs = players.get(to);
        if (fromPs == null || !fromPs.isIt() || !fromPs.isAlive()) return;
        if (toPs == null || toPs.isIt() || !toPs.isAlive()) return;
        long cd = plugin.getConfig().getLong("game.tag-cooldown-ms", 1200);
        if (System.currentTimeMillis() - fromPs.getLastTagMs() < cd) return;

        // Shield blocks one transfer.
        if (toPs.isShielded()) {
            toPs.setShielded(false);
            Player tp = Bukkit.getPlayer(to);
            if (tp != null) { tp.sendTitle("§e§lSHIELD BLOCKED!", "", 0, 25, 5);
                tp.playSound(tp.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f); }
            return;
        }

        fromPs.setIt(false);
        toPs.setIt(true);
        toPs.recordTagPass();
        fromPs.incrementTags();
        toPs.markReceivedTnt();

        statsService.recordObjective(from); // counts as a "tag" objective for stats

        boolean clutch = remainingRoundSeconds <= 1;
        if (clutch) {
            fromPs.incrementClutch();
            broadcast("§6§l⚡ CLUTCH TAG! §e" + fromPs.getName() + " §7passed in the final second!");
            Player fc = Bukkit.getPlayer(from);
            if (fc != null) fireClutch(fc, nl.kmc.core.event.ClutchMomentEvent.ClutchType.LAST_SECOND_TAG,
                    fromPs.getName() + " passed the bomb in the final second!");
        }

        int tagPts = plugin.getConfig().getInt("points.per-tag", 10);
        if (tagPts > 0) api.points().givePoints(from, tagPts, PointAward.Reason.OBJECTIVE, registration.getId());

        updateVisuals();
        Player fp = Bukkit.getPlayer(from), tp = Bukkit.getPlayer(to);
        if (fp != null) { fp.sendTitle("§a§lSAFE!", "§7Bomb passed on!", 4, 18, 4); fp.playSound(fp.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.6f); }
        if (tp != null) { tp.sendTitle("§c§lTNT TRANSFERRED", "§7Pass it on!", 4, 26, 4); tp.playSound(tp.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f); }
    }

    // ── Final stages ──────────────────────────────────────────────────────────
    private void checkFinalStages() {
        long alive = aliveCount();
        if (alive == 5) {
            players.values().stream().filter(PlayerState::isAlive).forEach(PlayerState::markFinalFive);
            announceStage("§c§lFINAL FIVE", "§eFive remain!", Sound.UI_TOAST_CHALLENGE_COMPLETE);
        } else if (alive == 3) {
            announceStage("§c§lFINAL THREE", "§eThe tension rises!", Sound.UI_TOAST_CHALLENGE_COMPLETE);
        } else if (alive == 2) {
            showdown = true;
            announceStage("§4§lFINAL SHOWDOWN", "§eOne bomb. No powerups. No escape.", Sound.ENTITY_ENDER_DRAGON_GROWL);
            plugin.getPowerupManager().setEnabled(false);
            shrinkBorder(plugin.getConfig().getInt("game.showdown-border-seconds", 25));
        }
    }

    private void announceStage(String title, String sub, Sound sound) {
        broadcast("§8§m                              ");
        broadcast("  " + title + " §7— " + sub);
        broadcast("§8§m                              ");
        for (Player p : Bukkit.getOnlinePlayers()) { p.sendTitle(title, sub, 8, 50, 12); p.playSound(p.getLocation(), sound, 1f, 1f); }
    }

    // ── Chaos events ──────────────────────────────────────────────────────────
    private void startChaosScheduler() {
        if (!plugin.getConfig().getBoolean("chaos.enabled", true)) return;
        int min = plugin.getConfig().getInt("chaos.min-interval-seconds", 60);
        int max = plugin.getConfig().getInt("chaos.max-interval-seconds", 90);
        scheduleNextChaos(min, max);
    }

    private void scheduleNextChaos(int min, int max) {
        long delay = (min + new Random().nextInt(Math.max(1, max - min + 1))) * 20L;
        chaosTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isLive() && !showdown && aliveCount() > 3) triggerChaos();
            scheduleNextChaos(min, max);
        }, delay);
    }

    private void triggerChaos() {
        List<String> pool = new ArrayList<>(List.of(
                "LOW_GRAVITY", "SPEED_FRENZY", "DARKNESS", "TRACKER_FAILURE", "DOUBLE_TROUBLE", "TELEPORT_MADNESS"));
        var arena = plugin.getArenaManager().getArena();
        boolean canBorder = arena.getCenter() != null && arena.getBorderRadius() > 0;
        if (canBorder) pool.add("SHRINKING_ARENA");

        String ev = pool.get(new Random().nextInt(pool.size()));
        announceStage("§5§lCHAOS EVENT", chaosLabel(ev), Sound.ENTITY_WITHER_SPAWN);
        activeChaos = chaosLabel(ev);

        switch (ev) {
            case "LOW_GRAVITY" -> applyAll("jump_boost", 2, 20 * 20);
            case "SPEED_FRENZY" -> applyAll("speed", 1, 20 * 20);
            case "DARKNESS" -> applyAll("darkness", 0, 20 * 20);
            case "TRACKER_FAILURE" -> trackerFailure(15);
            case "DOUBLE_TROUBLE" -> { nextRoundTntBonus = 2; broadcast("§5§lDOUBLE TROUBLE! §7Next round gets extra bombs!"); }
            case "TELEPORT_MADNESS" -> teleportMadness(15);
            case "SHRINKING_ARENA" -> chaosShrink();
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> activeChaos = null, 20 * 20L);
    }

    /** Temporary border squeeze for ~20s, then it opens back up. */
    private void chaosShrink() {
        var arena = plugin.getArenaManager().getArena();
        Location c = arena.getCenter();
        double r = arena.getBorderRadius();
        if (c == null || c.getWorld() == null || r <= 0) return;
        WorldBorder wb = c.getWorld().getWorldBorder();
        wb.setCenter(c);
        wb.setDamageAmount(0.5);
        wb.setWarningDistance(6);
        wb.setSize(r * 2);
        wb.setSize(Math.max(12, r * 0.5) * 2, 8);                 // squeeze in
        Bukkit.getScheduler().runTaskLater(plugin, () -> {        // open back up
            if (isLive() && !showdown) wb.setSize(r * 2, 6);
        }, 20 * 20L);
    }

    private String chaosLabel(String ev) {
        return switch (ev) {
            case "LOW_GRAVITY" -> "Low Gravity";
            case "SPEED_FRENZY" -> "Speed Frenzy";
            case "DARKNESS" -> "Darkness";
            case "TRACKER_FAILURE" -> "Tracker Failure";
            case "DOUBLE_TROUBLE" -> "Double Trouble";
            case "TELEPORT_MADNESS" -> "Teleport Madness";
            case "SHRINKING_ARENA" -> "Shrinking Arena";
            default -> ev;
        };
    }

    private void trackerFailure(int seconds) {
        PotionEffectType glow = effect("glowing");
        players.values().stream().filter(PlayerState::isIt).forEach(ps -> {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) return;
            if (glow != null) p.removePotionEffect(glow);
            clearTnt(p);
        });
        Bukkit.getScheduler().runTaskLater(plugin, this::updateVisuals, seconds * 20L);
    }

    private void teleportMadness(int seconds) {
        var spawns = plugin.getArenaManager().getArena().getSpawns();
        if (spawns.isEmpty()) return;
        Random r = new Random();
        for (int t = 0; t < seconds; t += 3) {
            schedule(t * 20L, () -> players.values().stream().filter(PlayerState::isAlive).forEach(ps -> {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p != null) { p.teleport(spawns.get(r.nextInt(spawns.size())));
                    p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 30, 0.4, 1, 0.4, 0.1); }
            }));
        }
    }

    private void applyAll(String effect, int amp, int durTicks) {
        PotionEffectType type = effect(effect);
        if (type == null) return;
        players.values().stream().filter(PlayerState::isAlive).forEach(ps -> {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p != null) p.addPotionEffect(new PotionEffect(type, durTicks, amp, true, false, false));
        });
    }

    // ── Border ────────────────────────────────────────────────────────────────
    private void shrinkBorder(int seconds) {
        var arena = plugin.getArenaManager().getArena();
        Location c = arena.getCenter();
        double r = arena.getBorderRadius();
        if (c == null || c.getWorld() == null || r <= 0) return;
        WorldBorder wb = c.getWorld().getWorldBorder();
        wb.setCenter(c);
        wb.setDamageAmount(0.5);
        wb.setWarningDistance(6);
        wb.setSize(r * 2);
        wb.setSize(Math.max(8, r * 0.25) * 2, seconds);
    }

    // ── Spectator / global HUD ────────────────────────────────────────────────
    private void startHud() {
        hudTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isLive()) return;
            long alive = aliveCount();
            long tnt   = players.values().stream().filter(ps -> ps.isAlive() && ps.isIt()).count();
            String chaos = activeChaos != null ? " §8| §5" + activeChaos : "";
            String hud = "§7Alive §a" + alive + " §8| §7TNT §c" + tnt + " §8| §7Round §e" + currentRound + chaos;
            // Spectators (and the dead) see the live HUD; live holders get their own warning.
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerState ps = players.get(p.getUniqueId());
                if (ps != null && ps.isAlive() && ps.isIt()) continue; // holders have the bomb warning
                p.sendActionBar(Component.text(hud));
            }
        }, 10L, 10L);
    }

    // ── Visuals ───────────────────────────────────────────────────────────────
    private void updateVisuals() {
        PotionEffectType glow = effect("glowing");
        boolean useGlow   = plugin.getConfig().getBoolean("game.glowing", true);
        boolean useHelmet = plugin.getConfig().getBoolean("game.tnt-helmet", true);
        players.values().forEach(ps -> {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) return;
            if (glow != null) p.removePotionEffect(glow);
            if (ps.isIt()) {
                if (useGlow && glow != null) p.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, true));
                if (useHelmet) p.getInventory().setHelmet(new org.bukkit.inventory.ItemStack(Material.TNT));
            } else {
                clearTnt(p);
            }
        });
    }

    private void clearTnt(Player p) {
        var helm = p.getInventory().getHelmet();
        if (helm != null && helm.getType() == Material.TNT) p.getInventory().setHelmet(null);
    }

    private void toSpectator(Player p) {
        p.setGameMode(GameMode.SPECTATOR);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        Location spec = plugin.getArenaManager().getArena().getSpectatorSpawn();
        if (spec != null) p.teleport(spec);
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        long alive = aliveCount();
        long tnt   = players.values().stream().filter(ps -> ps.isAlive() && ps.isIt()).count();
        bossBar.setProgress(Math.max(0, Math.min(1, remainingRoundSeconds / (double) Math.max(1, roundDuration()))));
        bossBar.setColor(remainingRoundSeconds <= 5 ? BarColor.YELLOW : BarColor.RED);
        bossBar.setTitle("§c§lTNT TAG §8| §7Round §e" + currentRound + " §8| §c" + tnt + "💣 §8| §a"
                + alive + " alive §8| §e" + remainingRoundSeconds + "s");
    }

    // ── End ───────────────────────────────────────────────────────────────────
    @Override
    protected void onGameEnd() {
        phase = MatchPhase.ENDING;
        cancelFlow();
        cancelAll();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        plugin.getPowerupManager().stop();
        var arena = plugin.getArenaManager().getArena();
        if (arena.getWorld() != null) try { arena.getWorld().getWorldBorder().reset(); } catch (Exception ignored) {}

        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.isAlive() != b.isAlive()) return a.isAlive() ? -1 : 1;
            if (a.getEliminatedAtRound() != b.getEliminatedAtRound())
                return Integer.compare(b.getEliminatedAtRound(), a.getEliminatedAtRound());
            return Integer.compare(b.getTagsLanded(), a.getTagsLanded());
        });

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null; int topRounds = -1;

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getRoundsSurvived() > topRounds) { topRounds = ps.getRoundsSurvived(); mvpUuid = ps.getUuid(); mvpName = ps.getName(); }
        }

        // Push survival time to the stats platform for every player.
        for (PlayerState ps : players.values())
            statsService.recordSurvivalSeconds(ps.getUuid(), ps.liveSurvivalMs() / 1000);

        // Clutch moments for the winner.
        if (!ranked.isEmpty()) {
            PlayerState w = ranked.get(0);
            Player wp = Bukkit.getPlayer(w.getUuid());
            if (wp != null) {
                fireClutch(wp, nl.kmc.core.event.ClutchMomentEvent.ClutchType.LAST_SURVIVOR,
                        w.getName() + " is the last one standing!");
                if (!w.hasEverReceivedTnt()) {
                    broadcast("§6§l⭐ UNTOUCHABLE! §e" + winnerDesc + " §7won without ever holding the bomb!");
                    fireClutch(wp, nl.kmc.core.event.ClutchMomentEvent.ClutchType.PERFECT_GAME,
                            w.getName() + " won TNT Tag untouched!");
                }
            }
        }

        returnToLobby();
        players.clear();
        fireResult(winnerDesc, mvpUuid, mvpName, finishOrder);
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
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!isLive()) return defaultScoreboardLines(viewer);
        UUID id = viewer.getUniqueId();
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "sb.tnttag.round", currentRound));
        l.add(api.tr(id, "sb.tnttag.time", Math.max(0, remainingRoundSeconds)));
        l.add(api.tr(id, "sb.common.players-left", aliveCount()));
        if (activeChaos != null) { l.add(""); l.add("§5§l" + activeChaos); }
        PlayerState me = players.get(id);
        if (me != null) {
            l.add("");
            if (!me.isAlive())   l.add(api.tr(id, "sb.tnttag.out"));
            else if (me.isIt())  l.add(api.tr(id, "sb.tnttag.it"));
            else                 l.add(api.tr(id, "sb.tnttag.safe"));
        }
        return l;
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "TNT Tag"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                var a = plugin.getArenaManager().getArena();
                if (!a.isReady()) r.addError("TNT Tag arena not ready: " + a.getReadinessReport());
                if (a.getCenter() == null || a.getBorderRadius() <= 0)
                    r.addWarning("No centre/border set — Final Showdown & border chaos events disabled.");
                if (a.getSpectatorSpawn() == null) r.addWarning("No spectator spawn set.");
                if (a.getPowerupSpawns().isEmpty()) r.addWarning("No powerup spots set — powerups disabled.");
                return r;
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private int tntCountFor(long alive) {
        if (showdown || alive <= 4) return 1;
        if (alive <= 7) return 1;
        return Math.max(1, plugin.getConfig().getInt("game.tnt-holders-base", 2));
    }

    private int roundDuration() {
        if (showdown) return plugin.getConfig().getInt("game.showdown-round-seconds", 15);
        List<Integer> timers = plugin.getConfig().getIntegerList("game.round-timers");
        if (timers == null || timers.isEmpty()) timers = List.of(30, 28, 26, 24, 20);
        return timers.get(Math.min(currentRound - 1 < 0 ? 0 : currentRound - 1, timers.size() - 1));
    }

    /** Fires a KMC clutch moment (the stats/achievement engine reacts to it). */
    private void fireClutch(Player p, nl.kmc.core.event.ClutchMomentEvent.ClutchType type, String desc) {
        try { Bukkit.getPluginManager().callEvent(
                new nl.kmc.core.event.ClutchMomentEvent(p, type, desc, registration.getId())); }
        catch (Throwable t) { log.warning("clutch event failed: " + t); }
    }

    private long aliveCount() { return players.values().stream().filter(PlayerState::isAlive).count(); }
    private List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID id : players.keySet()) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        return out;
    }

    private boolean flowOn(String key) { return plugin.getConfig().getBoolean("start-flow." + key, true); }
    private int seconds(String key, int def) { return Math.max(0, plugin.getConfig().getInt("start-sequence." + key, def)); }
    private int totalStartSeconds() {
        int t = 0;
        if (flowOn("introduction")) t += seconds("intro-seconds", 5);
        if (flowOn("tutorial"))     t += seconds("tutorial-seconds", 10);
        if (flowOn("countdown"))    t += seconds("countdown-seconds", 5);
        return Math.max(1, t);
    }

    private void schedule(long delay, Runnable r) {
        flowTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> { if (getState().isRunning()) r.run(); }, Math.max(0, delay)));
    }
    private void cancelFlow() { flowTasks.forEach(t -> { if (t != null) t.cancel(); }); flowTasks.clear(); }
    private void cancelAll() {
        if (roundTimerTask != null) { roundTimerTask.cancel(); roundTimerTask = null; }
        if (tickTask       != null) { tickTask.cancel();       tickTask       = null; }
        if (hudTask        != null) { hudTask.cancel();        hudTask        = null; }
        if (chaosTask      != null) { chaosTask.cancel();      chaosTask      = null; }
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            GamePlayerUtil.resetPlayer(p);
            if (lobby != null) p.teleport(lobby);
        }
    }

    private static PotionEffectType effect(String name) {
        try { return io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(name)); }
        catch (Throwable t) { return null; }
    }
}
