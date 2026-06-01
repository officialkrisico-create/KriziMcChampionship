package nl.kmc.mayhem.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.managers.MobMayhemGameManagerV2;
import nl.kmc.mayhem.managers.WaveExecutor;
import nl.kmc.mayhem.models.TeamGameState;
import nl.kmc.mayhem.waves.WaveLibrary;
import nl.kmc.mayhem.waves.WaveModifier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Routes mob/player death events to the V2 game manager.
 *
 * <p>Mobs spawned by Mob Mayhem are tagged with team id + wave number
 * via PersistentDataContainer. When they die, this listener reads the
 * tag and forwards the kill to the right team's score.
 *
 * <p>Also implements the on-event modifier effects:
 * <ul>
 *   <li>EXPLOSIVE_MOBS — mob explodes on death</li>
 *   <li>POISON_TOUCH — mob applies poison when it hits a player</li>
 * </ul>
 */
public class MobListener implements Listener {

    private final MobMayhemPlugin plugin;

    public MobListener(MobMayhemPlugin plugin) { this.plugin = plugin; }

    private MobMayhemGameManagerV2 gm() { return plugin.getGameManagerV2(); }

    /** Find the TeamGameState for a given player UUID across all teams. */
    private TeamGameState getTeamStateForPlayer(java.util.UUID uuid) {
        MobMayhemGameManagerV2 gm = gm();
        if (gm == null) return null;
        for (TeamGameState ts : gm.getTeamStates().values()) {
            if (ts.getAllPlayers().contains(uuid)) return ts;
        }
        return null;
    }

    // ----------------------------------------------------------------
    // Mob death — read tags, route to GameManager, modifier effects
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        MobMayhemGameManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;

        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;  // PlayerDeathEvent handles those

        var teamKey = new NamespacedKey(plugin, WaveExecutor.MOB_TEAM_KEY);
        var waveKey = new NamespacedKey(plugin, WaveExecutor.MOB_WAVE_KEY);
        var pdc = entity.getPersistentDataContainer();

        if (!pdc.has(teamKey, PersistentDataType.STRING)) return;

        String teamId   = pdc.get(teamKey, PersistentDataType.STRING);
        Integer waveNum = pdc.get(waveKey, PersistentDataType.INTEGER);
        if (waveNum == null) waveNum = 0;

        TeamGameState ts = gm.getTeamStates().get(teamId);
        if (ts == null) return;

        // Check if this mob's modifiers include EXPLOSIVE_MOBS — if so, boom on death
        if (ts.getActiveModifiers().contains(WaveModifier.EXPLOSIVE_MOBS)) {
            entity.getWorld().createExplosion(entity.getLocation(), 2.0f, false, false, entity);
        }

        // Untrack from team state
        ts.removeMob(entity.getUniqueId());

        // Route kill to V2 manager
        Player killer = entity.getKiller();
        boolean wasBoss = ts.getCurrentWave() > 0
                && !WaveLibrary.defaultWaves().isEmpty()
                && ts.getCurrentWave() <= WaveLibrary.defaultWaves().size()
                && WaveLibrary.defaultWaves().get(ts.getCurrentWave() - 1).isBossWave();
        int mobPoints = WaveLibrary.defaultPointsForKill(entity.getType(), wasBoss);

        if (killer != null) {
            gm.onMobKill(killer.getUniqueId(), mobPoints);
        }

        // Strip XP drops — keeps things clean
        event.setDroppedExp(0);
        // Strip vanilla loot — Mob Mayhem controls drops via wave-end loot
        event.getDrops().clear();
    }

    // ----------------------------------------------------------------
    // Player death — eliminate from team
    // ----------------------------------------------------------------

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        MobMayhemGameManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
        Player p = event.getEntity();
        TeamGameState ts = getTeamStateForPlayer(p.getUniqueId());
        if (ts == null) return;

        // Don't fire respawn animation — we'll handle it
        event.deathMessage(null);

        ts.eliminatePlayer(p.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        Bukkit.broadcastMessage(org.bukkit.ChatColor.RED + "☠ " + org.bukkit.ChatColor.GRAY
                + p.getName() + " is uitgeschakeld op wave " + ts.getCurrentWave());
    }

    // ----------------------------------------------------------------
    // Mob hit player — POISON_TOUCH modifier
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMobHitsPlayer(EntityDamageByEntityEvent event) {
        MobMayhemGameManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (!(event.getDamager() instanceof LivingEntity damager)) return;
        if (damager instanceof Player) return;  // ignore PvP

        var teamKey = new NamespacedKey(plugin, WaveExecutor.MOB_TEAM_KEY);
        var pdc = damager.getPersistentDataContainer();
        if (!pdc.has(teamKey, PersistentDataType.STRING)) return;

        String teamId = pdc.get(teamKey, PersistentDataType.STRING);
        TeamGameState ts = gm.getTeamStates().get(teamId);
        if (ts == null) return;

        if (ts.getActiveModifiers().contains(WaveModifier.POISON_TOUCH)) {
            PotionEffectType poison = lookup("poison");
            if (poison != null) {
                p.addPotionEffect(new PotionEffect(poison, 100, 0, true, true, true));
            }
        }
    }

    private PotionEffectType lookup(String key) {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(key)); }
        catch (Exception e) { return null; }
    }
}
