package nl.kmc.game.api.arcade;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.*;
import nl.kmc.game.api.arcade.ArcadeLogic.OnceGuard;
import nl.kmc.game.api.arcade.ArcadeLogic.ScoreBook;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Shared skeleton of the six arcade games. It owns everything that is the same for all of them — prepare
 * (teams, teleport, boss bar, intro), the game loop that stops while the tournament is paused, one-time
 * scoring, optional team PvP with respawns, cleanup and the result — so each game only describes its rules.
 *
 * <p>Scoring goes through the normal KMC API ({@code api.points()}); there is no game-specific currency.
 * Every award has a key, so an event can only ever be booked once per run.
 */
public abstract class ArcadeGameManager extends BaseGameManager {

    public enum Combat { NONE, TEAM_PVP }

    /** What a game hands back when it finishes. */
    public record Result(String winner, UUID mvp, String mvpName, List<UUID> order) {}

    /** The period of the game loop in server ticks. */
    protected static final int STEP = 2;

    protected final JavaPlugin gamePlugin;
    protected final ArenaConfig arena;
    protected final ScoreBook book = new ScoreBook();
    protected final OnceGuard once = new OnceGuard();
    protected final List<UUID> players = new ArrayList<>();
    protected final Map<UUID, String> teamOf = new HashMap<>();
    protected final Set<UUID> gone = new HashSet<>();

    protected String runId = "";
    protected volatile boolean live;
    /** Game time in ticks; does not advance while the tournament is paused. */
    protected long ticks;

    private StandardStartFlow startFlow;
    private BossBar bossBar;
    private BukkitTask loop;
    private boolean ownsDryRun;

    // combat state
    private final Map<UUID, Long> deadUntil = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Long> lastAttackTick = new HashMap<>();

    protected ArcadeGameManager(JavaPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats);
        this.gamePlugin = plugin;
        this.arena = arena;
    }

    // ── Hooks for the games ───────────────────────────────────────────────────

    protected abstract String titleColored();
    protected abstract String introSubKey();
    protected abstract List<String> tutorialKeys();
    /** Human-readable problems that stop the game from starting; empty = ready. */
    protected abstract List<String> arenaProblems();
    protected abstract List<Location> spawnPoints();
    protected abstract Location flyoverCenter();
    /** Players are on their spawns and frozen; set up the arena state for this run. */
    protected void prepareGame() { }
    /** The countdown is over: gameplay starts now (ticks == 0). */
    protected abstract void beginPlay();
    /** Called every {@link #STEP} ticks while the game is live and not paused. */
    protected abstract void playTick();
    /** Time is up / the game decided: award the final points and return the result. */
    protected abstract Result finishGame();
    /** Remove temporary entities, blocks and tasks; always called, also after an abort. */
    protected void cleanupGame() { }
    protected abstract List<String> sidebar(Player viewer);

    protected Combat combat() { return Combat.NONE; }
    protected int respawnSeconds() { return gamePlugin.getConfig().getInt("combat.respawn-seconds", 5); }
    protected int protectionSeconds() { return gamePlugin.getConfig().getInt("combat.spawn-protection-seconds", 3); }
    protected boolean friendlyFire() { return gamePlugin.getConfig().getBoolean("combat.friendly-fire", false); }
    protected Location respawnLocation(Player p) {
        List<Location> sp = spawnPoints();
        if (sp.isEmpty()) return p.getLocation();
        return sp.get(Math.floorMod(teamIndex(teamId(p.getUniqueId())), sp.size()));
    }
    protected void onRespawn(Player p) { }
    /** A player went down (lethal damage while team PvP is on). {@code killer} may be null. */
    protected void onPlayerDown(Player victim, UUID killer) { }
    /** Return true to handle a hit yourself (the base class then ignores it). */
    protected boolean onPlayerHit(Player attacker, Player victim, EntityDamageByEntityEvent e) { return false; }
    protected void onPlayerLeft(UUID id) { }
    protected boolean allowBlockChange(Player p, org.bukkit.block.Block b) { return false; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected final void onPrepare() {
        gamePlugin.reloadConfig();
        runId = UUID.randomUUID().toString();
        book.clear();
        once.clear();
        players.clear();
        teamOf.clear();
        gone.clear();
        deadUntil.clear();
        protectedUntil.clear();
        lastAttacker.clear();
        lastAttackTick.clear();
        live = false;
        ticks = 0;

        List<String> problems = arenaProblems();
        if (!problems.isEmpty()) {
            log.severe("Arena not ready: " + problems);
            end();
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Location> spawns = spawnPoints();
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            players.add(p.getUniqueId());
            teamOf.put(p.getUniqueId(), resolveTeam(p));
        }
        // Players of the same team start next to each other: order by team, then by name.
        players.sort(Comparator.comparing((UUID id) -> teamOf.get(id)).thenComparing(UUID::compareTo));
        for (int i = 0; i < players.size(); i++) {
            Player p = Bukkit.getPlayer(players.get(i));
            if (p == null) continue;
            GamePlayerUtil.resetPlayer(p);
            p.setGameMode(GameMode.ADVENTURE);
            Location s = spawns.isEmpty() ? p.getLocation() : spawnFor(i, spawns);
            GamePlayerUtil.safeTeleport(p, s);
        }

        bossBar = Bukkit.createBossBar(titleColored(), BarColor.WHITE, BarStyle.SOLID);
        online.forEach(bossBar::addPlayer);

        prepareGame();

        List<Player> parts = new ArrayList<>(online);
        startFlow = new StandardStartFlow(gamePlugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return titleColored(); }
                    @Override public String introSubtitle() { return introSubKey(); }
                    @Override public List<String> defaultTutorialMessages() { return tutorialKeys(); }
                    @Override public Location flyoverCenter() { return ArcadeGameManager.this.flyoverCenter(); }
                    @Override public void onFinished() { beginLive(); }
                });
        startFlow.prepareAndFreeze();
    }

    private Location spawnFor(int index, List<Location> spawns) {
        return spawns.get(index % spawns.size());
    }

    @Override protected void onCountdownStart() { /* the start flow runs from onGameStart */ }

    @Override
    protected void onGameStart() {
        if (startFlow != null) startFlow.start();
    }

    private void beginLive() {
        if (!getState().isRunning()) return;
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) GamePlayerUtil.unfreezePlayer(p);
        }
        live = true;
        ticks = 0;
        try { beginPlay(); }
        catch (Throwable t) { log.severe("beginPlay failed: " + t); t.printStackTrace(); end(); return; }
        loop = Bukkit.getScheduler().runTaskTimer(gamePlugin, this::tickLoop, STEP, STEP);
    }

    private void tickLoop() {
        if (!live || !getState().isRunning()) return;
        if (paused()) return;                      // the tournament host paused: nothing advances
        ticks += STEP;
        try {
            reviveDead();
            playTick();
        } catch (Throwable t) {
            log.severe("playTick failed: " + t);
            t.printStackTrace();
        }
    }

    /** True while the tournament host has paused the event. */
    protected boolean paused() {
        try {
            var am = nl.kmc.kmccore.KMCCore.getInstance().getAutomationManager();
            return am != null && am.getState() == nl.kmc.kmccore.managers.AutomationManager.State.PAUSED;
        } catch (Throwable t) { return false; }
    }

    @Override
    protected final void onGameEnd() {
        boolean wasLive = live;
        live = false;
        if (loop != null) { loop.cancel(); loop = null; }
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        Result r = null;
        try { r = wasLive ? finishGame() : null; }
        catch (Throwable t) { log.severe("finishGame failed: " + t); t.printStackTrace(); }
        try { cleanupGame(); }
        catch (Throwable t) { log.severe("cleanupGame failed: " + t); t.printStackTrace(); }

        List<UUID> order = r != null ? r.order() : new ArrayList<>(players);
        for (int i = 0; i < order.size(); i++) {
            UUID id = order.get(i);
            try {
                api.games().recordGameParticipation(id, nameOf(id), registration.getId(), i == 0 && r != null);
            } catch (Throwable ignored) { }
        }

        Location lobby = nl.kmc.kmccore.KMCCore.getInstance().getArenaManager().getLobby();
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            GamePlayerUtil.resetPlayer(p);
            GamePlayerUtil.teleportToLobby(p, lobby);
        }
        players.clear();
        deadUntil.clear();
        protectedUntil.clear();

        if (r != null) fireResult(r.winner(), r.mvp(), r.mvpName(), r.order());
        else fireResult("-", null, null, order);
        if (ownsDryRun) { nl.kmc.core.DryRun.end(); ownsDryRun = false; }
    }

    /** Starts the game as a dry run: everything plays, nothing is booked. */
    public boolean startDry() {
        nl.kmc.core.DryRun.begin(registration.getId());
        ownsDryRun = true;
        if (!start()) {
            nl.kmc.core.DryRun.end();
            ownsDryRun = false;
            return false;
        }
        return true;
    }

    // ── Teams / players ───────────────────────────────────────────────────────

    private String resolveTeam(Player p) {
        try {
            var t = api.teams().getTeamByPlayer(p.getUniqueId());
            if (t.isPresent()) return t.get().getId();
        } catch (Throwable ignored) { }
        return "solo_" + p.getUniqueId().toString().substring(0, 8);
    }

    protected final String teamId(UUID id) { return teamOf.getOrDefault(id, "solo_?"); }

    /** Team ids that have at least one participant, in a stable order. */
    protected final List<String> activeTeams() {
        return new ArrayList<>(new TreeSet<>(teamOf.values()));
    }

    protected final int teamIndex(String teamId) { return Math.max(0, activeTeams().indexOf(teamId)); }

    protected final KMCTeam teamObj(String id) {
        try { return api.teams().getTeam(id).orElse(null); } catch (Throwable t) { return null; }
    }

    protected final String teamName(String id) {
        KMCTeam t = teamObj(id);
        return t != null ? t.getColor() + t.getDisplayName() : "§f" + id;
    }

    protected final ChatColor teamColor(String id) {
        KMCTeam t = teamObj(id);
        return t != null ? t.getColor() : ChatColor.WHITE;
    }

    protected final List<UUID> membersOf(String team) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : players) if (team.equals(teamOf.get(id))) out.add(id);
        return out;
    }

    protected final String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }

    protected final boolean isParticipant(UUID id) { return players.contains(id) && !gone.contains(id); }

    /** A participant who is online, alive and in the game (not waiting to respawn, not a spectator). */
    protected final boolean isActive(UUID id) {
        if (!isParticipant(id) || deadUntil.containsKey(id)) return false;
        Player p = Bukkit.getPlayer(id);
        return p != null && p.isOnline() && p.getGameMode() != GameMode.SPECTATOR && !p.isDead();
    }

    protected final List<Player> activePlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : players) if (isActive(id)) out.add(Bukkit.getPlayer(id));
        return out;
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    /** Gives a player points once per {@code key}. The player's team gets them through the normal KMC rules. */
    protected final boolean award(String key, UUID player, int points, PointAward.Reason reason, String category) {
        if (points == 0 || player == null) return false;
        String k = key == null ? null : runId + ":" + key;
        String team = teamId(player);
        if (!book.add(k, player, team, category, points)) return false;
        api.points().givePoints(player, points, reason, registration.getId());
        return true;
    }

    /** Gives a team points once per {@code key} (objective points that belong to nobody in particular). */
    protected final boolean awardTeam(String key, String team, int points, PointAward.Reason reason, String category) {
        if (points == 0 || team == null || team.startsWith("solo_")) {
            if (team != null && team.startsWith("solo_") && points != 0) {
                // a player without a team: book it for him so the HUD and the result stay right
                List<UUID> m = membersOf(team);
                if (!m.isEmpty()) return award(key, m.get(0), points, reason, category);
            }
            return false;
        }
        String k = key == null ? null : runId + ":" + key;
        if (!book.add(k, null, team, category, points)) return false;
        api.points().giveTeamPoints(team, points, reason, registration.getId());
        return true;
    }

    /** Standard end-of-game team placement points (skipped for solo players). */
    protected final void awardTeamPlacements(List<String> rankedTeams) {
        if (!gamePlugin.getConfig().getBoolean("points.team-placement", true)) return;
        int place = 1;
        for (String t : rankedTeams) {
            if (!t.startsWith("solo_")) api.points().awardTeamPlacement(t, place, registration.getId());
            place++;
        }
    }

    protected final void awardPlayerPlacements(List<UUID> ranked) {
        if (!gamePlugin.getConfig().getBoolean("points.placement-curve", false)) return;
        for (int i = 0; i < ranked.size(); i++)
            api.points().awardPlayerPlacement(ranked.get(i), i + 1, ranked.size(), registration.getId());
    }

    // ── Boss bar / messages ───────────────────────────────────────────────────

    protected final void bar(String title, double progress, BarColor color) {
        if (bossBar == null) return;
        bossBar.setTitle(title);
        bossBar.setColor(color);
        bossBar.setProgress(Math.max(0, Math.min(1, progress)));
    }

    protected final void actionBar(Player p, String text) {
        p.sendActionBar(net.kyori.adventure.text.Component.text(text));
    }

    protected final String tr(UUID id, String key, Object... args) { return api.tr(id, key, args); }

    // ── Kit ───────────────────────────────────────────────────────────────────

    /** Gives the configured kit ({@code kit.items}: "MATERIAL:amount") and, optionally, team-coloured armour. */
    protected final void giveKit(Player p) {
        p.getInventory().clear();
        var cfg = gamePlugin.getConfig();
        for (String s : cfg.getStringList("kit.items")) {
            String[] part = s.split(":");
            Material m = Material.matchMaterial(part[0].trim());
            if (m == null) continue;
            int n = 1;
            try { if (part.length > 1) n = Math.max(1, Integer.parseInt(part[1].trim())); } catch (NumberFormatException ignored) { }
            ItemStack it = new ItemStack(m, n);
            String name = m.name();
            if (name.endsWith("_HELMET")) p.getInventory().setHelmet(it);
            else if (name.endsWith("_CHESTPLATE")) p.getInventory().setChestplate(it);
            else if (name.endsWith("_LEGGINGS")) p.getInventory().setLeggings(it);
            else if (name.endsWith("_BOOTS")) p.getInventory().setBoots(it);
            else p.getInventory().addItem(it);
        }
        if (cfg.getBoolean("kit.team-armor", true)) {
            Color c = colorOf(teamColor(teamId(p.getUniqueId())));
            p.getInventory().setChestplate(dyed(Material.LEATHER_CHESTPLATE, c));
            p.getInventory().setHelmet(dyed(Material.LEATHER_HELMET, c));
        }
    }

    private static ItemStack dyed(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(c);
        meta.setUnbreakable(true);
        it.setItemMeta(meta);
        return it;
    }

    public static Color colorOf(ChatColor c) {
        if (c == null) return Color.WHITE;
        return switch (c) {
            case RED, DARK_RED -> Color.RED;
            case GOLD -> Color.ORANGE;
            case YELLOW -> Color.YELLOW;
            case GREEN -> Color.LIME;
            case DARK_GREEN -> Color.GREEN;
            case AQUA -> Color.AQUA;
            case DARK_AQUA -> Color.TEAL;
            case BLUE, DARK_BLUE -> Color.BLUE;
            case LIGHT_PURPLE -> Color.FUCHSIA;
            case DARK_PURPLE -> Color.PURPLE;
            case GRAY -> Color.SILVER;
            case DARK_GRAY -> Color.GRAY;
            case BLACK -> Color.BLACK;
            default -> Color.WHITE;
        };
    }

    // ── Combat, respawn ───────────────────────────────────────────────────────

    protected final boolean isProtected(UUID id) {
        return protectedUntil.getOrDefault(id, 0L) > ticks;
    }

    protected final void protect(UUID id, int seconds) {
        protectedUntil.put(id, ticks + seconds * 20L);
    }

    /** The player who hit {@code victim} last within {@code windowSeconds}, or null. */
    protected final UUID recentAttacker(UUID victim, int windowSeconds) {
        UUID a = lastAttacker.get(victim);
        if (a == null) return null;
        return ticks - lastAttackTick.getOrDefault(victim, -100000L) <= windowSeconds * 20L ? a : null;
    }

    protected final void noteAttack(UUID attacker, UUID victim) {
        lastAttacker.put(victim, attacker);
        lastAttackTick.put(victim, ticks);
    }

    /** Puts a player out of the fight until the respawn delay is over. */
    protected final void down(Player p, UUID killer) {
        UUID id = p.getUniqueId();
        if (deadUntil.containsKey(id) || !isParticipant(id)) return;
        try { onPlayerDown(p, killer); } catch (Throwable t) { log.warning("onPlayerDown failed: " + t); }
        p.getInventory().clear();
        p.setHealth(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setGameMode(GameMode.SPECTATOR);
        deadUntil.put(id, ticks + respawnSeconds() * 20L);
        p.sendTitle(api.tr(id, "arcade.down.title"), api.tr(id, "arcade.down.sub", respawnSeconds()), 0, 30, 10);
    }

    private void reviveDead() {
        if (deadUntil.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Long>> it = deadUntil.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (ticks < en.getValue()) continue;
            it.remove();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !isParticipant(en.getKey())) continue;
            respawnNow(p);
        }
    }

    /** Brings a player back (also used by games that respawn without the PvP flow). */
    protected final void respawnNow(Player p) {
        deadUntil.remove(p.getUniqueId());
        p.setGameMode(GameMode.ADVENTURE);
        GamePlayerUtil.safeTeleport(p, respawnLocation(p));
        p.setHealth(20);
        p.setFoodLevel(20);
        protect(p.getUniqueId(), protectionSeconds());
        if (combat() == Combat.TEAM_PVP) giveKit(p);
        try { onRespawn(p); } catch (Throwable t) { log.warning("onRespawn failed: " + t); }
    }

    // ── Event handlers (public so Bukkit finds them on the superclass) ────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void arcadeDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !getState().isRunning() || !isParticipant(victim.getUniqueId())) return;
        if (victim.getGameMode() == GameMode.SPECTATOR) { e.setCancelled(true); return; }

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player pl) attacker = pl;
            else if (byEntity.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) attacker = pl;
            if (attacker != null && isParticipant(attacker.getUniqueId()) && live) {
                if (onPlayerHit(attacker, victim, byEntity)) return;
            }
        }
        if (combat() == Combat.NONE || !live) { e.setCancelled(true); return; }
        if (isProtected(victim.getUniqueId())) { e.setCancelled(true); return; }
        if (attacker != null) {
            if (attacker.equals(victim)) { e.setCancelled(true); return; }
            if (!isParticipant(attacker.getUniqueId())) { e.setCancelled(true); return; }
            if (!friendlyFire() && teamId(attacker.getUniqueId()).equals(teamId(victim.getUniqueId()))) {
                e.setCancelled(true);
                return;
            }
            if (isProtected(attacker.getUniqueId())) protectedUntil.remove(attacker.getUniqueId());
            noteAttack(attacker.getUniqueId(), victim.getUniqueId());
        }
        if (victim.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            UUID killer = attacker != null ? attacker.getUniqueId() : recentAttacker(victim.getUniqueId(), 8);
            down(victim, killer);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void arcadeFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && getState().isRunning() && isParticipant(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void arcadeDrop(PlayerDropItemEvent e) {
        if (getState().isRunning() && isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void arcadeSwap(PlayerSwapHandItemsEvent e) {
        if (getState().isRunning() && isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void arcadeBreak(BlockBreakEvent e) {
        if (getState().isRunning() && isParticipant(e.getPlayer().getUniqueId())
                && !allowBlockChange(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void arcadePlace(BlockPlaceEvent e) {
        if (getState().isRunning() && isParticipant(e.getPlayer().getUniqueId())
                && !allowBlockChange(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void arcadeQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!getState().isRunning() || !players.contains(id) || gone.contains(id)) return;
        gone.add(id);
        deadUntil.remove(id);
        try { onPlayerLeft(id); } catch (Throwable t) { log.warning("onPlayerLeft failed: " + t); }
    }

    // ── Base-class plumbing ───────────────────────────────────────────────────

    @Override protected final String getScoreboardTitle() { return titleColored(); }

    @Override
    protected final List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning() || players.isEmpty()) return defaultScoreboardLines(viewer);
        return sidebar(viewer);
    }

    @Override protected PlayerGameState capturePlayerState(Player player) { return null; }
    @Override protected void restorePlayerState(Player player, PlayerGameState snapshot) { }

    @Override
    protected final ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return registration.getDisplayName(); }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                for (String p : arenaProblems()) r.addError(registration.getDisplayName() + ": " + p);
                return r;
            }
        };
    }

    protected static String mmss(long seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
