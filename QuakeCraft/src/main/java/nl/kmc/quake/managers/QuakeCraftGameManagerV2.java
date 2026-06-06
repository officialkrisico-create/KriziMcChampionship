package nl.kmc.quake.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.TeamUtil;
import nl.kmc.quake.util.WeaponFactory;
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
 * V2 QuakeCraft manager — railgun FPS, first to kill-target or highest kills wins.
 *
 * <p>No elimination: players respawn after death. Team-friendly-fire configurable.
 * Points per kill, killstreak bonuses, and revenge bonuses all apply.
 */
public final class QuakeCraftGameManagerV2 extends BaseGameManager {

    private final QuakeCraftPlugin plugin;

    private final Map<UUID, PlayerState> players    = new LinkedHashMap<>();
    private final Map<UUID, UUID>        lastKilledBy = new HashMap<>();
    private final Map<UUID, Long>        lastKilledAt = new HashMap<>();

    private BukkitTask gameTimerTask;
    private BossBar    bossBar;
    private int        remainingSeconds;

    private World   arenaWorld;
    private boolean arenaWorldPreviousPvp;

    /**
     * QuakeCraft match-start state machine, layered on top of the generic
     * {@link BaseGameManager} lifecycle. Combat / scoring / abilities only go
     * live in {@link MatchPhase#RUNNING}. The presentation phases (intro,
     * tutorial, countdown) are config-driven and designed to be reused by
     * future KMC minigames that want a cinematic start.
     */
    public enum MatchPhase { INITIALIZING, INTRODUCTION, TUTORIAL, COUNTDOWN, RUNNING, ENDING, POST_GAME }

    private MatchPhase phase = MatchPhase.INITIALIZING;

    /** Resolved at INITIALIZING: KMC team id → that team's home spawn for this match. */
    private final Map<String, Location> teamSpawns     = new HashMap<>();
    /** Scheduled start-flow tasks, cancelled if the match aborts early. */
    private final List<BukkitTask>      startFlowTasks = new ArrayList<>();

    public MatchPhase getPhase()        { return phase; }
    /** True only once the match is live — gates every weapon, gadget and the hit handler. */
    public boolean    isCombatEnabled() { return phase == MatchPhase.RUNNING && getState().isRunning(); }

    public QuakeCraftGameManagerV2(QuakeCraftPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    // ── INITIALIZING ────────────────────────────────────────────────────────
    @Override
    protected void onPrepare() {
        phase = MatchPhase.INITIALIZING;
        players.clear();
        lastKilledBy.clear();
        lastKilledAt.clear();
        teamSpawns.clear();
        cancelStartFlow();

        // Players stay frozen for the entire presentation (intro+tutorial+countdown).
        int freezeTicks = Math.max(20, totalStartSeconds() * 20);
        PotionEffectType jumpType = effect("jump_boost");

        assignTeamSpawns();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
            p.teleport(teamSpawnFor(p));
            GamePlayerUtil.resetPlayer(p);
            GamePlayerUtil.freezePlayer(p, freezeTicks);
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, freezeTicks, 128, true, false, false));
        }

        bossBar = Bukkit.createBossBar(ChatColor.RED + "" + ChatColor.BOLD + "QuakeCraft",
                BarColor.RED, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
    }

    @Override
    protected void onCountdownStart() {
        // The QuakeCraft presentation (intro/tutorial/countdown) is driven from
        // onGameStart instead, so the generic base countdown stays out of the way.
    }

    // ── Start-flow entry point (base lifecycle has reached ACTIVE) ────────────
    @Override
    protected void onGameStart() {
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 600);
        arenaWorld = plugin.getArenaManager().getArenaWorld();
        if (arenaWorld != null) {
            arenaWorldPreviousPvp = arenaWorld.getPVP();
            arenaWorld.setPVP(true);
        }
        runStartFlow();
    }

    // ── RUNNING — combat goes live ────────────────────────────────────────────
    private void beginRunning() {
        phase = MatchPhase.RUNNING;

        PotionEffectType jumpType = effect("jump_boost");
        for (UUID uuid : players.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            GamePlayerUtil.unfreezePlayer(p);
            if (jumpType != null) p.removePotionEffect(jumpType);
            giveBaseLoadout(p);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Veel succes en plezier!", 0, 40, 10);
        }
        nl.kmc.quake.util.Sfx.playGlobal(plugin, "match.start", Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);

        if (bossBar != null) { bossBar.setColor(BarColor.GREEN); }
        updateBossBar();

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();
            if (remainingSeconds <= 0) { end(); return; }
            if (checkKillTargetReached()) end();
        }, 20L, 20L);

        if (plugin.getConfig().getBoolean("powerup-spawning.enabled", true)) {
            plugin.getPowerupSpawner().start();
        }
        plugin.getMineManager().start();
        plugin.getDecoyManager().start();
    }

    // ── Per-game sidebar scoreboard ───────────────────────────────────────────

    @Override
    protected String getScoreboardTitle() { return "§c§lQUAKECRAFT"; }

    @Override
    protected List<String> getScoreboardLines(Player viewer) {
        java.util.UUID id = viewer.getUniqueId();
        List<String> l = new ArrayList<>();
        int killTarget = plugin.getConfig().getInt("game.kill-target", 25);

        if (phase != MatchPhase.RUNNING) {
            l.add(api.tr(id, "sb.quake.starting"));
            l.add(api.tr(id, "sb.quake.first-to", killTarget));
            return l;
        }

        l.add(api.tr(id, "sb.quake.time", String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)));
        l.add(api.tr(id, "sb.quake.first-to", killTarget));
        l.add("");
        l.add(api.tr(id, "sb.quake.top"));
        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> b.getKills() - a.getKills());
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            PlayerState ps = ranked.get(i);
            boolean you = ps.getUuid().equals(id);
            l.add(" §7" + (i + 1) + ". " + (you ? "§a" : "§f") + ps.getName() + " §8- §c" + ps.getKills());
        }

        PlayerState me = players.get(id);
        if (me != null) {
            l.add("");
            l.add(api.tr(id, "sb.quake.kd", me.getKills(), me.getDeaths()));
            if (me.getCurrentStreak() > 1) l.add(api.tr(id, "sb.quake.streak", me.getCurrentStreak()));
        }
        return l;
    }

    // ── Start-flow orchestration ──────────────────────────────────────────────

    /** Chains INTRODUCTION → TUTORIAL → COUNTDOWN → RUNNING as scheduled tasks. */
    private void runStartFlow() {
        cancelStartFlow();
        int introSec = flowEnabled("introduction") ? startSeconds("intro-seconds", 5)  : 0;
        int tutSec   = flowEnabled("tutorial")     ? startSeconds("tutorial-seconds", 10) : 0;
        int cdSec    = flowEnabled("countdown")    ? startSeconds("countdown-seconds", 5)  : 0;

        long t = 0;
        if (introSec > 0) { schedule(t, this::showIntroduction); t += introSec * 20L; }

        if (tutSec > 0) {
            final long base = t;
            schedule(t, () -> phase = MatchPhase.TUTORIAL);
            List<String> msgs = tutorialMessages();
            long step = msgs.isEmpty() ? 0 : Math.max(20L, (tutSec * 20L) / msgs.size());
            for (int i = 0; i < msgs.size(); i++) {
                final String m = msgs.get(i);
                schedule(base + i * step, () -> broadcast(m));
            }
            t += tutSec * 20L;
        }

        if (cdSec > 0) {
            final long base = t;
            schedule(t, () -> phase = MatchPhase.COUNTDOWN);
            for (int n = cdSec; n >= 1; n--) {
                final int num = n;
                schedule(base + (cdSec - n) * 20L, () -> showCountdownNumber(num));
            }
            t += cdSec * 20L;
        }

        schedule(t, this::beginRunning);
    }

    private void showIntroduction() {
        phase = MatchPhase.INTRODUCTION;
        String sub = plugin.getConfig().getString("start-sequence.intro-subtitle",
                "Eerste die de kill-limiet haalt wint");
        for (Player p : participants()) {
            p.sendTitle("§c§lQUAKECRAFT", "§e" + sub, 8, 70, 12);
            p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.01);
        }
        nl.kmc.quake.util.Sfx.playGlobal(plugin, "match.intro", Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void showCountdownNumber(int n) {
        for (Player p : participants()) p.sendTitle("§e§l" + n, "§7Maak je klaar...", 0, 22, 4);
        nl.kmc.quake.util.Sfx.playGlobal(plugin, "match.countdown", Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
    }

    private List<String> tutorialMessages() {
        List<String> cfg = plugin.getConfig().getStringList("start-sequence.tutorial-messages");
        if (cfg != null && !cfg.isEmpty())
            return cfg.stream().map(s -> ChatColor.translateAlternateColorCodes('&', s)).toList();
        return List.of(
                "§b§l» §fWelkom bij §cQuakeCraft§f!",
                "§b§l» §fLinksklik met je §erailgun §fom een one-shot straal te vuren.",
                "§b§l» §fPak §dpowerups §fvoor bazooka's, granaten en meer.",
                "§b§l» §fJe kunt je eigen teamgenoten §cniet §fraken — let op je vuur!",
                "§b§l» §fEerste tot §e" + plugin.getConfig().getInt("game.kill-target", 25) + " kills §fwint. Veel succes!");
    }

    private void schedule(long delayTicks, Runnable r) {
        startFlowTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!getState().isRunning()) return; // match aborted mid-presentation
            r.run();
        }, Math.max(0, delayTicks)));
    }

    private void cancelStartFlow() {
        for (BukkitTask task : startFlowTasks) if (task != null) task.cancel();
        startFlowTasks.clear();
    }

    private boolean flowEnabled(String key) { return plugin.getConfig().getBoolean("start-flow." + key, true); }
    private int startSeconds(String key, int def) { return Math.max(0, plugin.getConfig().getInt("start-sequence." + key, def)); }
    private int totalStartSeconds() {
        int t = 0;
        if (flowEnabled("introduction")) t += startSeconds("intro-seconds", 5);
        if (flowEnabled("tutorial"))     t += startSeconds("tutorial-seconds", 10);
        if (flowEnabled("countdown"))    t += startSeconds("countdown-seconds", 5);
        return Math.max(1, t);
    }

    private List<Player> participants() {
        List<Player> out = new ArrayList<>();
        for (UUID id : players.keySet()) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        return out;
    }

    private PotionEffectType effect(String name) {
        try { return RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(name)); }
        catch (Exception e) { return null; }
    }

    // ── Team-grouped spawning ─────────────────────────────────────────────────

    /** Assigns each team present a distinct home spawn from the arena spawn list. */
    private void assignTeamSpawns() {
        List<Location> spawns = new ArrayList<>(plugin.getArenaManager().getSpawns());
        Collections.shuffle(spawns);
        if (spawns.isEmpty()) return;
        LinkedHashSet<String> teamIds = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) teamIds.add(teamIdOf(p));
        int idx = 0;
        for (String id : teamIds) { teamSpawns.put(id, spawns.get(idx % spawns.size())); idx++; }
    }

    private String teamIdOf(Player p) {
        var t = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
        return t != null ? t.getId() : "solo:" + p.getUniqueId();
    }

    /** Initial spawn: the team's home spawn, with a small spread so teammates don't stack. */
    private Location teamSpawnFor(Player p) {
        Location base = teamSpawns.get(teamIdOf(p));
        if (base == null) base = plugin.getArenaManager().randomSpawn();
        return base == null ? p.getLocation() : spreadAround(base);
    }

    /** Respawn: prefer next to a living teammate, else the team's home spawn. */
    private Location teamRespawnFor(Player target) {
        for (UUID id : players.keySet()) {
            if (id.equals(target.getUniqueId())) continue;
            Player other = Bukkit.getPlayer(id);
            if (other == null || other.getGameMode() == GameMode.SPECTATOR) continue;
            if (TeamUtil.areTeammates(plugin, target, other)) return spreadAround(other.getLocation());
        }
        Location base = teamSpawns.get(teamIdOf(target));
        if (base != null) return spreadAround(base);
        return plugin.getArenaManager().randomSpawn();
    }

    private Location spreadAround(Location base) {
        double r = 1.5;
        return base.clone().add((Math.random() * 2 - 1) * r, 0, (Math.random() * 2 - 1) * r);
    }

    @Override
    protected void onGameEnd() {
        phase = MatchPhase.ENDING;
        cancelStartFlow();
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
        if (bossBar       != null) { bossBar.removeAll();   bossBar       = null; }

        plugin.getPowerupSpawner().stop();
        plugin.getMineManager().stop();
        plugin.getDecoyManager().stop();

        if (arenaWorld != null) {
            arenaWorld.setPVP(arenaWorldPreviousPvp);
            arenaWorld = null;
        }

        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.getKills() != b.getKills()) return Integer.compare(b.getKills(), a.getKills());
            return Integer.compare(a.getDeaths(), b.getDeaths());
        });

        List<UUID> finishOrder = new ArrayList<>();
        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvpUuid = null; String mvpName = null; int topKills = 0;

        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (ps.getKills() > topKills) { topKills = ps.getKills(); mvpUuid = ps.getUuid(); mvpName = ps.getName(); }
        }

        returnToLobby();
        players.clear();
        phase = MatchPhase.POST_GAME;
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
        PlayerState ps = players.get(player.getUniqueId());
        if (ps != null) { s.extra.put("kills", ps.getKills()); s.extra.put("deaths", ps.getDeaths()); }
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        player.teleport(snapshot.location);
        player.getInventory().setContents(snapshot.inventory);
        player.getInventory().setArmorContents(snapshot.armor);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
        snapshot.effects.forEach(player::addPotionEffect);
        player.sendMessage("§c[QuakeCraft] Status hersteld!");
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "QuakeCraft"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("QuakeCraft arena not ready: " + plugin.getArenaManager().getReadinessReport());
                return r;
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void handleHit(Player shooter, Player target, String weapon) {
        if (!isCombatEnabled()) return;
        if (shooter == null || target == null || shooter.equals(target)) return;
        if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR) return;

        PlayerState shooterState = players.get(shooter.getUniqueId());
        PlayerState targetState  = players.get(target.getUniqueId());
        if (shooterState == null || targetState == null) return;

        // Centralized, global friendly-fire immunity — no damage, no kill credit,
        // no assist credit. Every weapon routes through here (or TeamUtil directly).
        if (TeamUtil.areTeammates(plugin, shooter, target)) return;

        long revengeWindowMs = plugin.getConfig().getLong("points.revenge-window-ms", 10000);
        UUID prevKiller = lastKilledBy.get(shooter.getUniqueId());
        Long prevKilledAt = lastKilledAt.get(shooter.getUniqueId());
        boolean isRevenge = prevKiller != null && prevKiller.equals(target.getUniqueId())
                && prevKilledAt != null && (System.currentTimeMillis() - prevKilledAt) <= revengeWindowMs;
        if (isRevenge) {
            lastKilledBy.remove(shooter.getUniqueId());
            lastKilledAt.remove(shooter.getUniqueId());
        }

        shooterState.addKill();
        targetState.addDeath();
        lastKilledBy.put(target.getUniqueId(), shooter.getUniqueId());
        lastKilledAt.put(target.getUniqueId(), System.currentTimeMillis());

        int perKill = plugin.getConfig().getInt("points.per-kill", 10);
        api.points().givePoints(shooter.getUniqueId(), perKill, PointAward.Reason.KILL, registration.getId());

        if (isRevenge) {
            int revengeBonus = plugin.getConfig().getInt("points.revenge-kill-bonus", 25);
            if (revengeBonus > 0)
                api.points().givePoints(shooter.getUniqueId(), revengeBonus, PointAward.Reason.BONUS, registration.getId());
            broadcast("§d⚡ WRAAK! §7" + shooter.getName() + " §enam wraak op §7" + target.getName() + "§e!");
        }

        Location deathLoc = target.getLocation().add(0, 1, 0);
        target.getWorld().spawnParticle(Particle.EXPLOSION, deathLoc, 1);
        // Per-weapon impact sound + a kill-confirmation ping to the shooter.
        nl.kmc.quake.util.Sfx.play(plugin, deathLoc, weapon + ".impact", Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f);
        nl.kmc.quake.util.Sfx.playTo(plugin, shooter, "kill.confirm", Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2.0f);
        broadcast("§c☠ §7" + target.getName() + " §8← §e" + shooter.getName() + " §8(" + shooterState.getKills() + ")");

        respawnAfterDeath(target);
        checkKillstreak(shooter, shooterState);
        updateBossBar();
    }

    public Map<UUID, PlayerState> getPlayersMap() { return Collections.unmodifiableMap(players); }
    public boolean isPvpAllowed() { return getState().isRunning(); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private boolean checkKillTargetReached() {
        int target = plugin.getConfig().getInt("game.kill-target", 25);
        return players.values().stream().anyMatch(ps -> ps.getKills() >= target);
    }

    private void respawnAfterDeath(Player target) {
        target.setGameMode(GameMode.SPECTATOR);
        target.getInventory().clear();
        target.setHealth(20); target.setFoodLevel(20);
        target.setFireTicks(0); target.setFallDistance(0);

        int delay = plugin.getConfig().getInt("game.respawn-delay-seconds", 1) * 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!getState().isRunning()) return;
            // Respawn near a living teammate (or the team's home spawn) so teams
            // stay grouped instead of being scattered to opposite sides.
            Location spawn = teamRespawnFor(target);
            if (spawn != null) target.teleport(spawn);
            target.setGameMode(GameMode.ADVENTURE);
            target.setHealth(20); target.setFoodLevel(20);
            target.setFireTicks(0); target.setFallDistance(0);
            target.getActivePotionEffects().forEach(e -> target.removePotionEffect(e.getType()));
            giveBaseLoadout(target);
        }, delay);
    }

    private void giveBaseLoadout(Player p) {
        if (plugin.getConfig().getBoolean("game.give-base-railgun", true))
            p.getInventory().setItem(0, WeaponFactory.buildRailgun(plugin));
        if (plugin.getConfig().getBoolean("game.base-speed-boost", true)) {
            PotionEffectType speedType;
            try { speedType = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft("speed")); } catch (Exception e) { speedType = null; }
            if (speedType != null)
                p.addPotionEffect(new PotionEffect(speedType, Integer.MAX_VALUE, 0, true, false, false));
        }
    }

    private void checkKillstreak(Player shooter, PlayerState ps) {
        if (!plugin.getConfig().getBoolean("killstreaks.enabled", true)) return;
        var rewards = plugin.getConfig().getConfigurationSection("killstreaks.rewards");
        if (rewards == null) return;
        String key = String.valueOf(ps.getCurrentStreak());
        if (!rewards.contains(key)) return;
        var section = rewards.getConfigurationSection(key);
        if (section == null) return;
        int bonusPoints = section.getInt("bonus-points", 0);
        if (bonusPoints > 0)
            api.points().givePoints(shooter.getUniqueId(), bonusPoints, PointAward.Reason.BONUS, registration.getId());
        String msg = section.getString("message", "");
        if (!msg.isBlank()) broadcast(msg.replace("{player}", shooter.getName()));
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        PlayerState top = players.values().stream().max(Comparator.comparingInt(PlayerState::getKills)).orElse(null);
        int target = plugin.getConfig().getInt("game.kill-target", 25);
        int topKills = top != null ? top.getKills() : 0;
        String leadName = top != null ? top.getName() : "Nobody";
        int min = remainingSeconds / 60, sec = remainingSeconds % 60;
        bossBar.setTitle(ChatColor.RED + "" + ChatColor.BOLD + "QuakeCraft §8| §e" + leadName
                + " §f" + topKills + "/" + target + " §8| §b" + String.format("%02d:%02d", min, sec));
        bossBar.setProgress(Math.max(0, Math.min(1.0, (double) topKills / target)));
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        players.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) return;
            GamePlayerUtil.resetPlayer(p);
            if (lobby != null) p.teleport(lobby);
        });
    }

}
