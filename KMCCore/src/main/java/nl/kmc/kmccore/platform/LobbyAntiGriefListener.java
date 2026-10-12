package nl.kmc.kmccore.platform;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;

/**
 * The part of lobby protection that {@code LobbyProtectionListener} does not cover: item frames, paintings,
 * armor stands, buckets, fire, farmland, boats and placed entities, containers, thrown pearls. Applies only in
 * the lobby zone while no game is running, and never to players with {@code kmc.lobby.build}.
 */
public final class LobbyAntiGriefListener implements Listener {

    private final KMCCore plugin;

    public LobbyAntiGriefListener(KMCCore plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("anti-grief.enabled", true); }

    private boolean gameRunning() {
        return plugin.getGameManager().isGameActive() || nl.kmc.core.RunningGames.anyRunning();
    }

    private boolean inLobby(Location loc) {
        Location lobby = plugin.getArenaManager().getLobby();
        if (lobby == null || loc == null || loc.getWorld() == null || !loc.getWorld().equals(lobby.getWorld())) return false;
        double r = plugin.getConfig().getDouble("arena.lobby-protection-radius", 100.0);
        return loc.distanceSquared(lobby) <= r * r;
    }

    /** True when this player should be stopped at {@code loc}. */
    private boolean protectedFrom(Player p, Location loc) {
        return on() && !gameRunning() && inLobby(loc) && !p.hasPermission("kmc.lobby.build");
    }

    private static boolean decor(Entity e) {
        return e instanceof Hanging || e instanceof ArmorStand || e.getType() == EntityType.ITEM_DISPLAY
                || e.getType() == EntityType.GLOW_ITEM_FRAME;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && protectedFrom(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitDecor(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && decor(e.getEntity()) && protectedFrom(p, e.getEntity().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (decor(e.getRightClicked()) && protectedFrom(e.getPlayer(), e.getRightClicked().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (protectedFrom(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (protectedFrom(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (protectedFrom(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!on() || gameRunning() || !inLobby(e.getBlock().getLocation())) return;
        if (e.getPlayer() == null || !e.getPlayer().hasPermission("kmc.lobby.build")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (on() && !gameRunning() && inLobby(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (on() && !gameRunning() && e.getSource().getType() == Material.FIRE && inLobby(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (on() && !gameRunning() && !(e.getEntity() instanceof Player) && inLobby(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceEntity(EntityPlaceEvent e) {
        if (e.getPlayer() != null && protectedFrom(e.getPlayer(), e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent e) {
        if (e.getAttacker() instanceof Player p && protectedFrom(p, e.getVehicle().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getClickedBlock() == null || !protectedFrom(p, e.getClickedBlock().getLocation())) return;
        Material m = e.getClickedBlock().getType();
        if (e.getAction() == Action.PHYSICAL) {
            if (m == Material.FARMLAND || m == Material.TURTLE_EGG) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.getConfig().getBoolean("anti-grief.block-containers", true) && isContainer(m)) {
            e.setCancelled(true);
            return;
        }
        // Items that change the world when used on a block.
        var hand = e.getItem() != null ? e.getItem().getType() : Material.AIR;
        String n = hand.name();
        if (n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT") || n.endsWith("_MINECART") || n.endsWith("_RAFT")
                || hand == Material.FLINT_AND_STEEL || hand == Material.FIRE_CHARGE || hand == Material.BONE_MEAL
                || hand == Material.ARMOR_STAND || hand == Material.ITEM_FRAME || hand == Material.PAINTING
                || hand == Material.GLOW_ITEM_FRAME || hand.name().endsWith("_HOE") || hand.name().endsWith("_SHOVEL")
                || hand.name().endsWith("_AXE")) {
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    private static boolean isContainer(Material m) {
        String n = m.name();
        return n.endsWith("CHEST") || n.equals("BARREL") || n.endsWith("SHULKER_BOX") || n.equals("FURNACE")
                || n.equals("BLAST_FURNACE") || n.equals("SMOKER") || n.equals("HOPPER") || n.equals("DISPENSER")
                || n.equals("DROPPER") || n.equals("BREWING_STAND") || n.equals("LECTERN") || n.equals("CHISELED_BOOKSHELF");
    }

    /** Ender pearls would let people leave the lobby area (and its protections). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p) || e.getEntityType() != EntityType.ENDER_PEARL) return;
        if (plugin.getConfig().getBoolean("anti-grief.block-ender-pearls", true) && protectedFrom(p, p.getLocation()))
            e.setCancelled(true);
    }
}
