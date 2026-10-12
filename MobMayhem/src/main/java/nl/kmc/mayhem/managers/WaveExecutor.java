package nl.kmc.mayhem.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import nl.kmc.mayhem.models.TeamGameState;
import nl.kmc.mayhem.waves.WaveDefinition;
import nl.kmc.mayhem.waves.WaveModifier;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Consumer;

/**
 * Executes a single team's wave:
 * <ol>
 *   <li>Spawn all mobs at random spawn points in the team's arena</li>
 *   <li>Apply wave modifiers (speed, healthy, poison, etc.)</li>
 *   <li>Tick task watches active mob count + duration</li>
 *   <li>Wave ends when all mobs dead OR timer expires</li>
 *   <li>Calls back the team's GameManager listener</li>
 * </ol>
 *
 * <p>Multiple WaveExecutors run in parallel — one per team.
 */
public class WaveExecutor {

    public static final String MOB_TEAM_KEY = "mm_team_id";
    public static final String MOB_WAVE_KEY = "mm_wave_number";
    /** Set (to 1) on the actual boss of a boss wave — escorts and baby zombies are not bosses. */
    public static final String MOB_BOSS_KEY = "mm_boss";

    /** Consecutive watch ticks a tracked mob could not be found (unloaded chunk) — pruned after a grace period. */
    private final Map<UUID, Integer> missingTicks = new HashMap<>();
    private int retargetCounter;
    private boolean glowShown;

    private final MobMayhemPlugin plugin;
    private final TeamGameState   state;
    private final Arena           arena;
    private final WaveDefinition  wave;
    private final Consumer<Boolean> onComplete; // true if team survived

    private BukkitTask  watchTask;
    private boolean     completed;
    private boolean     bloodMoonActive;
    private World       bloodMoonWorld;
    private boolean     bloodMoonPrevStorm;
    private long        bloodMoonPrevTime;

    public WaveExecutor(MobMayhemPlugin plugin, TeamGameState state, Arena arena,
                        WaveDefinition wave, Consumer<Boolean> onComplete) {
        this.plugin     = plugin;
        this.state      = state;
        this.arena      = arena;
        this.wave       = wave;
        this.onComplete = onComplete;
    }

    public void start() {
        if (!arena.hasMobSpawns()) {
            plugin.getLogger().warning("Team " + state.getTeamId() + " arena has no mob spawns!");
            finish(false);
            return;
        }

        // Pick modifiers
        Set<WaveModifier> modifiers = pickModifiers();
        state.startWave(wave.getWaveNumber(), modifiers);

        announceWaveStart(modifiers);

        // Apply player-side modifier effects (e.g. blindness)
        applyPlayerModifiers(modifiers);

        if (modifiers.contains(WaveModifier.BLOOD_MOON)) startBloodMoon();

        // Spawn mobs
        int multiplier = modifiers.contains(WaveModifier.DOUBLE_MOBS) ? 2 : 1;
        int attempted = 0;
        for (var entry : wave.getSpawns()) {
            int count = entry.count() * multiplier;
            attempted += count;
            for (int i = 0; i < count; i++) {
                spawnOne(entry.type(), modifiers);
            }
        }

        // Diagnostic: confirm the spawn loop actually produced live, tracked mobs.
        // If this ever logs 0/N, the spawnEntity() calls themselves are failing
        // (wrong world, invalid location, etc.) rather than mobs dying after spawn.
        int actuallyTracked = state.getActiveMobs().size();
        plugin.getLogger().info("[MobMayhem] Team " + state.getTeamId() + " wave "
                + wave.getWaveNumber() + ": attempted " + attempted + " spawns, "
                + actuallyTracked + " tracked as active mobs, "
                + arena.getMobSpawns().size() + " spawn point(s) available, world="
                + (arena.getPlayerSpawn() != null && arena.getPlayerSpawn().getWorld() != null
                        ? arena.getPlayerSpawn().getWorld().getName() : "NULL"));
        if (attempted > 0 && actuallyTracked == 0) {
            plugin.getLogger().severe("[MobMayhem] Wave " + wave.getWaveNumber()
                    + " spawned ZERO trackable mobs out of " + attempted + " attempts — "
                    + "check the warnings above/below for the cause (invalid spawn location, "
                    + "wrong world, or entities dying instantly after spawn).");
        }

        // Watch task — checks for wave complete every second
        long durationTicks = wave.getDurationSeconds() * 20L;
        long deadline = System.currentTimeMillis() + (wave.getDurationSeconds() * 1000L);

        watchTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (completed) return;

            // Prune mobs that died (some may have died without our listener
            // catching them — defensive cleanup)
            // A mob only counts as gone when it is really dead/removed. One that cannot be found right now
            // (chunk not loaded) gets 15 s of grace — otherwise the wave "clears" without any kill.
            state.getActiveMobs().removeIf(uuid -> {
                Entity e = Bukkit.getEntity(uuid);
                if (e == null) return missingTicks.merge(uuid, 1, Integer::sum) >= 15;
                missingTicks.remove(uuid);
                return e.isDead() || !e.isValid();
            });

            keepMobsInArenaAndOnPlayers();

            // All mobs dead? Wave complete.
            if (state.getActiveMobs().isEmpty()) {
                finish(true);
                return;
            }

            // All players eliminated? Team out.
            if (state.getAlivePlayers().isEmpty()) {
                finish(false);
                return;
            }

            // Blood Moon atmosphere: red dust raining near every alive player.
            if (bloodMoonActive) {
                for (UUID uuid : state.getAlivePlayers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) continue;
                    p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 2.2, 0),
                            8, 0.8, 0.4, 0.8, new Particle.DustOptions(Color.RED, 1.6f));
                }
            }

            // Time up? The wave is only cleared by killing every mob: at the timer the remaining mobs start
            // glowing so they can be hunted down; only at TWICE the duration is the wave force-ended.
            long nowMs = System.currentTimeMillis();
            if (nowMs >= deadline && !glowShown) {
                glowShown = true;
                PotionEffectType glow = lookup("glowing");
                for (UUID mobUuid : state.getActiveMobs()) {
                    Entity e = Bukkit.getEntity(mobUuid);
                    if (glow != null && e instanceof LivingEntity le)
                        le.addPotionEffect(new PotionEffect(glow, Integer.MAX_VALUE, 0, true, false, false));
                }
                for (UUID id : state.getAlivePlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendMessage(plugin.getKmcCore().getLanguageManager().tr(p, "mm.glow"));
                }
            }
            if (nowMs >= deadline + wave.getDurationSeconds() * 1000L) {
                if (plugin.getConfig().getBoolean("game.force-end-wave-on-timeout", true)) {
                    // Force-kill remaining mobs
                    for (UUID mobUuid : new ArrayList<>(state.getActiveMobs())) {
                        Entity e = Bukkit.getEntity(mobUuid);
                        if (e instanceof LivingEntity le) le.setHealth(0);
                    }
                    finish(state.getAlivePlayers().size() > 0);
                }
            }
        }, 20L, 20L);
    }

    /**
     * Once a second: (1) mobs that wandered off — spiders climbing out over the walls, anything that fell or
     * got pushed far away — are put back on a spawn point; (2) every second tick each mob is pointed at the
     * nearest living player, so all players (bots included) are attacked, not just the first one.
     */
    private void keepMobsInArenaAndOnPlayers() {
        Location ref = arena.getPlayerSpawn();
        if (ref == null || ref.getWorld() == null) return;
        double maxHeight = plugin.getConfig().getDouble("game.mob-max-height-above-spawn", 14);
        double maxRadius = plugin.getConfig().getDouble("game.mob-max-distance-from-spawn", 70);
        boolean retarget = (retargetCounter++ % 2) == 0;

        List<Player> alive = new ArrayList<>();
        for (UUID id : state.getAlivePlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && !p.isDead() && p.getWorld().equals(ref.getWorld())) alive.add(p);
        }

        for (UUID id : state.getActiveMobs()) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;

            Location at = le.getLocation();
            boolean tooHigh = at.getY() > ref.getY() + maxHeight;
            boolean tooFar  = at.getWorld() != null && at.getWorld().equals(ref.getWorld())
                    && Math.hypot(at.getX() - ref.getX(), at.getZ() - ref.getZ()) > maxRadius;
            boolean wrongWorld = at.getWorld() == null || !at.getWorld().equals(ref.getWorld());
            if (tooHigh || tooFar || wrongWorld) {
                Location back = arena.randomMobSpawn();
                if (back != null) { le.teleport(back); continue; }
            }

            if (retarget && le instanceof Mob mob && !alive.isEmpty()) {
                Player nearest = null;
                double best = Double.MAX_VALUE;
                for (Player p : alive) {
                    double d = p.getLocation().distanceSquared(at);
                    if (d < best) { best = d; nearest = p; }
                }
                if (nearest != null && !nearest.equals(mob.getTarget())) mob.setTarget(nearest);
            }
        }
    }

    /** Force-end the wave (called on game stop). */
    public void cancel() {
        if (watchTask != null) { watchTask.cancel(); watchTask = null; }
        // Kill all active mobs
        for (UUID mobUuid : state.getActiveMobs()) {
            Entity e = Bukkit.getEntity(mobUuid);
            if (e != null && !e.isDead()) e.remove();
        }
        state.getActiveMobs().clear();
        // Clear any blindness effects
        clearPlayerModifiers();
        endBloodMoon();
        completed = true;
    }

    // ----------------------------------------------------------------
    // Mob spawning + modifier application
    // ----------------------------------------------------------------

    private void spawnOne(EntityType type, Set<WaveModifier> modifiers) {
        Location spawn = arena.randomMobSpawn();
        if (spawn == null) {
            plugin.getLogger().warning("[MobMayhem] randomMobSpawn() returned null for team "
                    + state.getTeamId() + " — arena has no mob spawn points?");
            return;
        }

        World spawnWorld = arena.getPlayerSpawn().getWorld();
        if (spawn.getBlock().getType().isSolid()) {
            plugin.getLogger().severe("[MobMayhem] Mob spawn point " + fmt(spawn) + " for team "
                    + state.getTeamId() + " is INSIDE solid terrain (" + spawn.getBlock().getType()
                    + ") — re-run /mm addmobspawn there while standing in open air in the TEMPLATE world.");
        }
        Entity ent = spawnWorld.spawnEntity(spawn, type);
        if (ent == null) {
            plugin.getLogger().severe("[MobMayhem] spawnEntity(" + type + ") returned null at "
                    + fmt(spawn) + " in world '" + spawnWorld.getName() + "'.");
            return;
        }
        if (!ent.isValid() || ent.isDead()) {
            plugin.getLogger().warning("[MobMayhem] " + type + " spawned at " + fmt(spawn)
                    + " but was immediately invalid/dead (valid=" + ent.isValid()
                    + ", dead=" + ent.isDead() + "). Check for suffocation/void/lava at that spot.");
        }
        if (!(ent instanceof LivingEntity le)) {
            ent.remove();
            return;
        }

        // Tag with team id + wave number — lets DamageListener route kills
        var teamKey = new NamespacedKey(plugin, MOB_TEAM_KEY);
        var waveKey = new NamespacedKey(plugin, MOB_WAVE_KEY);
        le.getPersistentDataContainer().set(teamKey, PersistentDataType.STRING, state.getTeamId());
        le.getPersistentDataContainer().set(waveKey, PersistentDataType.INTEGER, wave.getWaveNumber());

        // Wave mobs must stay until they are killed: no despawning, and no dying to daylight (zombies and
        // skeletons burn in the sun, which "cleared" waves without a single kill).
        le.setRemoveWhenFarAway(false);
        PotionEffectType fireRes = lookup("fire_resistance");
        if (fireRes != null && type != EntityType.BLAZE && type != EntityType.MAGMA_CUBE) {
            le.addPotionEffect(new PotionEffect(fireRes, Integer.MAX_VALUE, 0, true, false, false));
        }
        if (wave.isBossWave() && isBossEntity(type)) {
            le.getPersistentDataContainer().set(new NamespacedKey(plugin, MOB_BOSS_KEY), PersistentDataType.INTEGER, 1);
        }

        // Apply modifiers
        if (modifiers.contains(WaveModifier.SPEED_MOBS)) {
            PotionEffectType speedType = lookup("speed");
            if (speedType != null) {
                le.addPotionEffect(new PotionEffect(speedType, Integer.MAX_VALUE, 1, true, false, false));
            }
        }
        if (modifiers.contains(WaveModifier.HEALTHY_MOBS)) {
            var maxHealth = le.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(maxHealth.getBaseValue() * 2);
                le.setHealth(maxHealth.getBaseValue());
            }
        }
        if (modifiers.contains(WaveModifier.BLOOD_MOON)) {
            var maxHealth = le.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(maxHealth.getBaseValue() * 3);
                le.setHealth(maxHealth.getBaseValue());
            }
            PotionEffectType speedType = lookup("speed");
            if (speedType != null) {
                le.addPotionEffect(new PotionEffect(speedType, Integer.MAX_VALUE, 0, true, false, false));
            }
        }
        // Boss extra HP
        if (wave.isBossWave()) {
            var maxHealth = le.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && isBossEntity(type)) {
                maxHealth.setBaseValue(maxHealth.getBaseValue() * 1.5);
                le.setHealth(maxHealth.getBaseValue());
                le.setCustomName(ChatColor.RED + "" + ChatColor.BOLD + "★ BOSS ★");
                le.setCustomNameVisible(true);
            }
        }

        // Make hostile mobs target the team's players
        if (le instanceof Mob mob && !state.getAlivePlayers().isEmpty()) {
            UUID firstPlayer = state.getAlivePlayers().iterator().next();
            Player target = Bukkit.getPlayer(firstPlayer);
            if (target != null) mob.setTarget(target);
        }

        state.addMob(le.getUniqueId());
    }

    private static String fmt(Location l) {
        return "(" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ")";
    }

    private boolean isBossEntity(EntityType type) {
        return type == EntityType.IRON_GOLEM
            || type == EntityType.RAVAGER
            || type == EntityType.WITHER
            || type == EntityType.ENDER_DRAGON;
    }

    private void applyPlayerModifiers(Set<WaveModifier> modifiers) {
        if (modifiers.contains(WaveModifier.LOW_VISIBILITY)) {
            PotionEffectType blind = lookup("blindness");
            if (blind != null) {
                int duration = wave.getDurationSeconds() * 20;
                for (UUID uuid : state.getAlivePlayers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) p.addPotionEffect(new PotionEffect(blind, duration, 0, true, false, false));
                }
            }
        }
    }

    private void clearPlayerModifiers() {
        PotionEffectType blind = lookup("blindness");
        if (blind != null) {
            for (UUID uuid : state.getAllPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.removePotionEffect(blind);
            }
        }
    }

    // ----------------------------------------------------------------
    // Modifier roll
    // ----------------------------------------------------------------

    private Set<WaveModifier> pickModifiers() {
        Set<WaveModifier> result = new HashSet<>();
        var cfg = plugin.getConfig();

        // Blood Moon is rolled separately — rarer and more dramatic than the
        // regular modifier pool, so it isn't crowded out by common ones.
        double bloodMoonChance = cfg.getDouble("modifiers.blood-moon-chance", 0.08);
        if (Math.random() < bloodMoonChance) result.add(WaveModifier.BLOOD_MOON);

        double chance = cfg.getDouble("modifiers.chance", 0.4);
        if (Math.random() >= chance) return result;

        List<WaveModifier> pool = new ArrayList<>();
        for (String name : cfg.getStringList("modifiers.enabled")) {
            try { pool.add(WaveModifier.valueOf(name.toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }
        if (pool.isEmpty()) return result;

        WaveModifier first = pool.get((int) (Math.random() * pool.size()));
        result.add(first);

        // Second modifier?
        double secondChance = cfg.getDouble("modifiers.second-chance", 0.15);
        if (Math.random() < secondChance) {
            pool.remove(first);
            if (!pool.isEmpty()) {
                result.add(pool.get((int) (Math.random() * pool.size())));
            }
        }
        return result;
    }

    private void announceWaveStart(Set<WaveModifier> modifiers) {
        StringBuilder mods = new StringBuilder();
        if (!modifiers.isEmpty()) {
            mods.append(" &7[");
            int i = 0;
            for (WaveModifier m : modifiers) {
                if (i > 0) mods.append("&7, ");
                mods.append(m.shortLabel);
                i++;
            }
            mods.append("&7]");
        }
        for (UUID uuid : state.getAllPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            var lm = plugin.getKmcCore().getLanguageManager();
            String title = lm.tr(p, "mm.wave.title", wave.getWaveNumber()) + (wave.isBossWave() ? " ★" : "");
            String subtitle = ChatColor.YELLOW + wave.getDisplayName();
            p.sendTitle(title, subtitle, 10, 60, 20);
            p.playSound(p.getLocation(),
                    wave.isBossWave() ? Sound.ENTITY_ENDER_DRAGON_GROWL : Sound.ENTITY_WITHER_SPAWN,
                    1f, 1f);
            p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    lm.tr(p, "mm.wave.line", wave.getWaveNumber(), wave.getDisplayName()) + mods));
        }
    }

    // ----------------------------------------------------------------
    // Completion
    // ----------------------------------------------------------------

    private void finish(boolean survived) {
        if (completed) return;
        completed = true;
        if (watchTask != null) { watchTask.cancel(); watchTask = null; }
        clearPlayerModifiers();
        endBloodMoon();
        state.completeWave();
        onComplete.accept(survived);
    }

    // ----------------------------------------------------------------
    // Blood Moon — rare, dramatic wave modifier: dark stormy sky,
    // ×3 HP + permanent Speed I on every mob, red dust raining on players.
    // ----------------------------------------------------------------

    private void startBloodMoon() {
        World world = arena.getPlayerSpawn().getWorld();
        if (world == null) return;
        bloodMoonWorld     = world;
        bloodMoonPrevTime  = world.getTime();
        bloodMoonPrevStorm = world.hasStorm();
        world.setTime(18000L); // midnight
        world.setStorm(true);
        world.setThundering(true);
        bloodMoonActive = true;

        for (UUID uuid : state.getAllPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            var lm = plugin.getKmcCore().getLanguageManager();
            p.sendTitle(lm.tr(p, "mm.bloodmoon.title"), lm.tr(p, "mm.bloodmoon.sub"), 10, 60, 20);
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.6f);
            p.playSound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 0.5f);
        }
    }

    private void endBloodMoon() {
        if (!bloodMoonActive || bloodMoonWorld == null) return;
        bloodMoonWorld.setStorm(bloodMoonPrevStorm);
        bloodMoonWorld.setThundering(false);
        bloodMoonWorld.setTime(bloodMoonPrevTime);
        bloodMoonActive = false;
        bloodMoonWorld  = null;
    }

    private PotionEffectType lookup(String key) {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(key)); }
        catch (Exception e) { return null; }
    }
}
