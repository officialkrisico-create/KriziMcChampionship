package nl.kmc.formula.managers;

import nl.kmc.formula.FormulaMCPlugin;
import nl.kmc.formula.models.Circuit;
import nl.kmc.formula.models.Gate;
import nl.kmc.formula.models.Racer;
import org.bukkit.*;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Supplier;

/**
 * Boost pads, item boxes and power-ups of Formula MC.
 *
 * <ul>
 *   <li><b>Boost pads</b> — gates added with {@code /fmc pad add}, or any block of {@code boost.pad-material}
 *       the boat drives over: a short speed boost.</li>
 *   <li><b>Item boxes</b> — spots added with {@code /fmc box add}: drive through one to get a power-up.
 *       The further back you are, the better the odds of a Turbo (rubber banding).</li>
 *   <li><b>Power-ups</b> — TURBO (speed), SHIELD (blocks the next hit), BLIND (the racer ahead can't see for
 *       a moment) and STUN (the racer ahead is slowed down). Used with right-click.</li>
 * </ul>
 */
public final class RacePowerups {

    public enum PowerUp {
        TURBO(Material.FIREWORK_ROCKET, "turbo"),
        SHIELD(Material.SHIELD, "shield"),
        BLIND(Material.INK_SAC, "blind"),
        STUN(Material.PACKED_ICE, "stun");

        final Material icon;
        final String key;
        PowerUp(Material icon, String key) { this.icon = icon; this.key = key; }
    }

    /** Text lookup per player language (nl/en). */
    public interface Text { String tr(UUID id, String key, Object... args); }

    private static final class State {
        PowerUp held;
        long turboUntil, shieldUntil, stunUntil, padCooldownUntil;
    }

    private final FormulaMCPlugin plugin;
    private final Text text;
    private final NamespacedKey itemKey;
    private final Map<UUID, State> states = new HashMap<>();
    private final Random rng = new Random();

    private Circuit circuit;
    private final List<Location> boxSpots = new ArrayList<>();
    private final Map<Integer, ItemDisplay> boxEntities = new HashMap<>();
    private final Map<Integer, Long> boxRespawnAt = new HashMap<>();

    public RacePowerups(FormulaMCPlugin plugin, Text text) {
        this.plugin = plugin;
        this.text = text;
        this.itemKey = new NamespacedKey(plugin, "fmc_powerup");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void begin(Circuit c) {
        clear();
        this.circuit = c;
    }

    /** Puts the item boxes on the track (called when the race itself goes live, not for qualifying). */
    public void spawnBoxes() {
        if (circuit == null || !cfg().getBoolean("powerups.enabled", true)) return;
        for (ItemDisplay d : boxEntities.values()) if (d != null && d.isValid()) d.remove();
        boxEntities.clear();
        boxRespawnAt.clear();
        boxSpots.clear();
        boxSpots.addAll(circuit.getItemBoxes());
        for (int i = 0; i < boxSpots.size(); i++) spawnBox(i);
    }

    /** Removes boxes, state and held items; called when the game ends. */
    public void clear() {
        for (ItemDisplay d : boxEntities.values()) if (d != null && d.isValid()) d.remove();
        boxEntities.clear();
        boxRespawnAt.clear();
        boxSpots.clear();
        states.clear();
        circuit = null;
    }

    public boolean hasPowerups() { return !boxSpots.isEmpty(); }

    private org.bukkit.configuration.file.FileConfiguration cfg() { return plugin.getConfig(); }

    private State st(UUID id) { return states.computeIfAbsent(id, k -> new State()); }

    // ── Item boxes ────────────────────────────────────────────────────────────

    private void spawnBox(int i) {
        Location at = boxSpots.get(i);
        if (at.getWorld() == null) return;
        Location l = at.clone().add(0, 1.2, 0);
        ItemDisplay d = at.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.NETHER_STAR));
            e.setBillboard(Display.Billboard.CENTER);
            e.setGlowing(true);
            e.setPersistent(false);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(1.1f, 1.1f, 1.1f), new Quaternionf()));
        });
        boxEntities.put(i, d);
    }

    private void hideBox(int i, long nowMs) {
        ItemDisplay d = boxEntities.remove(i);
        if (d != null && d.isValid()) d.remove();
        boxRespawnAt.put(i, nowMs + cfg().getInt("powerups.box-respawn-seconds", 6) * 1000L);
    }

    // ── Per-tick work for one racer ───────────────────────────────────────────

    /**
     * Pads, boxes, boost/stun physics and the weather drag for one racer's boat. Call every tick.
     *
     * @param ranking supplies the current ranking (best first) — only evaluated when somebody picks up a box
     */
    public void tick(Racer r, Boat boat, Player p, long nowMs, double weatherDrag, Supplier<List<Racer>> ranking) {
        State s = st(r.getUuid());
        Location bl = boat.getLocation();

        // Respawn boxes whose cooldown is over (cheap: only when something is waiting).
        if (!boxRespawnAt.isEmpty()) {
            for (Iterator<Map.Entry<Integer, Long>> it = boxRespawnAt.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                if (nowMs >= en.getValue()) { spawnBox(en.getKey()); it.remove(); }
            }
        }

        // Item boxes.
        if (s.held == null) {
            for (int i = 0; i < boxSpots.size(); i++) {
                if (!boxEntities.containsKey(i)) continue;
                Location spot = boxSpots.get(i);
                if (spot.getWorld() != bl.getWorld()) continue;
                if (spot.distanceSquared(bl) <= 2.6 * 2.6) {
                    hideBox(i, nowMs);
                    give(p, r, s, ranking.get());
                    break;
                }
            }
        }

        // Boost pads.
        if (nowMs >= s.padCooldownUntil && onPad(bl)) {
            s.padCooldownUntil = nowMs + 800;
            s.turboUntil = Math.max(s.turboUntil, nowMs + (long) (cfg().getDouble("boost.pad-seconds", 1.5) * 1000));
            p.playSound(bl, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.4f);
        }

        Vector v = boat.getVelocity();
        double h = Math.hypot(v.getX(), v.getZ());
        World w = bl.getWorld();

        if (nowMs < s.stunUntil) {
            boat.setVelocity(new Vector(v.getX() * 0.55, v.getY(), v.getZ() * 0.55));
            if (w != null) w.spawnParticle(Particle.SNOWFLAKE, bl.clone().add(0, 0.8, 0), 4, 0.4, 0.3, 0.4, 0.01);
            return;
        }
        if (nowMs < s.turboUntil) {
            double yaw = Math.toRadians(bl.getYaw());
            Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
            double max = cfg().getDouble("boost.max-speed", 1.1);
            double min = cfg().getDouble("boost.min-speed", 0.55);
            double target = Math.min(max, Math.max(min, h * 1.08 + 0.02));
            boat.setVelocity(new Vector(dir.getX() * target, v.getY(), dir.getZ() * target));
            if (w != null) {
                w.spawnParticle(Particle.FLAME, bl.clone().add(-dir.getX() * 1.2, 0.4, -dir.getZ() * 1.2), 3, 0.15, 0.1, 0.15, 0.02);
            }
            return;
        }
        if (weatherDrag > 0 && h > 0.2) {
            double f = 1.0 - weatherDrag;
            boat.setVelocity(new Vector(v.getX() * f, v.getY(), v.getZ() * f));
        }
    }

    private boolean onPad(Location bl) {
        if (circuit != null) {
            for (Gate g : circuit.getBoostPads()) if (g.contains(bl)) return true;
        }
        String mat = cfg().getString("boost.pad-material", "");
        if (mat != null && !mat.isBlank()) {
            Material m = Material.matchMaterial(mat);
            if (m != null && bl.getWorld() != null) {
                Location below = bl.clone().subtract(0, 0.6, 0);
                if (below.getBlock().getType() == m) return true;
            }
        }
        return false;
    }

    // ── Getting and using power-ups ───────────────────────────────────────────

    private void give(Player p, Racer r, State s, List<Racer> ranking) {
        int idx = ranking.indexOf(r);
        double pos = ranking.size() <= 1 ? 0.5 : Math.max(0, Math.min(1, (double) idx / (ranking.size() - 1)));
        // pos 0 = leader, 1 = last: the further back, the more Turbo and Stun; leaders get Shield and Blind.
        double[] wgt = {1.0 + 2.0 * pos, 1.5 + (1 - pos), 1.0 + 0.5 * (1 - pos), 1.0 + 1.5 * pos};
        double total = 0;
        for (double d : wgt) total += d;
        double x = rng.nextDouble() * total;
        PowerUp pick = PowerUp.values()[0];
        for (int i = 0; i < wgt.length; i++) {
            x -= wgt[i];
            if (x <= 0) { pick = PowerUp.values()[i]; break; }
        }
        s.held = pick;
        showHeld(p, pick);
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                text.tr(p.getUniqueId(), "fmc.pu.got", text.tr(p.getUniqueId(), "fmc.pu." + pick.key))));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
    }

    private int slot() { return Math.max(0, Math.min(8, cfg().getInt("powerups.slot", 4))); }

    private void showHeld(Player p, PowerUp pu) {
        ItemStack it = new ItemStack(pu.icon);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(text.tr(p.getUniqueId(), "fmc.pu." + pu.key));
        m.setLore(List.of(text.tr(p.getUniqueId(), "fmc.pu.use")));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, pu.name());
        it.setItemMeta(m);
        p.getInventory().setItem(slot(), it);
    }

    private void clearHeldItem(Player p) { p.getInventory().setItem(slot(), null); }

    /** True when {@code item} is a power-up item of this game. */
    public boolean isPowerupItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    /** Right-click: use the held power-up. */
    public void use(Player p, Racer r, List<Racer> ranking, java.util.function.Function<UUID, Player> playerOf, long nowMs) {
        State s = st(r.getUuid());
        if (s.held == null) return;
        UUID id = p.getUniqueId();
        switch (s.held) {
            case TURBO -> {
                s.turboUntil = Math.max(s.turboUntil, nowMs + (long) (cfg().getDouble("boost.turbo-seconds", 3.0) * 1000));
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
                p.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(id, "fmc.pu.used-turbo")));
            }
            case SHIELD -> {
                s.shieldUntil = nowMs + (long) (cfg().getDouble("powerups.shield-seconds", 10.0) * 1000);
                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f);
                p.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(id, "fmc.pu.used-shield")));
            }
            case BLIND, STUN -> {
                Racer target = ahead(r, ranking);
                Player tp = target != null ? playerOf.apply(target.getUuid()) : null;
                if (target == null || tp == null) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(id, "fmc.pu.no-target")));
                    return;       // keep the item
                }
                hit(s.held, p, tp, target, nowMs);
            }
        }
        s.held = null;
        clearHeldItem(p);
    }

    private Racer ahead(Racer me, List<Racer> ranking) {
        int idx = ranking.indexOf(me);
        for (int i = idx - 1; i >= 0; i--) {
            Racer c = ranking.get(i);
            if (!c.isFinished()) return c;
        }
        return null;
    }

    private void hit(PowerUp type, Player from, Player to, Racer target, long nowMs) {
        State ts = st(target.getUuid());
        UUID fid = from.getUniqueId(), tid = to.getUniqueId();
        if (nowMs < ts.shieldUntil) {
            ts.shieldUntil = 0;
            to.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(tid, "fmc.pu.blocked")));
            from.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(fid, "fmc.pu.was-blocked", to.getName())));
            to.playSound(to.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
            return;
        }
        long ms = (long) (cfg().getDouble("powerups.hit-seconds", 3.0) * 1000);
        if (type == PowerUp.BLIND) {
            to.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) (ms / 50), 0, false, false, false));
            to.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(tid, "fmc.pu.hit-blind", from.getName())));
        } else {
            ts.stunUntil = nowMs + ms;
            to.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(tid, "fmc.pu.hit-stun", from.getName())));
        }
        from.sendActionBar(net.kyori.adventure.text.Component.text(text.tr(fid, "fmc.pu.hit-sent", to.getName())));
        to.playSound(to.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
    }

    /** Held power-up of a racer (for the scoreboard); null when none. */
    public PowerUp held(UUID id) {
        State s = states.get(id);
        return s != null ? s.held : null;
    }

    public boolean isBoosting(UUID id, long nowMs) {
        State s = states.get(id);
        return s != null && nowMs < s.turboUntil;
    }

    public void forget(UUID id, Player p) {
        states.remove(id);
        if (p != null) clearHeldItem(p);
    }
}
