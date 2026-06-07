package nl.kmc.game.api;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * A shrinking "border ring": a vertical wall of particles in a circle that
 * slowly closes toward a centre and <b>damages</b> any player who steps
 * outside it. Shared by Survival Games and SkyWars so both have the same
 * closing-ring feel without a vanilla world border.
 *
 * <p>{@link #tick(Collection)} is meant to be called once per second.
 */
public final class ShrinkingBorderRing {

    private final Location center;
    private final double minRadius;
    private final double shrinkPerTick;   // tick() runs once/second
    private final double damage;
    private final double buffer;
    private final Particle particle;
    private double radius;

    public ShrinkingBorderRing(Location center, double startRadius, double minRadius,
                               double shrinkPerSecond, double damage, double buffer, Particle particle) {
        this.center        = center.clone();
        this.radius        = Math.max(minRadius, startRadius);
        this.minRadius     = minRadius;
        this.shrinkPerTick = Math.max(0, shrinkPerSecond);
        this.damage        = Math.max(0, damage);
        this.buffer        = Math.max(0, buffer);
        this.particle      = particle != null ? particle : Particle.FLAME;
    }

    public double getRadius() { return radius; }

    /** Shrinks the ring one step, draws it, and damages players outside it. */
    public void tick(Collection<? extends Player> targets) {
        radius = Math.max(minRadius, radius - shrinkPerTick);
        World w = center.getWorld();
        if (w == null) return;

        // Draw the ring as a low wall of particles (capped point count).
        int points = (int) Math.min(100, Math.max(24, radius * 4));
        for (int i = 0; i < points; i++) {
            double a = (2 * Math.PI * i) / points;
            double x = center.getX() + radius * Math.cos(a);
            double z = center.getZ() + radius * Math.sin(a);
            for (double dy = 0; dy <= 3; dy += 1.0) {
                w.spawnParticle(particle, x, center.getY() + dy, z, 1, 0, 0, 0, 0);
            }
        }

        // Damage anyone beyond the ring (horizontal distance).
        if (damage <= 0 || targets == null) return;
        double limit = Math.max(1, radius - buffer);
        double limitSq = limit * limit;
        for (Player p : targets) {
            if (p == null || !p.isOnline() || p.isDead()) continue;
            double dx = p.getLocation().getX() - center.getX();
            double dz = p.getLocation().getZ() - center.getZ();
            if (dx * dx + dz * dz > limitSq) {
                p.damage(damage);
                p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getEyeLocation(), 4, 0.2, 0.2, 0.2, 0);
            }
        }
    }
}
