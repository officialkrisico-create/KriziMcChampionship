package nl.kmc.sg.managers;

import nl.kmc.sg.SurvivalGamesPlugin;
import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Supply drops and timed cornucopia events for Survival Games.
 *
 * <p>Everything is driven by {@link #tick(int)} once a second with the seconds elapsed since the
 * bloodbath began. A drop is announced with its coordinates, a light beam marks the spot, and after
 * {@code drops.landing-delay-seconds} an epic chest appears there. The beam stays until the chest is
 * opened; unopened chests are removed again when the game ends.
 */
public final class SupplyDropManager {

    private record Drop(Location loc, BukkitTask beam) {}

    private final SurvivalGamesPlugin plugin;
    private final BiConsumer<String, Object[]> tell;
    private final Consumer<String> title;
    private final DoubleSupplier ringRadius;
    private final Random rng = new Random();

    private final List<Drop> landed = new ArrayList<>();
    private final List<BukkitTask> pending = new ArrayList<>();
    private final Set<Integer> firedEvents = new HashSet<>();
    private int dropsStarted;
    private int nextDropAt;
    private boolean stopped;

    public SupplyDropManager(SurvivalGamesPlugin plugin, BiConsumer<String, Object[]> tell,
                             Consumer<String> title, DoubleSupplier ringRadius) {
        this.plugin = plugin;
        this.tell = tell;
        this.title = title;
        this.ringRadius = ringRadius;
    }

    public void reset() {
        clearAll();
        stopped = false;
        dropsStarted = 0;
        firedEvents.clear();
        nextDropAt = plugin.getConfig().getInt("drops.first-seconds", 150);
    }

    /** Called once per second with the seconds since the bloodbath started. */
    public void tick(int elapsed) {
        if (stopped) return;
        var cfg = plugin.getConfig();

        if (cfg.getBoolean("drops.enabled", true)
                && dropsStarted < cfg.getInt("drops.max", 3) && elapsed >= nextDropAt) {
            nextDropAt = elapsed + Math.max(20, cfg.getInt("drops.interval-seconds", 120));
            if (startDropAtRandomSpot()) dropsStarted++;
        }

        List<?> events = cfg.getList("cornucopia-events");
        if (events == null) return;
        for (int i = 0; i < events.size(); i++) {
            if (firedEvents.contains(i) || !(events.get(i) instanceof Map<?, ?> m)) continue;
            int at = m.get("at") instanceof Number n ? n.intValue() : -1;
            if (at < 0 || elapsed < at) continue;
            firedEvents.add(i);
            fireEvent(String.valueOf(m.get("type")));
        }
    }

    /** No more new drops/events (deathmatch started); chests already on the ground stay. */
    public void stopScheduling() { stopped = true; }

    // ── Events ────────────────────────────────────────────────────────────────

    private void fireEvent(String type) {
        Location cor = corn();
        if (cor == null) return;
        switch (type.toUpperCase(Locale.ROOT)) {
            case "REFILL_CORNUCOPIA", "REFILL" -> {
                int n = plugin.getChestStocker().restockRareChests();
                if (n <= 0) return;
                tell.accept("sg.event.refill-chat", new Object[] {n});
                title.accept("sg.event.refill");
                cor.getWorld().playSound(cor, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.2f);
                cor.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, cor.clone().add(0, 1.5, 0), 60, 2, 1.5, 2, 0.3);
            }
            case "CORNUCOPIA_DROP", "DROP" -> {
                double r = Math.max(3, plugin.getConfig().getDouble("game.cornucopia-radius", 15) * 0.5);
                Location spot = surface(cor.getX() + (rng.nextDouble() * 2 - 1) * r,
                        cor.getZ() + (rng.nextDouble() * 2 - 1) * r, cor);
                if (spot != null) startDrop(spot);
            }
            default -> plugin.getLogger().warning("Unknown cornucopia event type: " + type);
        }
    }

    // ── Drops ─────────────────────────────────────────────────────────────────

    private boolean startDropAtRandomSpot() {
        Location cor = corn();
        if (cor == null) return false;
        double ring = Math.max(8, ringRadius.getAsDouble());
        for (int attempt = 0; attempt < 10; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double d = ring * (0.25 + 0.6 * rng.nextDouble());
            Location spot = surface(cor.getX() + Math.cos(a) * d, cor.getZ() + Math.sin(a) * d, cor);
            if (spot != null) { startDrop(spot); return true; }
        }
        return false;
    }

    /** The first free block above the ground at x/z; null when that column is void or open water/lava. */
    private Location surface(double x, double z, Location ref) {
        World w = ref.getWorld();
        if (w == null) return null;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int y = w.getHighestBlockYAt(bx, bz);
        if (y <= w.getMinHeight() + 1) return null;
        var ground = w.getBlockAt(bx, y, bz);
        if (ground.isLiquid() || !ground.getType().isSolid()) return null;
        return new Location(w, bx + 0.5, y + 1, bz + 0.5);
    }

    private void startDrop(Location spot) {
        int delay = Math.max(3, plugin.getConfig().getInt("drops.landing-delay-seconds", 15));
        tell.accept("sg.drop.chat-incoming", new Object[] {spot.getBlockX(), spot.getBlockZ(), delay});
        title.accept("sg.drop.incoming");
        for (Player p : Bukkit.getOnlinePlayers())
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 0.8f);

        // Falling "crate": a smoke column that comes down towards the spot, then the chest appears.
        final int[] left = {delay * 4};
        final BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World w = spot.getWorld();
            if (w == null || left[0] <= 0) {
                if (self[0] != null) { self[0].cancel(); pending.remove(self[0]); }
                if (w != null) land(spot);
                return;
            }
            double h = 1 + 28.0 * left[0] / (delay * 4.0);
            w.spawnParticle(Particle.CLOUD, spot.getX(), spot.getY() + h, spot.getZ(), 6, 0.3, 0.2, 0.3, 0.01);
            w.spawnParticle(Particle.END_ROD, spot.getX(), spot.getY() + 30, spot.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
            w.spawnParticle(Particle.FIREWORK, spot, 3, 0.4, 0.1, 0.4, 0.01);
            left[0]--;
        }, 5L, 5L);
        pending.add(self[0]);
    }

    private void land(Location spot) {
        if (!plugin.getGameManagerV2().getState().isRunning()) return;
        Location chest = plugin.getChestStocker().spawnDropChest(spot);
        if (chest == null) return;
        World w = chest.getWorld();
        w.playSound(chest, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.4f);
        w.spawnParticle(Particle.EXPLOSION, chest.clone().add(0.5, 0.5, 0.5), 1);
        Firework fw = w.spawn(chest.clone().add(0.5, 1, 0.5), Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.PURPLE, Color.YELLOW).flicker(true).build());
        meta.setPower(0);
        fw.setFireworkMeta(meta);
        fw.detonate();

        tell.accept("sg.drop.chat-landed", new Object[] {chest.getBlockX(), chest.getBlockZ()});

        BukkitTask beam = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (double dy = 1; dy < 26; dy += 1.0)
                w.spawnParticle(Particle.END_ROD, chest.getX() + 0.5, chest.getY() + dy, chest.getZ() + 0.5, 1, 0, 0, 0, 0);
        }, 0L, 10L);
        landed.add(new Drop(chest, beam));
    }

    /** Called from the listener when a player opens a chest; true if it was a supply drop. */
    public boolean onChestOpened(Player opener, Location at) {
        for (Iterator<Drop> it = landed.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            if (d.loc().getWorld() != at.getWorld() || d.loc().distanceSquared(at) > 1.5) continue;
            d.beam().cancel();
            it.remove();
            tell.accept("sg.drop.chat-opened", new Object[] {opener.getName()});
            at.getWorld().playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            return true;
        }
        return false;
    }

    public boolean isDrop(Location at) {
        for (Drop d : landed)
            if (d.loc().getWorld() == at.getWorld() && d.loc().distanceSquared(at) <= 1.5) return true;
        return false;
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void clearAll() {
        for (BukkitTask t : pending) t.cancel();
        pending.clear();
        for (Drop d : landed) {
            d.beam().cancel();
            var b = d.loc().getBlock();
            if (b.getType() == Material.CHEST) {
                if (b.getState() instanceof org.bukkit.block.Chest c) c.getBlockInventory().clear();
                b.setType(Material.AIR);
            }
        }
        landed.clear();
    }

    private Location corn() {
        var arena = plugin.getArenaManager().getArena();
        return arena != null ? arena.getCornucopiaCenter() : null;
    }
}
