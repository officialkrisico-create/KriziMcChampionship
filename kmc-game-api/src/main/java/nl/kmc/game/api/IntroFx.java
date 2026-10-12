package nl.kmc.game.api;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * The show around a game start: a title that types itself out, lightning and a ring of sparks over the
 * arena, a rising 5-4-3-2-1 with colours and pitch, and a launch burst at GO. Pure presentation — every
 * method is safe to skip ({@code start-flow.fx: false} in a game's config switches it all off).
 */
public final class IntroFx {

    private IntroFx() {}

    private static final String[] COUNT_COLOURS = {"§c§l", "§6§l", "§e§l", "§a§l", "§b§l"};   // 1 → 5

    // ── Title that types itself ───────────────────────────────────────────────

    /**
     * Reveals {@code title} one visible character at a time (2 ticks each, with a soft tick), then shows
     * the full title with {@code subtitle} for {@code holdTicks}.
     */
    public static void typewriter(JavaPlugin plugin, Collection<Player> players, String title, String subtitle,
                                  int holdTicks, Consumer<BukkitTask> track) {
        int visible = visibleLength(title);
        if (visible == 0) {
            for (Player p : players) p.sendTitle(title, subtitle, 5, holdTicks, 10);
            return;
        }
        int step = visible > 24 ? 1 : 2;
        for (int n = 1; n <= visible; n++) {
            final int upTo = n;
            track.accept(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String part = prefix(title, upTo);
                for (Player p : players) {
                    if (!p.isOnline()) continue;
                    p.sendTitle(part, "", 0, 12, 0);
                    if (upTo % 2 == 0) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.8f + upTo * 0.025f);
                }
            }, (long) n * step));
        }
        track.accept(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : players) {
                if (!p.isOnline()) continue;
                p.sendTitle(title, subtitle, 0, holdTicks, 12);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }, (long) visible * step + 2));
    }

    /** First {@code n} visible characters of a colour-coded string, keeping every code seen so far. */
    static String prefix(String s, int n) {
        StringBuilder out = new StringBuilder();
        int seen = 0;
        for (int i = 0; i < s.length() && seen < n; i++) {
            char c = s.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(c).append(s.charAt(++i));
                continue;
            }
            out.append(c);
            seen++;
        }
        return out.toString();
    }

    static int visibleLength(String s) {
        int seen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            seen++;
        }
        return seen;
    }

    // ── Arena lights ──────────────────────────────────────────────────────────

    /** A (harmless) lightning flash plus an expanding ring of sparks over the arena centre. */
    public static void arenaFlash(JavaPlugin plugin, Location center, Consumer<BukkitTask> track) {
        if (center == null || center.getWorld() == null) return;
        World w = center.getWorld();
        w.strikeLightningEffect(center);
        final double[] r = {1.0};
        final int[] ticks = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            r[0] += 1.2;
            for (int i = 0; i < 28; i++) {
                double a = Math.PI * 2 * i / 28;
                w.spawnParticle(Particle.END_ROD, center.getX() + Math.cos(a) * r[0], center.getY() + 1,
                        center.getZ() + Math.sin(a) * r[0], 1, 0, 0, 0, 0);
            }
            if (++ticks[0] >= 12 && self[0] != null) self[0].cancel();
        }, 2L, 2L);
        track.accept(self[0]);
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    /** One step of the countdown: bigger colour/pitch the closer to zero, with a burst under the players. */
    public static void countdown(Collection<Player> players, int number, int total,
                                 java.util.function.Function<Player, String> subtitle) {
        String colour = COUNT_COLOURS[Math.max(0, Math.min(COUNT_COLOURS.length - 1, number - 1))];
        float pitch = 0.7f + 0.8f * (1f - (number - 1f) / Math.max(1f, total - 1f));
        for (Player p : players) {
            if (!p.isOnline()) continue;
            p.sendTitle(colour + number, subtitle.apply(p), 0, 22, 4);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, pitch);
            if (number <= 3) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            p.spawnParticle(Particle.FIREWORK, p.getLocation().add(0, 0.2, 0), 8, 0.4, 0.1, 0.4, 0.02);
        }
    }

    // ── GO ────────────────────────────────────────────────────────────────────

    /** Horn, a burst of particles and a (harmless) firework at every player. */
    public static void go(JavaPlugin plugin, Collection<Player> players) {
        for (Player p : players) {
            if (!p.isOnline()) continue;
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.8f, 1.2f);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
            p.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.3);
        }
        // One firework above the middle of the group (fireworks never damage — FireworkSafetyListener).
        Player any = players.stream().filter(Player::isOnline).findFirst().orElse(null);
        if (any == null) return;
        try {
            Firework fw = any.getWorld().spawn(any.getLocation().add(0, 3, 0), Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().withColor(Color.YELLOW, Color.WHITE)
                    .with(FireworkEffect.Type.BALL_LARGE).flicker(true).build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 2L);
        } catch (Throwable ignored) { }
    }
}
