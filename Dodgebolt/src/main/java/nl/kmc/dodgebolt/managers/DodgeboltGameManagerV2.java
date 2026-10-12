package nl.kmc.dodgebolt.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.domain.PointAward;
import nl.kmc.dodgebolt.DodgeboltPlugin;
import nl.kmc.dodgebolt.managers.ArenaConfig.Side;
import nl.kmc.dodgebolt.models.DbPlayer;
import nl.kmc.game.api.*;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Dodgebolt — two teams on two platforms, bows and a handful of arrows. A hit knocks a player out for the
 * round; the team with nobody left loses it. First team to {@code game.rounds-to-win} rounds takes the match.
 *
 * <p>Meant as the KMC finale (the two finalists are read from the automation), but it runs just as well on
 * its own with {@code /dodgebolt start [teamA teamB]}.
 *
 * <p>Rounds are driven by one timer ({@link #tick5()}, every 5 ticks): void check every call, the round
 * clock / arrow supply every fourth call. A round ends when a side is empty, or on time (more players
 * left wins; level → one overtime with extra supply; still level → most kills; still level → replay).
 */
public final class DodgeboltGameManagerV2 extends BaseGameManager {

    private final DodgeboltPlugin plugin;

    private final Map<UUID, DbPlayer> players = new LinkedHashMap<>();
    private String teamAId, teamBId;
    private String requestedA, requestedB;

    private int scoreA, scoreB;
    private int round, roundsPlayed;
    private volatile boolean roundLive;
    private boolean overtimeUsed;
    private boolean walkover;
    private int secondsLeft, sinceSupply, tickCounter;
    private int roundSeconds;

    private BukkitTask timerTask, stepTask;
    private BossBar bossBar;
    private StandardStartFlow startFlow;

    public DodgeboltGameManagerV2(DodgeboltPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    /** {@code /dodgebolt start A B}: the teams for the next (non-finale) start. */
    public void requestTeams(String a, String b) { this.requestedA = a; this.requestedB = b; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        players.clear();
        scoreA = scoreB = 0;
        round = roundsPlayed = 0;
        roundLive = false;
        walkover = false;

        if (!resolveTeams()) {
            log.severe("[Dodgebolt] Fewer than two teams available — aborting.");
            end();
            return;
        }

        ArenaConfig arena = plugin.getArena();
        Location spectator = spectatorSpot();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var team = api.teams().getTeamByPlayer(p.getUniqueId()).orElse(null);
            Side side = team == null ? null
                    : team.getId().equals(teamAId) ? Side.A
                    : team.getId().equals(teamBId) ? Side.B : null;
            if (side != null) {
                players.put(p.getUniqueId(), new DbPlayer(p.getUniqueId(), p.getName(), team.getId(), side));
            } else {
                GamePlayerUtil.resetPlayer(p);
                p.setGameMode(GameMode.SPECTATOR);
                if (spectator != null) p.teleport(spectator);
            }
        }
        long a = players.values().stream().filter(d -> d.getSide() == Side.A).count();
        long b = players.values().stream().filter(d -> d.getSide() == Side.B).count();
        if (a == 0 || b == 0) {
            walkover = true;
            log.warning("[Dodgebolt] A side has no online players — walkover for the other team.");
        }

        // Everybody to their platform and held there while the presentation runs.
        int ia = 0, ib = 0;
        for (DbPlayer d : players.values()) {
            Player p = Bukkit.getPlayer(d.getUuid());
            if (p == null) continue;
            GamePlayerUtil.resetPlayer(p);
            List<Location> sp = arena.spawns(d.getSide());
            int idx = d.getSide() == Side.A ? ia++ : ib++;
            if (!sp.isEmpty()) GamePlayerUtil.safeTeleport(p, sp.get(idx % sp.size()));
            GamePlayerUtil.freezePlayer(p, 20 * 60);
        }

        roundSeconds = Math.max(15, plugin.getConfig().getInt("game.round-seconds", 90));
        bossBar = Bukkit.createBossBar("§c§lDODGEBOLT", BarColor.RED, BarStyle.SEGMENTED_10);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        List<Player> parts = players.keySet().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        Location center = arena.center();
        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§c§lDODGEBOLT"; }
                    @Override public String introSubtitle() { return "@dodgebolt.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@dodgebolt.tut.1", "@dodgebolt.tut.2", "@dodgebolt.tut.3", "@dodgebolt.tut.4");
                    }
                    @Override public Location flyoverCenter() { return center; }
                    @Override public void onFinished() { beginMatch(); }
                });
        startFlow.prepareAndFreeze();
    }

    /** Which two teams play: the finalists, else the requested pair, else the top two of the standings. */
    private boolean resolveTeams() {
        List<String> ids = new ArrayList<>();
        var am = plugin.getKmcCore().getAutomationManager();
        if (am != null && am.isFinalePhase()) ids.addAll(am.getFinaleTeamIds());
        if (ids.size() < 2 && requestedA != null && requestedB != null) {
            ids.clear();
            ids.add(requestedA);
            ids.add(requestedB);
        }
        requestedA = requestedB = null;
        if (ids.size() < 2) {
            ids.clear();
            for (KMCTeam t : api.teams().getStandings()) {
                ids.add(t.getId());
                if (ids.size() == 2) break;
            }
        }
        if (ids.size() < 2) return false;
        teamAId = ids.get(0);
        teamBId = ids.get(1);
        return true;
    }

    private Location spectatorSpot() {
        Location s = plugin.getArena().spectator();
        return s != null ? s : plugin.getArena().center();
    }

    @Override protected void onCountdownStart() { /* presentation runs from onGameStart */ }

    @Override
    protected void onGameStart() { if (startFlow != null) startFlow.start(); }

    /** Presentation finished: the match begins. */
    private void beginMatch() {
        if (!getState().isRunning()) return;
        stepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick5, 5L, 5L);
        if (walkover) {
            // One side has nobody: the other side takes the match without a shot being fired.
            int toWin = Math.max(1, plugin.getConfig().getInt("game.rounds-to-win", 2));
            boolean aEmpty = players.values().stream().noneMatch(d -> d.getSide() == Side.A);
            if (aEmpty) scoreB = toWin; else scoreA = toWin;
            Bukkit.getScheduler().runTaskLater(plugin, this::end, 20L);
            return;
        }
        beginRound();
    }

    // ── Rounds ────────────────────────────────────────────────────────────────

    private void beginRound() {
        if (!getState().isRunning()) return;
        round++;
        roundLive = false;
        overtimeUsed = false;
        sinceSupply = 0;
        clearArrows();

        ArenaConfig arena = plugin.getArena();
        int ia = 0, ib = 0;
        for (DbPlayer d : players.values()) {
            d.resetForRound();
            Player p = Bukkit.getPlayer(d.getUuid());
            if (p == null) { d.setAlive(false); continue; }
            GamePlayerUtil.resetPlayer(p);
            p.setGameMode(GameMode.ADVENTURE);
            List<Location> sp = arena.spawns(d.getSide());
            int idx = d.getSide() == Side.A ? ia++ : ib++;
            if (!sp.isEmpty()) GamePlayerUtil.safeTeleport(p, sp.get(idx % sp.size()));
            GamePlayerUtil.freezePlayer(p, 20 * 30);
            p.getInventory().setItem(0, bowFor(p));
            p.getInventory().setHeldItemSlot(0);
        }
        handOutArrows(Side.A);
        handOutArrows(Side.B);

        int intro = Math.max(2, plugin.getConfig().getInt("game.round-intro-seconds", 4));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(api.tr(p.getUniqueId(), "dodgebolt.round.title", round),
                    api.tr(p.getUniqueId(), "dodgebolt.round.sub", scoreA, scoreB), 0, 30, 5);
        }
        updateBossBar();

        for (int s = intro - 1; s >= 1; s--) {
            final int left = s;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!getState().isRunning() || roundLive) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle("§e§l" + left, "", 0, 18, 2);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
                }
            }, (intro - s) * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::goLive, intro * 20L);
    }

    private void goLive() {
        if (!getState().isRunning() || roundLive) return;
        for (DbPlayer d : players.values()) {
            Player p = Bukkit.getPlayer(d.getUuid());
            if (p != null) {
                GamePlayerUtil.unfreezePlayer(p);
                p.sendTitle(api.tr(p.getUniqueId(), "dodgebolt.go.title"), api.tr(p.getUniqueId(), "dodgebolt.go.sub"), 0, 25, 5);
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.2f);
            }
        }
        secondsLeft = roundSeconds;
        tickCounter = 0;
        roundLive = true;
        updateBossBar();
    }

    /** Hands the side's starting arrows to different team members (one each, round-robin). */
    private void handOutArrows(Side side) {
        int arrows = Math.max(1, plugin.getConfig().getInt("game.team-arrows", 3));
        List<Player> team = new ArrayList<>();
        for (DbPlayer d : players.values()) {
            if (d.getSide() != side || !d.isAlive()) continue;
            Player p = Bukkit.getPlayer(d.getUuid());
            if (p != null) team.add(p);
        }
        if (team.isEmpty()) return;
        Collections.shuffle(team);
        for (int i = 0; i < arrows; i++) {
            Player p = team.get(i % team.size());
            p.getInventory().addItem(new ItemStack(Material.ARROW, 1));
        }
    }

    private ItemStack bowFor(Player p) {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.setDisplayName(api.tr(p.getUniqueId(), "dodgebolt.bow"));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            bow.setItemMeta(meta);
        }
        return bow;
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private void tick5() {
        if (!getState().isRunning() || !roundLive) return;
        double voidY = plugin.getArena().voidY();
        for (DbPlayer d : new ArrayList<>(players.values())) {
            if (!d.isAlive()) continue;
            Player p = Bukkit.getPlayer(d.getUuid());
            if (p == null || !p.isOnline()) { eliminate(d.getUuid(), null, "quit"); continue; }
            if (p.getLocation().getY() < voidY) eliminate(d.getUuid(), null, "void");
        }
        if (!roundLive) return;

        if (++tickCounter % 4 != 0) return;           // once per second from here on
        secondsLeft--;
        sinceSupply++;
        int every = overtimeUsed ? 5 : Math.max(5, plugin.getConfig().getInt("game.supply-seconds", 15));
        if (sinceSupply >= every) { sinceSupply = 0; dropSupply(); }
        updateBossBar();
        if (secondsLeft <= 0) resolveTimeUp();
    }

    private void dropSupply() {
        int n = Math.max(1, plugin.getConfig().getInt("game.supply-arrows", 1));
        for (Side s : Side.values()) {
            Location at = plugin.getArena().drop(s);
            if (at == null || at.getWorld() == null) continue;
            long alive = players.values().stream().filter(d -> d.getSide() == s && d.isAlive()).count();
            if (alive == 0) continue;
            Item item = at.getWorld().dropItem(at, new ItemStack(Material.ARROW, n));
            item.setGlowing(true);
            item.setPickupDelay(10);
        }
        for (DbPlayer d : players.values()) {
            Player p = Bukkit.getPlayer(d.getUuid());
            if (p != null && d.isAlive()) {
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.7f);
                p.sendActionBar(net.kyori.adventure.text.Component.text(api.tr(p.getUniqueId(), "dodgebolt.supply")));
            }
        }
    }

    private void resolveTimeUp() {
        int a = alive(Side.A), b = alive(Side.B);
        if (a != b) { endRound(a > b ? Side.A : Side.B, "dodgebolt.reason.more-alive"); return; }
        if (!overtimeUsed) {
            overtimeUsed = true;
            secondsLeft = Math.max(10, plugin.getConfig().getInt("game.overtime-seconds", 30));
            sinceSupply = 0;
            dropSupply();
            tellKey("game.overtime.chat", secondsLeft);
            titleKey("game.overtime", 5, 40, 10);
            return;
        }
        int ka = roundKills(Side.A), kb = roundKills(Side.B);
        if (ka != kb) { endRound(ka > kb ? Side.A : Side.B, "dodgebolt.reason.more-kills"); return; }
        endRound(null, "dodgebolt.reason.draw");
    }

    private void endRound(Side winner, String reasonKey) {
        if (!roundLive) return;
        roundLive = false;
        roundsPlayed++;
        if (winner == Side.A) scoreA++;
        else if (winner == Side.B) scoreB++;

        int win = plugin.getConfig().getInt("points.round-win", 50);
        if (winner != null && win > 0) {
            for (DbPlayer d : players.values()) {
                if (d.getSide() == winner)
                    api.points().givePoints(d.getUuid(), win, PointAward.Reason.OBJECTIVE, registration.getId());
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            String winnerName = winner == null ? api.tr(id, "dodgebolt.nobody") : teamLabel(winner);
            p.sendMessage(api.tr(id, "dodgebolt.round.result", round, winnerName, api.tr(id, reasonKey), scoreA, scoreB));
            p.sendTitle(winner == null ? api.tr(id, "dodgebolt.round.draw") : api.tr(id, "dodgebolt.round.won", winnerName),
                    "§e" + scoreA + " §7- §e" + scoreB, 5, 50, 10);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
        updateBossBar();

        int toWin = Math.max(1, plugin.getConfig().getInt("game.rounds-to-win", 2));
        int maxRounds = Math.max(toWin, plugin.getConfig().getInt("game.max-rounds", 7));
        if (scoreA >= toWin || scoreB >= toWin || roundsPlayed >= maxRounds) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (getState().isRunning()) end(); }, 50L);
        } else {
            int pause = Math.max(2, plugin.getConfig().getInt("game.round-break-seconds", 6));
            Bukkit.getScheduler().runTaskLater(plugin, this::beginRound, pause * 20L);
        }
    }

    // ── Eliminations ──────────────────────────────────────────────────────────

    /**
     * Knocks a participant out for this round.
     *
     * @param killerId shooter, or null (void / disconnect)
     * @param how      "arrow", "void" or "quit" (for the message)
     */
    public void eliminate(UUID victimId, UUID killerId, String how) {
        DbPlayer v = players.get(victimId);
        if (v == null || !v.isAlive() || !roundLive) return;
        v.setAlive(false);

        Player vp = Bukkit.getPlayer(victimId);
        if (vp != null) {
            // Arrows still in the quiver stay on the platform for the teammates.
            int arrows = 0;
            for (ItemStack it : vp.getInventory().getContents())
                if (it != null && it.getType() == Material.ARROW) arrows += it.getAmount();
            if (arrows > 0 && !"void".equals(how) && vp.getWorld() != null)
                vp.getWorld().dropItem(vp.getLocation().add(0, 0.5, 0), new ItemStack(Material.ARROW, arrows));
            vp.getInventory().clear();
            GamePlayerUtil.unfreezePlayer(vp);
            vp.setGameMode(GameMode.SPECTATOR);
            vp.sendTitle(api.tr(victimId, "dodgebolt.out.title"), api.tr(victimId, "dodgebolt.out.sub"), 0, 40, 10);
            vp.playSound(vp.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
        }

        DbPlayer k = killerId != null ? players.get(killerId) : null;
        if (k != null && k.getSide() != v.getSide()) {
            api.games().announceElimination(victimId, killerId, registration.getId());
            k.addKill();
            int pts = plugin.getConfig().getInt("points.per-kill", 25);
            if (pts > 0) api.points().givePoints(killerId, pts, PointAward.Reason.KILL, registration.getId());
            try { statsService.recordKill(killerId); statsService.recordDeath(victimId); } catch (Throwable ignored) { }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            String msg = (k != null && k.getSide() != v.getSide())
                    ? api.tr(id, "dodgebolt.hit", sideColor(v.getSide()) + v.getName(), sideColor(k.getSide()) + k.getName())
                    : api.tr(id, "dodgebolt." + (how == null ? "out" : how), sideColor(v.getSide()) + v.getName());
            p.sendMessage(msg);
        }
        updateBossBar();

        int a = alive(Side.A), b = alive(Side.B);
        if (a == 0 && b == 0) endRound(null, "dodgebolt.reason.draw");
        else if (a == 0) endRound(Side.B, "dodgebolt.reason.eliminated");
        else if (b == 0) endRound(Side.A, "dodgebolt.reason.eliminated");
    }

    private int alive(Side s) {
        int n = 0;
        for (DbPlayer d : players.values()) if (d.getSide() == s && d.isAlive()) n++;
        return n;
    }

    private int roundKills(Side s) {
        int n = 0;
        for (DbPlayer d : players.values()) if (d.getSide() == s) n += d.getRoundKills();
        return n;
    }

    private String sideColor(Side s) {
        KMCTeam t = api.teams().getTeam(s == Side.A ? teamAId : teamBId).orElse(null);
        return t != null ? String.valueOf(t.getColor()) : "§f";
    }

    private String teamLabel(Side s) {
        KMCTeam t = api.teams().getTeam(s == Side.A ? teamAId : teamBId).orElse(null);
        return t != null ? t.getColor() + t.getDisplayName() : s.name();
    }

    // ── End ───────────────────────────────────────────────────────────────────

    @Override
    protected void onGameEnd() {
        roundLive = false;
        if (timerTask != null) { timerTask.cancel(); timerTask = null; }
        if (stepTask != null) { stepTask.cancel(); stepTask = null; }
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        // Winner: most rounds, then kills, then the better-seeded side (A).
        Side winner;
        if (scoreA != scoreB) winner = scoreA > scoreB ? Side.A : Side.B;
        else {
            int ka = players.values().stream().filter(d -> d.getSide() == Side.A).mapToInt(DbPlayer::getMatchKills).sum();
            int kb = players.values().stream().filter(d -> d.getSide() == Side.B).mapToInt(DbPlayer::getMatchKills).sum();
            winner = kb > ka ? Side.B : Side.A;
        }
        String winnerId = winner == Side.A ? teamAId : teamBId;
        String loserId  = winner == Side.A ? teamBId : teamAId;

        int win = plugin.getConfig().getInt("points.match-win", 150);
        int loss = plugin.getConfig().getInt("points.match-loss", 50);
        for (DbPlayer d : players.values()) {
            boolean won = d.getSide() == winner;
            int pts = won ? win : loss;
            if (pts > 0) api.points().givePoints(d.getUuid(), pts, PointAward.Reason.OBJECTIVE, registration.getId());
            api.games().recordGameParticipation(d.getUuid(), d.getName(), registration.getId(), won);
        }
        api.points().awardTeamPlacement(winnerId, 1, registration.getId());
        api.points().awardTeamPlacement(loserId, 2, registration.getId());

        // The finale reads the champion from here.
        try { plugin.getKmcCore().getAutomationManager().reportFinaleWinner(winnerId); }
        catch (Throwable t) { log.warning("reportFinaleWinner failed: " + t); }

        String winnerLabel = teamLabel(winner);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            p.sendMessage(api.tr(id, "dodgebolt.final.header"));
            p.sendMessage(api.tr(id, "dodgebolt.final.winner", winnerLabel, scoreA, scoreB));
            p.sendTitle(api.tr(id, "dodgebolt.final.title"), winnerLabel, 10, 70, 20);
        }

        clearArrows();
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (Player p : Bukkit.getOnlinePlayers()) {
            GamePlayerUtil.resetPlayer(p);
            GamePlayerUtil.teleportToLobby(p, lobby);
        }

        // Ranking: winning team first, best killers first inside each team.
        DbPlayer mvp = players.values().stream().max(Comparator.comparingInt(DbPlayer::getMatchKills)).orElse(null);
        List<UUID> order = new ArrayList<>();
        final Side w = winner;
        players.values().stream()
                .sorted(Comparator.<DbPlayer>comparingInt(d -> d.getSide() == w ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(DbPlayer::getMatchKills).reversed()))
                .forEach(d -> order.add(d.getUuid()));
        String desc = org.bukkit.ChatColor.stripColor(winnerLabel);
        UUID mvpId = mvp != null && mvp.getMatchKills() > 0 ? mvp.getUuid() : null;
        String mvpName = mvp != null ? mvp.getName() : null;
        players.clear();
        fireResult(desc, mvpId, mvpName, order);
    }

    /** Removes loose arrows (shot or dropped) from the arena world. */
    private void clearArrows() {
        World w = plugin.getArena().world();
        if (w == null) return;
        Location c = plugin.getArena().center();
        for (Entity e : w.getEntitiesByClasses(Arrow.class, Item.class)) {
            if (c != null && e.getLocation().distanceSquared(c) > 120 * 120) continue;
            if (e instanceof Item it && it.getItemStack().getType() != Material.ARROW) continue;
            e.remove();
        }
    }

    // ── Boss bar / scoreboard ─────────────────────────────────────────────────

    private void updateBossBar() {
        if (bossBar == null) return;
        String time = roundLive ? String.format("%02d:%02d", Math.max(0, secondsLeft) / 60, Math.max(0, secondsLeft) % 60) : "--:--";
        bossBar.setTitle("§c§lDODGEBOLT §8| §7R" + round + " §8| " + sideColor(Side.A) + scoreA + " §7- "
                + sideColor(Side.B) + scoreB + " §8| §a" + alive(Side.A) + " §7v §a" + alive(Side.B)
                + (overtimeUsed && roundLive ? " §8| §c⚡" : "") + " §8| §b" + time);
        double progress = roundLive ? Math.max(0, Math.min(1, secondsLeft / (double) (overtimeUsed
                ? Math.max(10, plugin.getConfig().getInt("game.overtime-seconds", 30)) : roundSeconds))) : 1.0;
        bossBar.setProgress(progress);
    }

    @Override protected String getScoreboardTitle() { return "§c§lDODGEBOLT"; }

    @Override
    protected List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning() || players.isEmpty()) return defaultScoreboardLines(viewer);
        UUID id = viewer.getUniqueId();
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "dodgebolt.sb.round", Math.max(1, round)));
        l.add("");
        l.add(teamLabel(Side.A) + " §8» §e" + scoreA);
        l.add(teamLabel(Side.B) + " §8» §e" + scoreB);
        l.add("");
        l.add(api.tr(id, "dodgebolt.sb.alive", alive(Side.A), alive(Side.B)));
        if (roundLive) l.add(api.tr(id, "dodgebolt.sb.time", Math.max(0, secondsLeft)));
        DbPlayer me = players.get(id);
        if (me != null) {
            l.add("");
            l.add(me.isAlive() ? api.tr(id, "dodgebolt.sb.in") : api.tr(id, "dodgebolt.sb.out"));
            l.add(api.tr(id, "dodgebolt.sb.kills", me.getMatchKills()));
        }
        return l;
    }

    // ── Reconnect / validation ────────────────────────────────────────────────

    @Override protected PlayerGameState capturePlayerState(Player player) { return null; }

    @Override protected void restorePlayerState(Player player, PlayerGameState snapshot) { /* no reconnect */ }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                plugin.getArena().errors().forEach(r::addError);
                plugin.getArena().warnings().forEach(r::addWarning);
                return r;
            }
            @Override public String getGameName() { return "Dodgebolt"; }
        };
    }

    // ── Accessors for the listener ────────────────────────────────────────────

    public DbPlayer get(UUID uuid)    { return players.get(uuid); }
    public boolean isParticipant(UUID uuid) { return players.containsKey(uuid); }
    public boolean isRoundLive()      { return roundLive; }
}
