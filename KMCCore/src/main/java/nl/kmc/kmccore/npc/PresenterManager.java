package nl.kmc.kmccore.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.host.HostText;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.function.Predicate;

/**
 * The body of the KMC host: a mannequin NPC in the lobby with ONE shared text display above its head (the
 * speech bubble). It only presents; what is said, when and to whom is decided by
 * {@link nl.kmc.kmccore.host.HostManager}. Place it with {@code /kmcnpc presenter set} (or
 * {@code /kmchost spawn}); give it a skin with {@code /kmcnpc presenter skin <playername>}.
 *
 * <p>The bubble is a single entity that is reused for every sentence: it is hidden (not removed) between
 * sentences, so nothing piles up. Who sees it is set per player with show/hideEntity, so an announcement
 * for one team does not need a second display.
 */
public final class PresenterManager {

    private final KMCCore plugin;
    private final File file;
    /** Where the host stands right now: the placed spot, or the podium during a KMCAuto session. */
    private Location spot;
    /** The spot an admin placed (persisted). */
    private Location saved;
    private String skinName;
    private String npcName = "";
    private boolean showName = true;
    private double bubbleHeight = 2.6;
    private Mannequin body;
    private TextDisplay bubble;

    // scripted movement (staged speeches): the host walks and jumps for a while, then stands at its spot again
    private BukkitTask physicsTask;
    private Location walkTarget;
    private double walkSpeed;
    private Runnable walkDone;
    private int stuckTicks;
    private double lastDist;
    private int hopsLeft;
    private int hopGap;
    private int hopCountdown;

    public PresenterManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "presenter.yml");
        load();
        if (spot != null) Bukkit.getScheduler().runTaskLater(plugin, this::spawn, 40L);
        // never lost: a creative punch, /kill or an unloaded chunk must not leave the lobby without a host
        Bukkit.getScheduler().runTaskTimer(plugin, this::keepAlive, 200L, 100L);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public void setSpot(Location where) {
        removeEntities();
        saved = where.clone();
        spot = saved;
        save();
        spawn();
    }

    public boolean remove() {
        if (saved == null) return false;
        removeEntities();
        saved = null;
        spot = null;
        save();
        return true;
    }

    public void setSkin(String playerName) {
        this.skinName = playerName;
        save();
        if (body != null && body.isValid()) applySkin();
    }

    public boolean isPlaced() { return saved != null; }

    /** Where the host stands now (the podium while it presents there). */
    public Location getSpot() { return spot == null ? null : spot.clone(); }

    /** The spot an admin placed, whatever stage the host is on right now. */
    public Location getSavedSpot() { return saved == null ? null : saved.clone(); }

    /** Puts the host on a stage (the podium) for as long as it presents there; {@link #leaveStage()} undoes it. */
    public void takeStage(Location where) {
        if (saved == null || where == null || where.getWorld() == null) return;
        spot = where.clone();
        goHome();
    }

    /** Back to the spot the admin placed. */
    public void leaveStage() {
        if (saved == null) return;
        spot = saved;
        goHome();
    }

    public boolean isOnStage() { return saved != null && spot != null && spot != saved; }

    /** Applies the {@code host.npc.*} settings (name, bubble height, skin override) to the live entities. */
    public void configure(String name, boolean showName, double bubbleHeight, String skinOverride) {
        this.npcName = name == null ? "" : name;
        this.showName = showName;
        this.bubbleHeight = Math.max(0.5, Math.min(6.0, bubbleHeight));
        if (skinOverride != null && !skinOverride.isBlank()) this.skinName = skinOverride;
        if (body != null && body.isValid()) {
            applyName();
            applySkin();
        }
        if (bubble != null && bubble.isValid() && spot != null) bubble.teleport(spot.clone().add(0, this.bubbleHeight, 0));
    }

    // ── The speech bubble ─────────────────────────────────────────────────────

    /**
     * Shows {@code text} above the host's head, for the players that match {@code viewers} (null = all).
     * Reuses the one display. Does nothing when the host is not placed.
     *
     * @return false when there was nothing to show it on
     */
    public boolean showBubble(String text, Predicate<Player> viewers) {
        if (spot == null || spot.getWorld() == null) return false;
        ensureSpawned();
        if (bubble == null || !bubble.isValid()) return false;
        followBubble();                                  // the host may have walked away from its spot
        bubble.text(LegacyComponentSerializer.legacySection().deserialize("§f" + HostText.color(text)));
        bubble.setVisibleByDefault(viewers == null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewers == null || viewers.test(p)) p.showEntity(plugin, bubble);
            else p.hideEntity(plugin, bubble);
        }
        return true;
    }

    /** Takes the bubble away (the entity stays, hidden, for the next sentence). */
    public void clearBubble() {
        if (bubble == null || !bubble.isValid()) return;
        bubble.text(Component.empty());
        bubble.setVisibleByDefault(false);
        for (Player p : Bukkit.getOnlinePlayers()) p.hideEntity(plugin, bubble);
    }

    /** Turns the host towards the nearest player within {@code range} blocks (a natural "look at"). */
    public void lookAtNearest(double range) {
        if (body == null || !body.isValid() || spot == null || spot.getWorld() == null) return;
        if (walkTarget != null) return;                      // walking: the way ahead decides where it looks
        Location from = body.getLocation();
        Player best = null;
        double bestD = range * range;
        for (Player p : from.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(from);
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best == null) return;
        Vector dir = best.getEyeLocation().toVector().subtract(from.clone().add(0, 1.6, 0).toVector());
        Location l = body.getLocation();
        l.setDirection(dir);
        face(l.getYaw(), l.getPitch());
    }

    private void face(float yaw, float pitch) {
        try {
            body.setRotation(yaw, pitch);
            body.setBodyYaw(yaw);
        } catch (Throwable ignored) { }
    }

    // ── Scripted movement ─────────────────────────────────────────────────────

    /** Where the host stands right now (its spot when it is not spawned). */
    public Location position() {
        if (body != null && body.isValid()) return body.getLocation();
        return spot == null ? null : spot.clone();
    }

    public boolean isMoving() { return walkTarget != null; }

    /** Gravity and pushing on while the host acts (walks, jumps); off again when it stands still. */
    private void setPhysics(boolean on) {
        if (body == null || !body.isValid()) return;
        body.setGravity(on);
        body.setImmovable(!on);
        if (!on) body.setVelocity(new Vector());
    }

    /**
     * Walks to {@code target} at {@code blocksPerTick}, turning to face the way it goes. {@code done} runs when
     * it has arrived (or could not walk at all). A new walk replaces the previous one.
     */
    public void walkTo(Location target, double blocksPerTick, Runnable done) {
        if (target == null || spot == null) { if (done != null) done.run(); return; }
        ensureSpawned();
        if (body == null || !body.isValid()) { if (done != null) done.run(); return; }
        Runnable previous = walkDone;
        walkTarget = target.clone();
        walkSpeed = Math.max(0.05, Math.min(0.5, blocksPerTick));
        walkDone = done;
        stuckTicks = 0;
        lastDist = Double.MAX_VALUE;
        setPhysics(true);
        startPhysicsTask();
        if (previous != null) previous.run();
    }

    /** One jump on the spot (or none when it is in the air). */
    public void jump() {
        ensureSpawned();
        if (body == null || !body.isValid()) return;
        setPhysics(true);
        startPhysicsTask();
        jumpNow();
    }

    /** {@code times} jumps with {@code gapTicks} between them. */
    public void hop(int times, int gapTicks) {
        ensureSpawned();
        if (body == null || !body.isValid()) return;
        setPhysics(true);
        hopsLeft = Math.max(0, times);
        hopGap = Math.max(4, gapTicks);
        hopCountdown = 0;
        startPhysicsTask();
    }

    private void jumpNow() {
        Vector v = body.getVelocity();
        v.setY(0.46);
        body.setVelocity(v);
    }

    public void swing() {
        if (body != null && body.isValid()) {
            try { body.swingMainHand(); } catch (Throwable ignored) { }
        }
    }

    public void setBodyPose(Pose pose) {
        if (body != null && body.isValid()) {
            try { body.setPose(pose); } catch (Throwable ignored) { }
        }
    }

    public void turnTo(float yaw, float pitch) {
        if (body != null && body.isValid()) face(yaw, pitch);
    }

    /** Stops every movement and puts the host back on its spot, facing the way it was placed. */
    public void goHome() {
        cancelPhysics(false);
        if (body != null && body.isValid() && spot != null) {
            setPhysics(false);
            try { body.setPose(Pose.STANDING); } catch (Throwable ignored) { }
            body.teleport(spot);
            face(spot.getYaw(), spot.getPitch());
        }
        if (bubble != null && bubble.isValid() && spot != null) bubble.teleport(spot.clone().add(0, bubbleHeight, 0));
    }

    private void startPhysicsTask() {
        if (physicsTask != null) return;
        physicsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::physicsTick, 1L, 1L);
    }

    /** @param runDone whether a walk that is cut short still reports back */
    private void cancelPhysics(boolean runDone) {
        if (physicsTask != null) { physicsTask.cancel(); physicsTask = null; }
        Runnable d = walkDone;
        walkTarget = null;
        walkDone = null;
        hopsLeft = 0;
        if (runDone && d != null) d.run();
    }

    private void followBubble() {
        if (bubble == null || !bubble.isValid() || body == null || !body.isValid()) return;
        Location at = body.getLocation().add(0, bubbleHeight, 0);
        if (at.getWorld() != null && at.distanceSquared(bubble.getLocation()) > 0.0004) bubble.teleport(at);
    }

    private void physicsTick() {
        if (body == null || !body.isValid()) { cancelPhysics(true); return; }
        try {
            if (spot != null && body.getLocation().getY() < spot.getY() - 2.5) {      // fell off the edge: never lost
                body.teleport(spot);
                body.setVelocity(new Vector());
            }
            if (hopsLeft > 0 && --hopCountdown <= 0 && body.isOnGround()) {
                jumpNow();
                hopsLeft--;
                hopCountdown = hopGap;
            }
            if (walkTarget != null) walkStep();
            followBubble();
            boolean idle = walkTarget == null && hopsLeft == 0 && body.isOnGround()
                    && body.getVelocity().lengthSquared() < 0.003;
            if (idle) {
                setPhysics(false);
                cancelPhysics(false);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Host movement stopped: " + t);
            cancelPhysics(true);
        }
    }

    private void walkStep() {
        Location cur = body.getLocation();
        double dx = walkTarget.getX() - cur.getX();
        double dz = walkTarget.getZ() - cur.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist <= Math.max(0.12, walkSpeed * 0.75)) {            // arrived: land exactly on the spot
            Location end = walkTarget.clone();
            end.setYaw(cur.getYaw());
            end.setPitch(0);
            body.teleport(end);
            body.setVelocity(new Vector());
            Runnable d = walkDone;
            walkTarget = null;
            walkDone = null;
            if (d != null) d.run();
            return;
        }
        double step = Math.min(walkSpeed, dist);
        double vx = dx / dist * step, vz = dz / dist * step;
        Vector v = body.getVelocity();
        v.setX(vx);
        v.setZ(vz);
        body.setVelocity(v);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        face(yaw, 0f);

        // no progress for a while (blocked, or the entity does not move by velocity): step it by hand
        if (lastDist - dist < walkSpeed * 0.25) stuckTicks++; else stuckTicks = 0;
        lastDist = dist;
        if (stuckTicks >= 6) {
            Location next = cur.clone().add(vx, 0, vz);
            next.setYaw(yaw);
            body.teleport(next);
        }
    }

    public boolean isBubbleShown() {
        return bubble != null && bubble.isValid() && bubble.isVisibleByDefault();
    }

    // ── Entities ──────────────────────────────────────────────────────────────

    private void keepAlive() {
        if (spot == null || spot.getWorld() == null) return;
        if (body != null && body.isValid() && bubble != null && bubble.isValid()) return;
        if (!spot.getWorld().isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4)) return;
        spawn();
    }

    private void ensureSpawned() {
        if (body == null || !body.isValid() || bubble == null || !bubble.isValid()) spawn();
    }

    private void spawn() {
        if (spot == null || spot.getWorld() == null) return;
        removeEntities();
        try {
            body = spot.getWorld().spawn(spot, Mannequin.class, m -> {
                m.setImmovable(true);
                m.setGravity(false);
                m.setInvulnerable(true);
                m.setSilent(true);
                m.setPersistent(false);
                m.setDescription(null);
            });
            applyName();
            applySkin();
            bubble = spot.getWorld().spawn(spot.clone().add(0, bubbleHeight, 0), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
                d.setLineWidth(220);
                d.setTeleportDuration(2);               // follows a walking host smoothly
                d.setVisibleByDefault(false);
                d.text(Component.empty());
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Presenter NPC unavailable: " + t);
        }
    }

    private void applyName() {
        if (body == null) return;
        try {
            boolean visible = showName && !npcName.isBlank();
            body.customName(visible ? LegacyComponentSerializer.legacySection().deserialize(HostText.color(npcName)) : null);
            body.setCustomNameVisible(visible);
        } catch (Throwable ignored) { }
    }

    private void applySkin() {
        if (body == null || skinName == null || skinName.isBlank()) return;
        try {
            body.setProfile(ResolvableProfile.resolvableProfile().name(skinName.length() > 16
                    ? skinName.substring(0, 16) : skinName).build());
        } catch (Throwable ignored) { }
    }

    private void removeEntities() {
        cancelPhysics(false);
        if (body != null) { body.remove(); body = null; }
        if (bubble != null) { bubble.remove(); bubble = null; }
    }

    /** True when {@code entity} is the host's body (for interaction). */
    public boolean isBody(org.bukkit.entity.Entity entity) {
        return entity != null && body != null && body.getUniqueId().equals(entity.getUniqueId());
    }

    public void shutdown() { removeEntities(); }

    // ── Legacy API ────────────────────────────────────────────────────────────

    /**
     * Older call sites ({@code presenter.<key>} language lines). Routed through the host so it obeys the
     * same rule as everything else: no automatic speech outside a KMCAuto session.
     */
    public void say(String key, int seconds, Object... args) {
        var host = plugin.getHost();
        if (host == null) return;
        String text = plugin.getLanguageManager().tr("nl", key, args);
        host.autoSay(text, seconds);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        saved = y.getLocation("spot");
        spot = saved;
        skinName = y.getString("skin");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (saved != null) y.set("spot", saved);
        if (skinName != null) y.set("skin", skinName);
        try { y.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Could not save presenter.yml: " + e.getMessage()); }
    }
}
