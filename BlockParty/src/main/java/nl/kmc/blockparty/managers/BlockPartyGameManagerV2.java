package nl.kmc.blockparty.managers;

import nl.kmc.blockparty.BlockPartyPlugin;
import nl.kmc.blockparty.models.BPPlayer;
import nl.kmc.blockparty.models.ChaosEvent;
import nl.kmc.blockparty.models.Colors;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * V2 Block Party manager — the colour-elimination championship.
 *
 * <p>Round loop: generate floor → pick a safe target colour → countdown →
 * remove every other colour → players not on the colour fall and are
 * eliminated → wait → regenerate. Difficulty escalates by phase and by the
 * number of players still alive (Endgame &lt; 8, Final Showdown &lt; 4).
 */
public final class BlockPartyGameManagerV2 extends BaseGameManager {

    private final BlockPartyPlugin plugin;
    private final ArenaManager     arena;
    private final FloorGenerator   floor;
    private final Random           random = new Random();

    private final Map<UUID, BPPlayer> players         = new LinkedHashMap<>();
    private final List<UUID>          eliminationOrder = new ArrayList<>(); // first eliminated first

    private int        round;
    private Set<Material> keepColours = new HashSet<>();   // colour(s) that survive this round
    private Material   displayColour;                       // shown to players (may be fake)
    private ChaosEvent chaos;                               // active chaos event, or null
    private int        roundSeconds;
    private int        secondsLeft;
    private BukkitTask roundTask;
    private BossBar    bossBar;

    public BlockPartyGameManagerV2(BlockPartyPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.arena  = plugin.getArenaManager();
        this.floor  = plugin.floorGen();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        players.clear();
        eliminationOrder.clear();
        round = 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            players.put(p.getUniqueId(), new BPPlayer(p.getUniqueId(), p.getName()));
            GamePlayerUtil.resetPlayer(p);
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(floor.randomFloorLocation());
        }

        bossBar = Bukkit.createBossBar("§d§lBLOCK PARTY", BarColor.PINK, BarStyle.SEGMENTED_10);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        broadcastTitle("§d§lBLOCK PARTY", "§7Maak je klaar...", 10, 50, 15);
    }

    @Override
    protected void onCountdownStart() {
        // Tutorial card during the grace period.
        broadcast("§8§m                                        ");
        broadcast("  §d§lBLOCK PARTY");
        broadcast("  §7Vind de §fjuiste kleur §7voordat de tijd om is.");
        broadcast("  §7Ga op de getoonde kleur staan.");
        broadcast("  §cVerkeerde kleur = §4eliminatie§c.");
        broadcast("§8§m                                        ");
        for (Player p : alivePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.3f);
    }

    @Override
    protected void onGameStart() {
        startRound();
    }

    @Override
    protected void onGameEnd() {
        if (roundTask != null) { roundTask.cancel(); roundTask = null; }
        if (bossBar  != null) { bossBar.removeAll(); bossBar = null; }
        floor.clear();

        // Whoever is still alive is the winner; append to the elimination order last.
        List<UUID> stillAlive = players.values().stream().filter(BPPlayer::isAlive).map(BPPlayer::getUuid).toList();
        for (UUID u : stillAlive) if (!eliminationOrder.contains(u)) eliminationOrder.add(u);

        // Final placement: last eliminated = best place.
        List<UUID> placement = new ArrayList<>(eliminationOrder);
        Collections.reverse(placement);

        BPPlayer winner = placement.isEmpty() ? null : players.get(placement.get(0));
        awardAndRecord(placement, winner);

        broadcastFinalStandings(placement, winner);

        UUID   mvpUuid = pickMvp(winner);
        String mvpName = mvpUuid != null && players.containsKey(mvpUuid) ? players.get(mvpUuid).getName() : null;

        returnToLobby();
        String winnerDesc = winner != null ? winner.getName() + " wint Block Party!" : "Geen winnaar";
        fireResult(winnerDesc, mvpUuid, mvpName, placement);
        players.clear();
    }

    // ── Round flow ────────────────────────────────────────────────────────────

    private void startRound() {
        if (!getState().isRunning()) return;
        long aliveCount = alivePlayers().size();
        if (aliveCount <= 1) { end(); return; }

        round++;
        int phase = phaseFor(round);
        clearChaos();

        // Generate the floor for this round's difficulty.
        FloorGenerator.Result result = floor.generate(coloursFor(phase, aliveCount), clusterFor(phase));

        // Decide on a chaos event (may tweak timer/colours/display below).
        chaos = rollChaos(aliveCount);

        // Pick a target colour that is guaranteed survivable (enough blocks for everyone alive).
        Material target = pickSafeColour(result, (int) aliveCount);
        keepColours = new HashSet<>(Set.of(target));

        // DOUBLE_COLOR: a second colour also survives (more room, but more confusing).
        if (chaos == ChaosEvent.DOUBLE_COLOR) {
            Material second = pickSafeColour(result, 1, target);
            if (second != null) keepColours.add(second);
        }

        // FAKE_COLOR: show the wrong name; the real answer is hinted subtly.
        displayColour = target;
        if (chaos == ChaosEvent.FAKE_COLOR) {
            displayColour = result.palette().stream().filter(m -> m != target).findFirst().orElse(target);
        }

        roundSeconds = timerFor(phase, aliveCount);
        if (chaos == ChaosEvent.RAPID_FIRE) roundSeconds = Math.max(2, roundSeconds - 2);
        secondsLeft  = roundSeconds;

        players.values().stream().filter(BPPlayer::isAlive).forEach(BPPlayer::beginRound);
        applyChaosEffects();
        announceRound(target);

        roundTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** One-second tick: update displays, track clutch positions, fire elimination at zero. */
    private void tick() {
        if (!getState().isRunning()) return;

        // Track who is standing on a surviving colour (for clutch detection).
        for (Player p : alivePlayers()) {
            BPPlayer bp = players.get(p.getUniqueId());
            if (bp == null) continue;
            boolean onTarget = keepColours.contains(blockUnder(p));
            // Stepping onto a correct colour in the last second = a clutch save.
            if (onTarget && !bp.wasOnTarget() && secondsLeft <= 1) bp.markClutch();
            bp.setWasOnTarget(onTarget);
        }

        updateDisplays();

        if (secondsLeft <= 0) { eliminate(); return; }
        if (secondsLeft <= 3) {
            for (Player p : alivePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.6f);
        }
        secondsLeft--;
    }

    private void eliminate() {
        if (roundTask != null) { roundTask.cancel(); roundTask = null; }

        List<BPPlayer> survivors = new ArrayList<>();
        List<BPPlayer> dropped   = new ArrayList<>();
        for (Player p : alivePlayers()) {
            BPPlayer bp = players.get(p.getUniqueId());
            if (bp == null) continue;
            if (keepColours.contains(blockUnder(p))) survivors.add(bp);
            else                                     dropped.add(bp);
        }

        // Remove every non-surviving colour — droppers fall into the void.
        floor.removeAllExcept(keepColours);

        boolean chaosActive = chaos != null;
        for (BPPlayer bp : survivors) {
            bp.surviveRound(round, chaosActive);
            Player p = Bukkit.getPlayer(bp.getUuid());
            if (p == null) continue;
            if (bp.isClutchThisRound()) {
                p.sendTitle("§a§l⚡ CLUTCH SAVE", "§7Op het laatste moment!", 3, 30, 8);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
                grant(p, "blockparty_clutch_king");
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }
        }
        for (BPPlayer bp : dropped) eliminatePlayer(bp);

        long alive = alivePlayers().size();
        if (!dropped.isEmpty())
            broadcast("§c☠ §7" + dropped.size() + " speler(s) geëlimineerd §8— §a" + alive + " §7over");

        if (alive <= 1) {
            Bukkit.getScheduler().runTaskLater(plugin, this::end, 40L);
            return;
        }
        long delay = Math.max(1, plugin.getConfig().getInt("game.regen-delay-seconds", 2)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, this::startRound, delay);
    }

    private void eliminatePlayer(BPPlayer bp) {
        bp.eliminate(round);
        eliminationOrder.add(bp.getUuid());
        statsService.recordPlacement(bp.getUuid(), alivePlayers().size() + 1);
        statsService.recordSurvivalSeconds(bp.getUuid(), bp.getRoundsSurvived());

        Player p = Bukkit.getPlayer(bp.getUuid());
        if (p == null) return;
        p.sendTitle("§c§lGEËLIMINEERD", "§7Ronde " + round + " §8• §7je overleefde " + bp.getRoundsSurvived() + " rondes", 5, 45, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_DEATH, 1f, 0.8f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.setGameMode(GameMode.SPECTATOR);
            if (arena.getSpectator() != null) p.teleport(arena.getSpectator());
        }, 15L);
    }

    // ── Difficulty / phase ────────────────────────────────────────────────────

    private int phaseFor(int r) {
        if (r <= 4)  return 1;
        if (r <= 8)  return 2;
        if (r <= 12) return 3;
        if (r <= 16) return 4;
        return 5;
    }

    private int timerFor(int phase, long alive) {
        var cfg = plugin.getConfig();
        if (alive < cfg.getInt("game.final-showdown-threshold", 4)) return cfg.getInt("game.timer.final-showdown", 2);
        if (alive < cfg.getInt("game.endgame-threshold", 8))        return cfg.getInt("game.timer.endgame", 3);
        return cfg.getInt("game.timer.phase" + phase, 8 - phase);
    }

    private int coloursFor(int phase, long alive) {
        int base = plugin.getConfig().getInt("game.colours.phase" + phase, 2 + phase * 2);
        if (alive < plugin.getConfig().getInt("game.endgame-threshold", 8)) base += 2; // more variety in endgame
        return Math.min(16, Math.max(2, base));
    }

    private int clusterFor(int phase) {
        return plugin.getConfig().getInt("game.cluster-size.phase" + phase, Math.max(6, 56 - phase * 10));
    }

    /** Picks a colour with enough blocks for every alive player; falls back to the largest. */
    private Material pickSafeColour(FloorGenerator.Result r, int needed, Material... exclude) {
        Set<Material> ex = new HashSet<>(Arrays.asList(exclude));
        List<Material> safe = new ArrayList<>();
        Material largest = null; int largestCount = -1;
        for (var e : r.counts().entrySet()) {
            if (ex.contains(e.getKey())) continue;
            if (e.getValue() > largestCount) { largestCount = e.getValue(); largest = e.getKey(); }
            if (e.getValue() >= needed) safe.add(e.getKey());
        }
        if (!safe.isEmpty()) return safe.get(random.nextInt(safe.size()));
        return largest; // no impossible rounds — always return the roomiest colour
    }

    // ── Chaos events ──────────────────────────────────────────────────────────

    private ChaosEvent rollChaos(long alive) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("chaos.enabled", true)) return null;
        if (round < cfg.getInt("chaos.start-round", 5)) return null;
        double chance = alive < cfg.getInt("game.endgame-threshold", 8)
                ? cfg.getDouble("chaos.endgame-chance", 0.45)
                : cfg.getDouble("chaos.base-chance", 0.22);
        if (random.nextDouble() > chance) return null;
        ChaosEvent[] all = ChaosEvent.values();
        return all[random.nextInt(all.length)];
    }

    private void applyChaosEffects() {
        if (chaos == null) return;
        for (Player p : alivePlayers()) {
            switch (chaos) {
                case LOW_GRAVITY -> p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, roundSeconds * 20 + 20, 2, false, false));
                case SPEED_ROUND, ICE_FLOOR -> p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, roundSeconds * 20 + 20, 2, false, false));
                case DARKNESS -> p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, roundSeconds * 20, 0, false, false));
                case RANDOM_TP -> p.teleport(floor.randomFloorLocation());
                default -> { /* logic-only events handled elsewhere */ }
            }
        }
    }

    private boolean colorBlind() { return chaos == ChaosEvent.COLOR_BLIND; }

    private void clearChaos() {
        chaos = null;
        for (Player p : alivePlayers())
            for (PotionEffectType t : new PotionEffectType[]{PotionEffectType.JUMP_BOOST, PotionEffectType.SPEED, PotionEffectType.BLINDNESS})
                p.removePotionEffect(t);
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private void announceRound(Material actual) {
        String label = Colors.label(displayColour);
        if (chaos != null) {
            for (Player p : alivePlayers()) {
                p.sendTitle(chaos.title(), chaos.subtitle(), 3, 35, 8);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
            }
        }
        broadcast("§8§m                ");
        broadcast("  §7Ronde §f" + round + " §8• §7Doel: " + (colorBlind() ? "§8§o(geen hint — kijk goed!)" : label));
        if (chaos == ChaosEvent.FAKE_COLOR)
            broadcast("  §c⚠ §7Verborgen hint — de échte kleur is §o" + Colors.plain(actual).charAt(0) + "...");
        broadcast("§8§m                ");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (colorBlind()) return;
            for (Player p : alivePlayers())
                p.sendTitle("§7DOELKLEUR", label, 2, 25, 6);
        }, chaos != null ? 35L : 1L);
    }

    private void updateDisplays() {
        long alive = alivePlayers().size();
        String label = colorBlind() ? "§8???" : Colors.label(displayColour);

        if (bossBar != null) {
            bossBar.setColor(secondsLeft <= 2 ? BarColor.RED : BarColor.PINK);
            bossBar.setProgress(Math.max(0, Math.min(1, secondsLeft / (double) Math.max(1, roundSeconds))));
            bossBar.setTitle("§d§lBLOCK PARTY §8| §7Doel " + label + " §8| §c⏱ " + secondsLeft + "s §8| §a" + alive + " over");
        }
        String hud = "§7Sta op §r" + label + " §8| §c" + secondsLeft + "s §8| §a" + alive + " spelers over";
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendActionBar(net.kyori.adventure.text.Component.text(hud));
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        long alive = alivePlayers().size();
        long teams = players.values().stream().filter(BPPlayer::isAlive)
                .map(bp -> api.teams().getTeamByPlayer(bp.getUuid()).map(t -> t.getId()).orElse("·"))
                .distinct().count();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add("§7Ronde §f" + round + (chaos != null ? " §8(§d" + chaos.name() + "§8)" : ""));
        l.add("§7Doelkleur: " + (colorBlind() ? "§8???" : Colors.label(displayColour)));
        l.add("§7Tijd: §c" + Math.max(0, secondsLeft) + "s");
        l.add("");
        l.add("§7Spelers over: §a" + alive);
        l.add("§7Teams over: §b" + teams);
        BPPlayer me = players.get(viewer.getUniqueId());
        if (me != null) {
            l.add("");
            l.add(me.isAlive() ? "§aJe leeft nog!" : "§cGeëlimineerd (R" + me.getEliminatedRound() + ")");
            if (me.getClutches() > 0) l.add("§eClutches: §6" + me.getClutches());
        }
        return l;
    }

    // ── End-of-game: scoring, MVP, achievements ───────────────────────────────

    private void awardAndRecord(List<UUID> placement, BPPlayer winner) {
        var cfg = plugin.getConfig();
        int base = cfg.getInt("scoring.base", 250);
        int step = cfg.getInt("scoring.step", 10);
        int floorPts = cfg.getInt("scoring.floor", 25);
        int total = placement.size();

        for (int i = 0; i < placement.size(); i++) {
            UUID u = placement.get(i);
            BPPlayer bp = players.get(u);
            int pts = Math.max(floorPts, base - i * step);
            api.points().givePoints(u, pts, PointAward.Reason.PLACEMENT, registration.getId());
            statsService.recordPointsEarned(u, pts);
            api.games().recordGameParticipation(u, bp != null ? bp.getName() : "?", registration.getId(), i == 0);

            // Achievements.
            if (i == 0)              grant(u, "blockparty_color_master");
            if (i == 0 && bp != null && bp.getClutches() == 0) grant(u, "blockparty_untouchable");
            if (i < 5)               grant(u, "blockparty_survivor");
        }

        // Last-team-standing bonus.
        if (cfg.getBoolean("scoring.last-team-bonus-enabled", true) && winner != null) {
            api.teams().getTeamByPlayer(winner.getUuid()).ifPresent(t ->
                    api.points().giveTeamPoints(t.getId(), cfg.getInt("scoring.last-team-bonus", 150),
                            PointAward.Reason.BONUS, registration.getId()));
        }
    }

    /** MVP = the winner, unless someone clearly out-clutched them. */
    private UUID pickMvp(BPPlayer winner) {
        BPPlayer bestClutch = players.values().stream()
                .max(Comparator.comparingInt(BPPlayer::getClutches)).orElse(null);
        if (bestClutch != null && bestClutch.getClutches() >= 3
                && (winner == null || bestClutch.getClutches() > winner.getClutches() + 1))
            return bestClutch.getUuid();
        return winner != null ? winner.getUuid() : (bestClutch != null ? bestClutch.getUuid() : null);
    }

    private void broadcastFinalStandings(List<UUID> placement, BPPlayer winner) {
        broadcast("§8§m                                        ");
        broadcast("        §d§l🎉 BLOCK PARTY — UITSLAG");
        broadcast("§8§m                                        ");
        if (winner != null) {
            Player wp = Bukkit.getPlayer(winner.getUuid());
            broadcastTitle("§6§l" + winner.getName(), "§ewint Block Party!", 8, 60, 15);
            if (wp != null) {
                wp.getWorld().spawnParticle(Particle.FIREWORK, wp.getLocation().add(0, 1, 0), 60, 0.6, 1, 0.6, 0.1);
                wp.playSound(wp.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }
        String[] medals = {"§6🥇", "§7🥈", "§c🥉"};
        for (int i = 0; i < Math.min(5, placement.size()); i++) {
            BPPlayer bp = players.get(placement.get(i));
            if (bp == null) continue;
            String m = i < 3 ? medals[i] : "§7#" + (i + 1);
            broadcast("  " + m + " §f" + bp.getName() + " §8— §7overleefde §e" + bp.getRoundsSurvived()
                    + " §7rondes§8, §6" + bp.getClutches() + " clutches");
        }
        broadcast("§8§m                                        ");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>();
        for (BPPlayer bp : players.values()) {
            if (!bp.isAlive()) continue;
            Player p = Bukkit.getPlayer(bp.getUuid());
            if (p != null) out.add(p);
        }
        return out;
    }

    private Material blockUnder(Player p) {
        return p.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
    }

    private void grant(UUID uuid, String achievementId) {
        try { api.achievements().grant(uuid, achievementId); } catch (Throwable ignored) {}
    }

    private void grant(Player p, String achievementId) { grant(p.getUniqueId(), achievementId); }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        players.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            GamePlayerUtil.resetPlayer(p);
            p.setGameMode(GameMode.ADVENTURE);
            if (lobby != null) p.teleport(lobby);
        });
    }

    // ── Reconnect / validation ────────────────────────────────────────────────

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState s = new PlayerGameState();
        s.location = player.getLocation();
        BPPlayer bp = players.get(player.getUniqueId());
        if (bp != null) s.extra.put("alive", bp.isAlive());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        BPPlayer bp = players.get(player.getUniqueId());
        if (bp != null && !bp.isAlive()) {
            player.setGameMode(GameMode.SPECTATOR);
            if (arena.getSpectator() != null) player.teleport(arena.getSpectator());
        } else {
            player.teleport(floor.randomFloorLocation());
        }
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Block Party"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                for (String issue : arena.issues()) r.addError(issue);
                return r;
            }
        };
    }

    public Map<UUID, BPPlayer> getPlayers() { return Collections.unmodifiableMap(players); }
}
