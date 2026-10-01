package nl.kmc.game.api;

import nl.kmc.core.api.KMCApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared "riedeltje" (routine) every game can use to start a match: teleport
 * + freeze players, show an intro title, fly the camera around the arena,
 * drop a few tutorial tips, count down, then hand off to the game's own
 * combat/logic start.
 *
 * <p>Lifted out of QuakeCraft's and TNTTag's hand-rolled {@code runStartFlow()}
 * (both had independently built the same intro → tutorial → countdown
 * staging) so every other game gets it for free instead of re-implementing
 * it, and adds one new stage none of them had: an arena flyover via
 * {@link KMCApi#cinematics()}, auto-generated if no admin recording exists.
 *
 * <p>Config keys (read from the calling game's own {@code config.yml},
 * matching the convention QuakeCraft/TNTTag already established):
 * <pre>
 * start-flow:
 *   introduction: true
 *   flyover:      true
 *   tutorial:     true
 *   countdown:    true
 * start-sequence:
 *   intro-seconds:     5
 *   intro-subtitle:    "..."
 *   flyover-radius:    20
 *   tutorial-seconds:  10
 *   tutorial-messages: ["...", "..."]
 *   countdown-seconds: 5
 * </pre>
 */
public final class StandardStartFlow {

    /** Supplies what this specific game needs — everything else is generic. */
    public interface Callbacks {
        /** Players to run the presentation for. */
        List<Player> participants();

        /** Shown as the big intro title (e.g. "§c§lQUAKECRAFT"). */
        String introTitle();

        /** Default tutorial lines if {@code start-sequence.tutorial-messages} isn't set. */
        default List<String> defaultTutorialMessages() { return List.of(); }

        /**
         * Centre point for the auto-generated flyover fallback (only used if
         * no {@code arena-<gameId>} route has been recorded yet). Return
         * null to skip the flyover entirely when none is recorded.
         */
        default Location flyoverCenter() { return null; }

        /** Called once the whole presentation finishes — give kit, start combat, etc. */
        void onFinished();
    }

    private final JavaPlugin   plugin;
    private final KMCApi       api;
    private final String       gameId;
    private final BooleanSupplier isRunning;
    private final Consumer<String> broadcast;
    private final Callbacks    callbacks;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public StandardStartFlow(JavaPlugin plugin, KMCApi api, String gameId,
                             BooleanSupplier isRunning, Consumer<String> broadcast,
                             Callbacks callbacks) {
        this.plugin    = plugin;
        this.api       = api;
        this.gameId    = gameId;
        this.isRunning = isRunning;
        this.broadcast = broadcast;
        this.callbacks = callbacks;
    }

    /**
     * Freezes every participant in place for the duration of the staged
     * presentation. Call from {@code onPrepare()}, AFTER the game's own
     * teleport/gamemode/inventory setup — this deliberately does NOT call
     * {@link GamePlayerUtil#resetPlayer} itself, since some games need a
     * gamemode other than ADVENTURE during play (e.g. SURVIVAL for building
     * games) and resetting here would stomp on that choice.
     */
    public void prepareAndFreeze() {
        int freezeTicks = 20 * 60 * 10; // generous ceiling — explicitly removed in finish(), never relies on expiry
        PotionTypeHolder jump = PotionTypeHolder.jumpBoost();
        for (Player p : callbacks.participants()) {
            GamePlayerUtil.freezePlayer(p, freezeTicks);
            if (jump.type() != null) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(jump.type(), freezeTicks, 128, true, false, false));
            }
        }
    }

    /** Runs the staged presentation. Call from {@code onGameStart()} (ACTIVE has already begun). */
    public void start() {
        cancel();
        boolean introOn    = flowEnabled("introduction");
        boolean flyoverOn  = flowEnabled("flyover");
        boolean tutorialOn = flowEnabled("tutorial");
        boolean countdownOn = flowEnabled("countdown");

        int introSec = introOn ? seconds("intro-seconds", 3) : 0;

        long t = 0;
        if (introOn) { schedule(t, this::showIntroduction); t += introSec * 20L; }

        if (flyoverOn) {
            schedule(t, this::playFlyover); // chains tutorial+countdown itself via its own onComplete
            return;
        }
        scheduleTutorialThenCountdown(t, tutorialOn, countdownOn);
    }

    /** Cancels any pending presentation stages (e.g. the game was force-stopped mid-intro). */
    public void cancel() {
        for (BukkitTask t : tasks) if (t != null) t.cancel();
        tasks.clear();
    }

    // ── Stages ───────────────────────────────────────────────────────────────

    private void showIntroduction() {
        String sub = plugin.getConfig().getString("start-sequence.intro-subtitle", "");
        for (Player p : callbacks.participants()) {
            p.sendTitle(callbacks.introTitle(), ChatColor.translateAlternateColorCodes('&', "&e" + sub), 8, 70, 12);
            p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.01);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    private void playFlyover() {
        Location center = callbacks.flyoverCenter();
        double radius = plugin.getConfig().getDouble("start-sequence.flyover-radius", 20.0);
        double height = plugin.getConfig().getDouble("start-sequence.flyover-height", 0.0);
        boolean played = api.cinematics().playArenaFlyover(gameId, callbacks.participants(), center, radius, height,
                () -> scheduleTutorialThenCountdown(0,
                        flowEnabled("tutorial"), flowEnabled("countdown")));
        if (!played) {
            // No route and no centre to auto-generate from — continue immediately.
            scheduleTutorialThenCountdown(0, flowEnabled("tutorial"), flowEnabled("countdown"));
        }
    }

    private void scheduleTutorialThenCountdown(long startDelay, boolean tutorialOn, boolean countdownOn) {
        long t = startDelay;
        if (tutorialOn) {
            int tutSec = seconds("tutorial-seconds", 6);
            List<String> msgs = tutorialMessages();
            long step = msgs.isEmpty() ? 0 : Math.max(20L, (tutSec * 20L) / msgs.size());
            for (int i = 0; i < msgs.size(); i++) {
                final String m = msgs.get(i);
                schedule(t + i * step, () -> broadcast.accept(m));
            }
            t += tutSec * 20L;
        }

        if (countdownOn) {
            int cdSec = seconds("countdown-seconds", 5);
            final long base = t;
            for (int n = cdSec; n >= 1; n--) {
                final int num = n;
                schedule(base + (cdSec - n) * 20L, () -> showCountdownNumber(num));
            }
            t += cdSec * 20L;
        }

        schedule(t, this::finish);
    }

    private void showCountdownNumber(int n) {
        for (Player p : callbacks.participants()) {
            p.sendTitle("§e§l" + n, "§7Maak je klaar...", 0, 22, 4);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
        }
    }

    private void finish() {
        PotionTypeHolder jump = PotionTypeHolder.jumpBoost();
        for (Player p : callbacks.participants()) {
            GamePlayerUtil.unfreezePlayer(p);
            if (jump.type() != null) p.removePotionEffect(jump.type());
        }
        callbacks.onFinished();
    }

    // ── Config / scheduling helpers ────────────────────────────────────────────

    private List<String> tutorialMessages() {
        List<String> cfg = plugin.getConfig().getStringList("start-sequence.tutorial-messages");
        if (cfg != null && !cfg.isEmpty()) {
            return cfg.stream().map(s -> ChatColor.translateAlternateColorCodes('&', s)).toList();
        }
        return callbacks.defaultTutorialMessages();
    }

    private void schedule(long delayTicks, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isRunning.getAsBoolean()) return; // match aborted mid-presentation
            r.run();
        }, Math.max(0, delayTicks)));
    }

    private boolean flowEnabled(String key) { return plugin.getConfig().getBoolean("start-flow." + key, true); }
    private int seconds(String key, int def) { return Math.max(0, plugin.getConfig().getInt("start-sequence." + key, def)); }

    /** Lazily resolves the Jump Boost effect type via Paper's registry — avoids a hard class dependency. */
    private record PotionTypeHolder(org.bukkit.potion.PotionEffectType type) {
        static PotionTypeHolder jumpBoost() {
            try {
                return new PotionTypeHolder(io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                        .get(org.bukkit.NamespacedKey.minecraft("jump_boost")));
            } catch (Exception e) { return new PotionTypeHolder(null); }
        }
    }
}
