package nl.kmc.blockparty.managers;

import nl.kmc.blockparty.BlockPartyPlugin;
import nl.kmc.blockparty.events.*;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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

    /** Round 1 always uses exactly this fixed palette — every later round uses all 16. */
    private static final List<Material> ROUND1_PALETTE = List.of(
            Material.YELLOW_CONCRETE, Material.WHITE_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE, Material.BLACK_CONCRETE);

    /** Tags the round's colour-display item so it can be told apart from anything else in a slot. */
    private final NamespacedKey colourItemKey;

    private final BlockPartyPlugin plugin;
    private final ArenaManager     arena;
    private final FloorGenerator   floor;
    private final Random           random = new Random();

    private final Map<UUID, BPPlayer> players         = new LinkedHashMap<>();
    private final List<UUID>          eliminationOrder = new ArrayList<>(); // first eliminated first

    private int        round;
    private Material   lastTargetColour;                     // this round's target, remembered so next round can avoid repeating it
    private Set<Material> keepColours = new HashSet<>();   // colour(s) that survive this round
    private Material   displayColour;                       // shown to players (may be fake)
    private ChaosEvent chaos;                               // active chaos event, or null
    private int        roundSeconds;
    private int        secondsLeft;
    private BukkitTask roundTask;
    private BossBar    bossBar;
    private StandardStartFlow startFlow;

    public BlockPartyGameManagerV2(BlockPartyPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.arena  = plugin.getArenaManager();
        this.floor  = plugin.floorGen();
        this.colourItemKey = new NamespacedKey(plugin, "blockparty_colour_item");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        players.clear();
        eliminationOrder.clear();
        round = 0;

        // Paint round 1's floor immediately so players have solid ground during
        // the countdown/grace period — without this the floor is still empty
        // from the previous game and players fall straight through into the
        // void. startRound() repaints it again for real once round 1 begins.
        paintRound1Floor();

        // Only players currently on an active KMC team take part — matches
        // every other game's participant rule, so a teamless spectator/admin
        // standing around doesn't accidentally get swept into the match.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (api.teams().getTeamByPlayer(p.getUniqueId()).isEmpty()) continue;
            players.put(p.getUniqueId(), new BPPlayer(p.getUniqueId(), p.getName()));
            GamePlayerUtil.resetPlayer(p);
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(floor.randomFloorLocation());
        }

        bossBar = Bukkit.createBossBar("§d§lBLOCK PARTY", BarColor.PINK, BarStyle.SEGMENTED_10);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        List<Player> parts = players.keySet().stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        Location center = arena.getPos1() != null && arena.getPos2() != null
                ? new Location(arena.getWorld(),
                        (arena.minX() + arena.maxX()) / 2.0, arena.floorY(),
                        (arena.minZ() + arena.maxZ()) / 2.0)
                : null;
        double radius = center != null
                ? Math.max(arena.maxX() - arena.minX(), arena.maxZ() - arena.minZ()) / 2.0 : 0;

        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§d§lBLOCK PARTY"; }
                    @Override public String introSubtitle() { return "@bp.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@bp.tut.1", "@bp.tut.2", "@bp.tut.3");
                    }
                    @Override public Location flyoverCenter() { return center; }
                    @Override public void onFinished() {
                        Bukkit.getPluginManager().callEvent(new BlockPartyGameStartEvent(new ArrayList<>(players.keySet())));
                        beginFirstRoundCountdown();
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
        if (roundTask != null) { roundTask.cancel(); roundTask = null; }
        if (bossBar  != null) { bossBar.removeAll(); bossBar = null; }
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }
        // Reset to a plain/preset floor rather than clearing to air — the
        // arena should look normal and complete between games, not like a void.
        floor.resetToIdle();

        // Whoever is still alive is the winner; append to the elimination order last.
        List<UUID> stillAlive = players.values().stream().filter(BPPlayer::isAlive).map(BPPlayer::getUuid).toList();
        for (UUID u : stillAlive) if (!eliminationOrder.contains(u)) eliminationOrder.add(u);

        // Final placement: last eliminated = best place.
        List<UUID> placement = new ArrayList<>(eliminationOrder);
        Collections.reverse(placement);

        BPPlayer winner = placement.isEmpty() ? null : players.get(placement.get(0));
        awardAndRecord(placement, winner);

        Bukkit.getPluginManager().callEvent(
                new BlockPartyGameEndEvent(winner != null ? winner.getUuid() : null, placement));

        broadcastFinalStandings(placement, winner);

        UUID   mvpUuid = pickMvp(winner);
        String mvpName = mvpUuid != null && players.containsKey(mvpUuid) ? players.get(mvpUuid).getName() : null;

        returnToLobby();
        String winnerDesc = winner != null ? winner.getName() + " wint Block Party!" : "Geen winnaar";
        fireResult(winnerDesc, mvpUuid, mvpName, placement);
        players.clear();
    }

    // ── Round flow ────────────────────────────────────────────────────────────

    /** Gives everyone a few seconds on the floor to orient themselves before the first target colour appears. */
    private void beginFirstRoundCountdown() {
        int secs = Math.max(0, plugin.getConfig().getInt("block-party.game.first-round-delay-seconds", 5));
        if (secs == 0) { startRound(); return; }
        final int[] left = {secs};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!getState().isRunning()) { task.cancel(); return; }
            if (left[0] <= 0) { task.cancel(); startRound(); return; }
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§d§lBLOCK PARTY", api.tr(p.getUniqueId(), "bp.first-round", left[0]), 0, 25, 5);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
            }
            left[0]--;
        }, 0L, 20L);
    }

    private void startRound() {
        if (!getState().isRunning()) return;
        long aliveCount = alivePlayers().size();
        if (aliveCount <= 1) { end(); return; }

        round++;
        int phase = phaseFor(round);
        clearChaos();

        // Round 1 uses the captured preset (if any) or the fixed 4-colour
        // fallback; every round after that is fully random across all 16.
        FloorGenerator.Result result = (round == 1)
                ? paintRound1Floor()
                : floor.generate(Colors.ALL.size(), clusterFor(phase));

        // Decide on a chaos event (may tweak timer/colours/display below).
        chaos = rollChaos(aliveCount);

        // Target needs room for everyone alive AND at least minimum-target-blocks
        // (whichever is bigger), and tries to avoid repeating last round's colour.
        int minTarget = plugin.getConfig().getInt("block-party.game.minimum-target-blocks", 8);
        int needed    = (int) Math.max(aliveCount, minTarget);
        Material target = pickTargetColour(result, needed);

        if (chaos == ChaosEvent.MIRROR) {
            // Inverted round: the announced colour is the ONE to avoid — every
            // other colour on the floor survives.
            keepColours = new HashSet<>(result.palette());
            keepColours.remove(target);
            if (keepColours.isEmpty()) keepColours.add(target); // degenerate single-colour floor
        } else {
            keepColours = new HashSet<>(Set.of(target));
        }

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
        lastTargetColour = target;

        players.values().stream().filter(BPPlayer::isAlive).forEach(BPPlayer::beginRound);
        applyChaosEffects();
        giveColourItem();
        announceRound(target);

        Bukkit.getPluginManager().callEvent(new BlockPartyRoundStartEvent(round, target, displayColour, aliveCount));

        roundTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Picks a target, preferring to avoid repeating last round's colour — falls back to allowing it if no alternative fits. */
    private Material pickTargetColour(FloorGenerator.Result result, int needed) {
        boolean avoidRepeat = plugin.getConfig().getBoolean("block-party.game.avoid-repeat-colour", true);
        if (avoidRepeat && lastTargetColour != null) {
            Material avoided = pickSafeColour(result, needed, lastTargetColour);
            if (avoided != null) return avoided;
        }
        return pickSafeColour(result, needed);
    }

    /** Gives every alive player the round's colour block — pure visual reference, replaces whatever was in that slot. */
    /** Colour name in the viewer's language (nl: ROOD, en: RED). */
    private String lbl(Player p, Material concrete) {
        boolean en = "en".equals(plugin.getKmcCore().getLanguageManager().getLanguage(p.getUniqueId()));
        return Colors.label(concrete, en);
    }

    private void giveColourItem() {
        int slot = plugin.getConfig().getInt("block-party.inventory-slot", 4);
        ItemStack item = new ItemStack(displayColour, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(colourItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        for (Player p : alivePlayers()) {
            ItemStack mine = item.clone();
            ItemMeta mm = mine.getItemMeta();
            if (mm != null) { mm.setDisplayName(lbl(p, displayColour)); mine.setItemMeta(mm); }
            p.getInventory().setItem(slot, mine);
        }
    }

    /** True only for the round's own colour-display item (tagged), never a player's own matching concrete. */
    private boolean isColourItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(colourItemKey, PersistentDataType.BYTE);
    }

    // ── Colour item protection — cancel every way it could be moved/used ──────

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!getState().isRunning()) return;
        if (!(e.getWhoClicked() instanceof Player p) || !players.containsKey(p.getUniqueId())) return;
        if (isColourItem(e.getCurrentItem()) || isColourItem(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent e) {
        if (!getState().isRunning() || !players.containsKey(e.getPlayer().getUniqueId())) return;
        if (isColourItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceBlock(BlockPlaceEvent e) {
        if (!getState().isRunning() || !players.containsKey(e.getPlayer().getUniqueId())) return;
        if (isColourItem(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (!getState().isRunning() || !players.containsKey(e.getPlayer().getUniqueId())) return;
        if (isColourItem(e.getMainHandItem()) || isColourItem(e.getOffHandItem())) e.setCancelled(true);
    }

    /** One-second tick: update displays, track clutch positions, fire elimination at zero. */
    private void tick() {
        if (!getState().isRunning()) return;

        // Track who is standing on a surviving colour (for clutch detection).
        for (Player p : alivePlayers()) {
            BPPlayer bp = players.get(p.getUniqueId());
            if (bp == null) continue;
            boolean onTarget = standsOnKeep(p);
            // Stepping onto a correct colour in the last second = a clutch save.
            if (onTarget && !bp.wasOnTarget() && secondsLeft <= 1) bp.markClutch();
            bp.setWasOnTarget(onTarget);
        }

        updateDisplays();

        if (secondsLeft <= 0) { eliminate(); return; }

        // Big on-screen countdown number, same cadence as the tick itself.
        for (Player p : alivePlayers()) {
            p.sendTitle("§e§l" + secondsLeft, "", 0, 18, 2);
            if (secondsLeft == 1) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f); // warning
        }
        if (secondsLeft <= 3) {
            for (Player p : alivePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.6f);
        }
        secondsLeft--;
    }

    private void eliminate() {
        if (roundTask != null) { roundTask.cancel(); roundTask = null; }

        for (Player p : alivePlayers()) p.sendTitle(api.tr(p.getUniqueId(), "bp.vanish"), "", 0, 15, 5);

        List<BPPlayer> survivors = new ArrayList<>();
        List<BPPlayer> dropped   = new ArrayList<>();
        for (Player p : alivePlayers()) {
            BPPlayer bp = players.get(p.getUniqueId());
            if (bp == null) continue;
            if (standsOnKeep(p)) survivors.add(bp);
            else                                     dropped.add(bp);
        }

        // Remove every non-surviving colour — droppers fall into the void.
        floor.removeAllExcept(keepColours);

        boolean chaosActive = chaos != null;
        for (BPPlayer bp : survivors) {
            bp.surviveRound(round, chaosActive);
            Bukkit.getPluginManager().callEvent(
                    new BlockPartyPlayerSurviveEvent(bp.getUuid(), round, bp.isClutchThisRound()));
            Player p = Bukkit.getPlayer(bp.getUuid());
            if (p == null) continue;
            if (bp.isClutchThisRound()) {
                api.games().recordHighlight(bp.getUuid(), "clutch", 1, registration.getId());
                p.sendTitle(api.tr(p.getUniqueId(), "bp.clutch.title"), api.tr(p.getUniqueId(), "bp.clutch.sub"), 3, 30, 8);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
                grant(p, "blockparty_clutch_king");
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }
        }
        for (BPPlayer bp : dropped) eliminatePlayer(bp);

        Bukkit.getPluginManager().callEvent(new BlockPartyRoundEndEvent(round,
                survivors.stream().map(BPPlayer::getUuid).toList(),
                dropped.stream().map(BPPlayer::getUuid).toList()));

        long alive = alivePlayers().size();
        if (!dropped.isEmpty())
            tellKey("bp.dropped", dropped.size(), alive);

        if (alive <= 1) {
            Bukkit.getScheduler().runTaskLater(plugin, this::end, 40L);
            return;
        }
        long delay = Math.max(1, plugin.getConfig().getInt("block-party.game.regen-delay-seconds", 2)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, this::startRound, delay);
    }

    private void eliminatePlayer(BPPlayer bp) {
        bp.eliminate(round);
        eliminationOrder.add(bp.getUuid());
        statsService.recordPlacement(bp.getUuid(), alivePlayers().size() + 1);
        {
            String outName = Bukkit.getOfflinePlayer(bp.getUuid()).getName();
            String tag = nl.kmc.game.api.GamePlayerUtil.placeTag(alivePlayers().size() + 1);
            for (Player v : Bukkit.getOnlinePlayers())
                v.sendMessage("§c☠ §7" + (outName != null ? outName : "?") + tag);
        }
        statsService.recordSurvivalSeconds(bp.getUuid(), bp.getRoundsSurvived());
        Bukkit.getPluginManager().callEvent(
                new BlockPartyPlayerEliminateEvent(bp.getUuid(), round, bp.getRoundsSurvived()));

        Player p = Bukkit.getPlayer(bp.getUuid());
        if (p == null) return;
        p.sendTitle(api.tr(p.getUniqueId(), "bp.out.title"),
                api.tr(p.getUniqueId(), "bp.out.sub", round, bp.getRoundsSurvived()), 5, 45, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_DEATH, 1f, 0.8f);
        int slot = plugin.getConfig().getInt("block-party.inventory-slot", 4);
        p.getInventory().setItem(slot, null); // remove the colour-display item on elimination
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.setGameMode(GameMode.SPECTATOR);
            if (arena.getSpectator() != null) p.teleport(arena.getSpectator());
        }, 15L);
    }

    /** Round 1's floor: the admin-captured preset if one is set, else the fixed 4-colour default. */
    private FloorGenerator.Result paintRound1Floor() {
        return arena.hasPresetFloor()
                ? floor.generatePreset(arena.getPresetFloor())
                : floor.generate(ROUND1_PALETTE, clusterFor(1));
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
        // The first rounds always use the relaxed phase timer, however few players are alive — otherwise a
        // small lobby would be in "endgame" speed from round 1 (alive < 8).
        boolean early = round <= cfg.getInt("block-party.game.relaxed-rounds", 4);
        if (!early) {
            if (alive < cfg.getInt("block-party.game.final-showdown-threshold", 4)) return cfg.getInt("block-party.game.timer.final-showdown", 3);
            if (alive < cfg.getInt("block-party.game.endgame-threshold", 8))        return cfg.getInt("block-party.game.timer.endgame", 4);
        }
        return cfg.getInt("block-party.game.timer.phase" + phase, Math.max(4, 13 - phase * 2));
    }

    private int clusterFor(int phase) {
        return plugin.getConfig().getInt("block-party.game.cluster-size.phase" + phase, Math.max(6, 56 - phase * 10));
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
        if (!cfg.getBoolean("block-party.chaos.enabled", true)) return null;
        if (round < cfg.getInt("block-party.chaos.start-round", 5)) return null;
        double chance = alive < cfg.getInt("block-party.game.endgame-threshold", 8)
                ? cfg.getDouble("block-party.chaos.endgame-chance", 0.45)
                : cfg.getDouble("block-party.chaos.base-chance", 0.22);
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
        if (chaos != null) {
            for (Player p : alivePlayers()) {
                String ck = "bp.chaos." + chaos.name().toLowerCase();
                p.sendTitle(api.tr(p.getUniqueId(), ck + ".title"), api.tr(p.getUniqueId(), ck + ".sub"), 3, 35, 8);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
            }
        }
        boolean mirror = chaos == ChaosEvent.MIRROR;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            String goal = api.tr(id, mirror ? "bp.avoid" : "bp.goal");
            p.sendMessage("§8§m                ");
            p.sendMessage(api.tr(id, "bp.round-line", round, goal,
                    colorBlind() ? api.tr(id, "bp.nohint") : lbl(p, displayColour)));
            if (chaos == ChaosEvent.FAKE_COLOR)
                p.sendMessage(api.tr(id, "bp.fake-hint", Colors.plain(actual).charAt(0)));
            if (mirror) p.sendMessage(api.tr(id, "bp.mirror-note"));
            p.sendMessage("§8§m                ");
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (colorBlind()) return;
            for (Player p : alivePlayers())
                p.sendTitle(api.tr(p.getUniqueId(), mirror ? "bp.avoid-title" : "bp.goal-title"),
                        lbl(p, displayColour), 2, 25, 6);
        }, chaos != null ? 35L : 1L);
    }

    private void updateDisplays() {
        long alive = alivePlayers().size();
        boolean mirror = chaos == ChaosEvent.MIRROR;

        if (bossBar != null) {
            bossBar.setColor(mirror ? BarColor.PURPLE : (secondsLeft <= 2 ? BarColor.RED : BarColor.PINK));
            bossBar.setProgress(Math.max(0, Math.min(1, secondsLeft / (double) Math.max(1, roundSeconds))));
            bossBar.setTitle("§d§lBLOCK PARTY §8| " + (mirror ? "§c§l⛔" : "§7🎯") + " §8| §c⏱ " + secondsLeft
                    + "s §8| §a" + alive + "👤");
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            String label = colorBlind() ? "§8???" : lbl(p, displayColour);
            String hud = api.tr(p.getUniqueId(), mirror ? "bp.hud.avoid" : "bp.hud.goal", label, secondsLeft, alive);
            p.sendActionBar(net.kyori.adventure.text.Component.text(hud));
        }
    }

    @Override
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning()) return defaultScoreboardLines(viewer);
        long alive = alivePlayers().size();
        long teams = players.values().stream().filter(BPPlayer::isAlive)
                .map(bp -> api.teams().getTeamByPlayer(bp.getUuid()).map(t -> t.getId()).orElse("·"))
                .distinct().count();
        java.util.List<String> l = new java.util.ArrayList<>();
        UUID vid = viewer.getUniqueId();
        l.add(api.tr(vid, "bp.sb.round", round, chaos != null ? " §8(§d" + chaos.name() + "§8)" : ""));
        l.add(api.tr(vid, chaos == ChaosEvent.MIRROR ? "bp.sb.avoid" : "bp.sb.goal",
                colorBlind() ? "§8???" : lbl(viewer, displayColour)));
        l.add(api.tr(vid, "bp.sb.time", Math.max(0, secondsLeft)));
        l.add("");
        l.add(api.tr(vid, "bp.sb.players", alive));
        l.add(api.tr(vid, "bp.sb.teams", teams));
        BPPlayer me = players.get(viewer.getUniqueId());
        if (me != null) {
            l.add("");
            l.add(me.isAlive() ? api.tr(vid, "bp.sb.alive") : api.tr(vid, "bp.sb.out", me.getEliminatedRound()));
            if (me.getClutches() > 0) l.add(api.tr(vid, "bp.sb.clutches", me.getClutches()));
        }
        return l;
    }

    // ── End-of-game: scoring, MVP, achievements ───────────────────────────────

    private void awardAndRecord(List<UUID> placement, BPPlayer winner) {
        var cfg = plugin.getConfig();
        int survivalPerRound = cfg.getInt("block-party.scoring.survival-per-round", 10);
        int first  = cfg.getInt("block-party.scoring.first", 200);
        int second = cfg.getInt("block-party.scoring.second", 150);
        int third  = cfg.getInt("block-party.scoring.third", 100);
        int top5   = cfg.getInt("block-party.scoring.top-5", 50);
        int top10  = cfg.getInt("block-party.scoring.top-10", 25);

        for (int i = 0; i < placement.size(); i++) {
            UUID u = placement.get(i);
            BPPlayer bp = players.get(u);
            int place = i + 1;
            int placementPts = switch (place) {
                case 1 -> first;
                case 2 -> second;
                case 3 -> third;
                default -> place <= 5 ? top5 : (place <= 10 ? top10 : 0);
            };
            int pts = placementPts + survivalPerRound * (bp != null ? bp.getRoundsSurvived() : 0);
            api.points().givePoints(u, pts, PointAward.Reason.PLACEMENT, registration.getId());
            statsService.recordPointsEarned(u, pts);
            api.games().recordGameParticipation(u, bp != null ? bp.getName() : "?", registration.getId(), i == 0);

            // Achievements.
            if (i == 0)              grant(u, "blockparty_color_master");
            if (i == 0 && bp != null && bp.getClutches() == 0) grant(u, "blockparty_untouchable");
            if (i < 5)               grant(u, "blockparty_survivor");
        }

        // Last-team-standing bonus.
        if (cfg.getBoolean("block-party.scoring.last-team-bonus-enabled", true) && winner != null) {
            api.teams().getTeamByPlayer(winner.getUuid()).ifPresent(t ->
                    api.points().giveTeamPoints(t.getId(), cfg.getInt("block-party.scoring.last-team-bonus", 150),
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
        tellKey("bp.results");
        broadcast("§8§m                                        ");
        if (winner != null) {
            Player wp = Bukkit.getPlayer(winner.getUuid());
            for (Player tp : Bukkit.getOnlinePlayers())
                tp.sendTitle("§6§l" + winner.getName(), api.tr(tp.getUniqueId(), "bp.wins"), 8, 60, 15);
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
            for (Player rp : Bukkit.getOnlinePlayers()) {
                rp.sendMessage("  " + m + " §f" + bp.getName() + " §8— "
                        + api.tr(rp.getUniqueId(), "bp.result-line", bp.getRoundsSurvived(), bp.getClutches()));
            }
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

    /**
     * True if the player is above a surviving colour. Looks at the first solid block under five points of
     * the hitbox (centre + corners), up to 3 blocks down — so a player who is mid-jump, or straddling two
     * blocks with one of them correct, is not eliminated while "standing on the right block".
     */
    private boolean standsOnKeep(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        if (w == null) return false;
        double[][] offs = {{0, 0}, {0.3, 0.3}, {0.3, -0.3}, {-0.3, 0.3}, {-0.3, -0.3}};
        int startY = (int) Math.floor(l.getY() - 0.01);
        for (double[] o : offs) {
            int bx = (int) Math.floor(l.getX() + o[0]);
            int bz = (int) Math.floor(l.getZ() + o[1]);
            for (int dy = 0; dy <= 3; dy++) {
                Material m = w.getBlockAt(bx, startY - dy, bz).getType();
                if (m.isAir()) continue;
                if (keepColours.contains(m)) return true;
                break;
            }
        }
        return false;
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
            nl.kmc.game.api.GamePlayerUtil.teleportToLobby(p, lobby);
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
