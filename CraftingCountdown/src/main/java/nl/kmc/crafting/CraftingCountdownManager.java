package nl.kmc.crafting;

import net.kyori.adventure.text.Component;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Crafting Countdown: every team has a work area with a supply chest and a delivery block. Each round all
 * teams get the same target; raw materials are put in the supply chest, the team crafts the item with the
 * normal crafting table and right-clicks the delivery block. Items can be handed in piece by piece; the
 * reward is paid once per task (to whoever handed in the last piece), the first team to finish gets a bonus.
 */
public final class CraftingCountdownManager extends ArcadeGameManager {

    private enum Phase { RUN, REST }

    private static final class TeamState {
        int area;
        final List<Recipe> tasks = new ArrayList<>();      // this round (1, or 2 with DOUBLE_RECIPE)
        int taskIndex;
        TaskProgress progress;
        boolean roundDone;
        int completedTasks;
        int roundsCompleted;
        TextDisplay hologram;
    }

    private final CraftingCountdownPlugin plugin;
    private final Map<String, TeamState> teams = new HashMap<>();
    private final Map<UUID, Integer> contribution = new HashMap<>();
    private final Set<String> usedRecipes = new HashSet<>();
    private final Set<String> firstBonusRounds = new HashSet<>();
    private List<Recipe> pool = new ArrayList<>();
    private List<Location> areas = new ArrayList<>();
    private List<Location> deliveries = new ArrayList<>();
    private List<Location> supplies = new ArrayList<>();
    private final Random rnd = new Random();

    private Phase phase = Phase.REST;
    private int round;
    private int totalRounds;
    private long phaseStart, phaseEnd;
    private String modifier = "NONE";

    public CraftingCountdownManager(CraftingCountdownPlugin plugin, GameRegistration reg, StatisticsService stats,
                                    ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§6§lCRAFTING COUNTDOWN"; }
    @Override protected String introSubKey() { return "@crafting.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@crafting.tut.1", "@crafting.tut.2", "@crafting.tut.3", "@crafting.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/craftcountdown world)");
        int a = arena.locs("areas").size(), d = arena.locs("deliveries").size(), s = arena.locs("supplies").size();
        if (a == 0) p.add("geen werkgebieden (/craftcountdown area center|delivery|supply)");
        if (a != d || a != s) p.add("aantal centers (" + a + "), inlevermomenten (" + d + ") en kisten (" + s + ") klopt niet");
        List<String> rp = new ArrayList<>();
        List<Recipe> parsed = Recipe.parse(gamePlugin.getConfig().getList("recipes"), rp);
        if (parsed.isEmpty()) p.add("geen geldige recepten in config.yml");
        p.addAll(rp.stream().map(x -> "recept: " + x).toList());
        return p;
    }

    @Override protected List<Location> spawnPoints() { return arena.locs("areas"); }

    @Override
    protected Location flyoverCenter() {
        List<Location> a = arena.locs("areas");
        if (a.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location l : a) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        return new Location(a.get(0).getWorld(), x / a.size(), y / a.size() + 2, z / a.size());
    }

    @Override
    protected Location respawnLocation(Player p) {
        TeamState ts = teams.get(teamId(p.getUniqueId()));
        return ts != null && ts.area < areas.size() ? areas.get(ts.area) : p.getLocation();
    }

    @Override
    protected void prepareGame() {
        areas = arena.locs("areas");
        deliveries = arena.locs("deliveries");
        supplies = arena.locs("supplies");
        pool = Recipe.parse(gamePlugin.getConfig().getList("recipes"), new ArrayList<>());
        teams.clear();
        contribution.clear();
        usedRecipes.clear();
        firstBonusRounds.clear();
        totalRounds = Math.max(1, gamePlugin.getConfig().getInt("game.rounds", 5));
        round = 0;
        List<String> ids = activeTeams();
        for (int i = 0; i < ids.size(); i++) {
            TeamState ts = new TeamState();
            ts.area = i % areas.size();
            teams.put(ids.get(i), ts);
        }
        if (ids.size() > areas.size())
            log.warning("[CraftingCountdown] " + ids.size() + " teams but only " + areas.size()
                    + " work areas — teams share areas.");
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            TeamState ts = teams.get(teamId(id));
            if (p != null && ts != null) GamePlayerUtil.safeTeleport(p, areas.get(ts.area));
        }
    }

    @Override
    protected void beginPlay() {
        startRound(1);
    }

    // ── Rounds ────────────────────────────────────────────────────────────────

    private void startRound(int n) {
        round = n;
        phase = Phase.RUN;
        var cfg = gamePlugin.getConfig();
        phaseStart = ticks;
        phaseEnd = ticks + cfg.getInt("game.round-seconds", 45) * 20L;
        List<String> mods = cfg.getStringList("game.modifiers");
        modifier = mods.isEmpty() ? "NONE" : mods.get((n - 1) % mods.size()).toUpperCase(Locale.ROOT);
        List<String> order = cfg.getStringList("game.difficulty-order");
        Recipe.Difficulty want = Recipe.Difficulty.EASY;
        if (!order.isEmpty()) {
            try { want = Recipe.Difficulty.valueOf(order.get((n - 1) % order.size()).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        boolean repeat = cfg.getBoolean("game.allow-repeat", false);
        int count = "DOUBLE_RECIPE".equals(modifier) ? 2 : 1;
        List<Recipe> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Recipe r = Recipe.pick(pool, want, usedRecipes, repeat, rnd);
            if (r == null) break;
            usedRecipes.add(r.id());
            tasks.add(r);
        }
        if (tasks.isEmpty()) { log.severe("No recipe available — ending."); end(); return; }

        Map<Material, Integer> resources = new LinkedHashMap<>();
        for (Recipe r : tasks) r.resources().forEach((m, a) -> resources.merge(m, a, Integer::sum));

        for (var en : teams.entrySet()) {
            TeamState ts = en.getValue();
            ts.tasks.clear();
            ts.tasks.addAll(tasks);
            ts.taskIndex = 0;
            ts.progress = new TaskProgress(tasks.get(0).amount());
            ts.roundDone = false;
            fillSupply(ts.area, resources);
            ensureHologram(ts);
        }
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !isParticipant(id)) continue;
            p.getInventory().clear();
            Recipe first = tasks.get(0);
            p.sendTitle(api.tr(id, "crafting.round.title", n, totalRounds),
                    "§e" + first.amount() + "x " + Recipe.pretty(first.item())
                            + (tasks.size() > 1 ? " §7+ " + Recipe.pretty(tasks.get(1).item()) : ""), 5, 60, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }
    }

    private void fillSupply(int area, Map<Material, Integer> resources) {
        if (area >= supplies.size()) return;
        Block b = supplies.get(area).getBlock();
        if (!(b.getState() instanceof Container c)) {
            log.warning("[CraftingCountdown] supply #" + (area + 1) + " is not a container: " + b.getType());
            return;
        }
        var inv = c.getInventory();
        inv.clear();
        for (var en : resources.entrySet()) {
            int left = en.getValue();
            int max = en.getKey().getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                inv.addItem(new ItemStack(en.getKey(), n));
                left -= n;
            }
        }
    }

    private void emptySupplies() {
        for (Location l : supplies) {
            if (l.getBlock().getState() instanceof Container c) c.getInventory().clear();
        }
    }

    private void endRound() {
        phase = Phase.REST;
        emptySupplies();
        for (var en : teams.entrySet()) {
            TeamState ts = en.getValue();
            if (ts.roundDone) ts.roundsCompleted++;
        }
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.getInventory().clear();
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "crafting.round.over", round));
            List<String> ranked = book.rankTeams(teams.keySet());
            for (String t : ranked)
                v.sendMessage("  " + teamName(t) + " §8— §e" + book.team(t) + " ptn"
                        + (teams.get(t).roundDone ? " §a✔" : " §c✘"));
        }
        if (round >= totalRounds) { end(); return; }
        phaseStart = ticks;
        phaseEnd = ticks + gamePlugin.getConfig().getInt("game.rest-seconds", 10) * 20L;
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        if (phase == Phase.REST) {
            long left = Math.max(0, phaseEnd - ticks) / 20;
            bar("§6§lCRAFTING COUNTDOWN §8| §7Ronde " + (round + 1) + " over §e" + left + "s", 1, BarColor.YELLOW);
            if (ticks >= phaseEnd) startRound(round + 1);
            return;
        }
        long left = Math.max(0, phaseEnd - ticks);
        if (ticks % 20 == 0) for (TeamState ts : teams.values()) updateHologram(ts);
        bar("§6§lCRAFTING COUNTDOWN §8| §7Ronde §f" + round + "/" + totalRounds + " §8| §e" + mmss(left / 20),
                (double) left / Math.max(1, phaseEnd - phaseStart), left <= 10 * 20 ? BarColor.RED : BarColor.GREEN);
        boolean allDone = true;
        for (TeamState ts : teams.values()) if (!ts.roundDone) { allDone = false; break; }
        if (ticks >= phaseEnd || allDone) endRound();
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = false)
    public void onDeliver(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (!live || !getState().isRunning() || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (!isParticipant(p.getUniqueId())) return;
        Location at = e.getClickedBlock().getLocation();
        boolean isDelivery = false;
        for (Location d : deliveries) if (sameBlock(d, at)) { isDelivery = true; break; }
        if (!isDelivery) return;
        e.setCancelled(true);
        deliver(p);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld() && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private void deliver(Player p) {
        UUID id = p.getUniqueId();
        if (phase != Phase.RUN) { actionBar(p, api.tr(id, "crafting.not-running")); return; }
        TeamState ts = teams.get(teamId(id));
        if (ts == null || ts.roundDone || ts.progress == null) { actionBar(p, api.tr(id, "crafting.already-done")); return; }

        Recipe task = ts.tasks.get(ts.taskIndex);
        int have = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (isDeliverable(it, task.item())) have += it.getAmount();
        }
        if (have == 0) {
            actionBar(p, api.tr(id, "crafting.wrong-item", task.amount(), Recipe.pretty(task.item())));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        int take = ts.progress.offer(have);
        removeItems(p, task.item(), take);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
        if (!ts.progress.isDone()) {
            actionBar(p, api.tr(id, "crafting.progress", ts.progress.delivered(), ts.progress.required(),
                    Recipe.pretty(task.item())));
            updateHologram(ts);
            return;
        }
        completeTask(p, ts, task);
    }

    /** Plain items only: renamed or enchanted items are not accepted as a delivery. */
    private static boolean isDeliverable(ItemStack it, Material want) {
        if (it == null || it.getType() != want) return false;
        if (!it.hasItemMeta()) return true;
        var m = it.getItemMeta();
        return !m.hasDisplayName() && !m.hasEnchants();
    }

    private void removeItems(Player p, Material m, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (!isDeliverable(it, m)) continue;
            int n = Math.min(left, it.getAmount());
            if (n == it.getAmount()) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - n);
            left -= n;
        }
    }

    private void completeTask(Player p, TeamState ts, Recipe task) {
        UUID id = p.getUniqueId();
        String team = teamId(id);
        var cfg = gamePlugin.getConfig();
        int pts = switch (task.difficulty()) {
            case EASY -> cfg.getInt("points.easy", 10);
            case MEDIUM -> cfg.getInt("points.medium", 25);
            case HARD -> cfg.getInt("points.hard", 45);
        };
        String key = "task:" + round + ":" + team + ":" + ts.taskIndex;
        award(key, id, pts, PointAward.Reason.OBJECTIVE, "task-" + task.difficulty().name().toLowerCase(Locale.ROOT));
        contribution.merge(id, 1, Integer::sum);
        ts.completedTasks++;

        boolean last = ts.taskIndex + 1 >= ts.tasks.size();
        // the first team to finish the round's (last) task gets the bonus, once per round
        if (last && firstBonusRounds.add("r" + round)) {
            award("first:" + round, id, cfg.getInt("points.first-delivery", 10), PointAward.Reason.BONUS, "first-delivery");
            tellKey("crafting.first", teamName(team), Recipe.pretty(task.item()));
        } else {
            tellKey("crafting.done", teamName(team), p.getName(), Recipe.pretty(task.item()));
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        for (UUID m : membersOf(team)) {
            Player mp = Bukkit.getPlayer(m);
            if (mp != null) mp.sendTitle("§a§l✔", "§7" + Recipe.pretty(task.item()), 0, 30, 10);
        }

        if (last) {
            ts.roundDone = true;
            ts.progress = null;
        } else {
            ts.taskIndex++;
            ts.progress = new TaskProgress(ts.tasks.get(ts.taskIndex).amount());
            Recipe next = ts.tasks.get(ts.taskIndex);
            for (UUID m : membersOf(team)) {
                Player mp = Bukkit.getPlayer(m);
                if (mp != null) actionBar(mp, api.tr(m, "crafting.next-task", next.amount(), Recipe.pretty(next.item())));
            }
        }
        updateHologram(ts);
    }

    // ── Holograms ─────────────────────────────────────────────────────────────

    private void ensureHologram(TeamState ts) {
        if (ts.area >= deliveries.size()) return;
        if (ts.hologram != null && ts.hologram.isValid()) return;
        Location at = deliveries.get(ts.area).clone().add(0.5, 2.0, 0.5);
        if (at.getWorld() == null) return;
        ts.hologram = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setSeeThrough(false);
            d.setBackgroundColor(Color.fromARGB(160, 0, 0, 0));
        });
    }

    private void updateHologram(TeamState ts) {
        ensureHologram(ts);
        if (ts.hologram == null || !ts.hologram.isValid()) return;
        if (ts.roundDone || ts.progress == null || ts.tasks.isEmpty()) {
            ts.hologram.text(Component.text("✔", net.kyori.adventure.text.format.NamedTextColor.GREEN));
            return;
        }
        Recipe t = ts.tasks.get(ts.taskIndex);
        ts.hologram.text(Component.text("§e" + ts.progress.required() + "x ")
                .append(Component.translatable(t.item().translationKey()))
                .append(Component.text("\n§7" + ts.progress.delivered() + "/" + ts.progress.required()
                        + (ts.tasks.size() > 1 ? " §8(" + (ts.taskIndex + 1) + "/" + ts.tasks.size() + ")" : ""))));
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        TeamState ts = teams.get(team);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "crafting.sb.round", Math.max(1, round), totalRounds));
        l.add(api.tr(id, "crafting.sb.time", mmss(Math.max(0, phaseEnd - ticks) / 20)));
        if (ts != null && phase == Phase.RUN && !ts.tasks.isEmpty()) {
            Recipe t = ts.tasks.get(ts.taskIndex);
            l.add(api.tr(id, "crafting.sb.goal", t.amount(), Recipe.pretty(t.item())));
            l.add(ts.roundDone ? api.tr(id, "crafting.sb.done")
                    : api.tr(id, "crafting.sb.progress", ts.progress.delivered(), ts.progress.required()));
        }
        l.add("");
        l.add(api.tr(id, "crafting.sb.team", book.team(team)));
        l.add(api.tr(id, "crafting.sb.you", contribution.getOrDefault(id, 0)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        int bonus = gamePlugin.getConfig().getInt("points.all-complete", 30);
        for (var en : teams.entrySet()) {
            if (en.getValue().roundsCompleted >= totalRounds && !en.getKey().startsWith("solo_"))
                awardTeam("all:" + en.getKey(), en.getKey(), bonus, PointAward.Reason.BONUS, "all-complete");
            else if (en.getValue().roundsCompleted >= totalRounds)
                award("all:" + en.getKey(), membersOf(en.getKey()).get(0), bonus, PointAward.Reason.BONUS, "all-complete");
        }
        List<String> ranked = book.rankTeams(teams.keySet());
        awardTeamPlacements(ranked);

        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(contribution.getOrDefault(b, 0), contribution.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "crafting.results"));
            for (String t : ranked) {
                TeamState ts = teams.get(t);
                v.sendMessage("  " + teamName(t) + " §8— §e" + book.team(t) + " ptn §8| §7"
                        + ts.completedTasks + " opdrachten, " + ts.roundsCompleted + "/" + totalRounds + " rondes");
            }
        }
        UUID mvp = null;
        int best = -1;
        for (UUID id : players) {
            int c = contribution.getOrDefault(id, 0);
            if (c > best) { best = c; mvp = id; }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        return new Result(winner, mvp, mvp != null ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        emptySupplies();
        for (TeamState ts : teams.values()) {
            if (ts.hologram != null && ts.hologram.isValid()) ts.hologram.remove();
        }
        teams.clear();
    }
}
