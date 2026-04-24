package nl.kmc.luckyblock.util;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import nl.kmc.luckyblock.models.LootEntry;
import nl.kmc.kmccore.api.KMCApi;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Applies a {@link LootEntry} to a player at a given location.
 *
 * <p>All side effects (spawning mobs, explosions, etc.) happen here.
 * This class is stateless — construct once and call {@link #execute} repeatedly.
 */
public class LootExecutor {

    private final LuckyBlockPlugin plugin;
    private final Random           random = new Random();

    public LootExecutor(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Executes a loot entry for the given player at the given location.
     *
     * @param entry    the loot to apply
     * @param player   the player who broke the lucky block
     * @param location the location where the block was
     */
    public void execute(LootEntry entry, Player player, Location location) {
        KMCApi api = plugin.getKmcCore().getApi();

        // Show message to the player
        if (entry.getMessage() != null && !entry.getMessage().isBlank()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', entry.getMessage()));
        }

        // Play open sound
        location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);

        switch (entry.getType()) {

            case ITEM -> {
                ItemStack stack = new ItemStack(entry.getItem(), entry.getAmount());
                if (entry.getEnchants() != null && !entry.getEnchants().isEmpty()) {
                    ItemMeta meta = stack.getItemMeta();
                    for (Map.Entry<Enchantment, Integer> e : entry.getEnchants().entrySet()) {
                        meta.addEnchant(e.getKey(), e.getValue(), true);
                    }
                    stack.setItemMeta(meta);
                }
                // Give item or drop at feet
                giveOrDrop(player, stack);
                spawnParticles(location, Particle.HAPPY_VILLAGER, 20);
            }

            case EFFECT -> {
                int durationTicks = entry.getDurationSeconds() * 20;
                player.addPotionEffect(new PotionEffect(
                        entry.getPotionEffect(), durationTicks, entry.getAmplifier()));
                spawnParticles(location, Particle.ENTITY_EFFECT, 30);
            }

            case EXPLOSION -> {
                location.getWorld().createExplosion(location,
                        entry.getExplosionPower(), true, true, player);
                spawnParticles(location, Particle.EXPLOSION, 5);
            }

            case LIGHTNING -> {
                location.getWorld().strikeLightning(location);
                spawnParticles(location, Particle.EXPLOSION, 10);
            }

            case MOB -> {
                for (int i = 0; i < entry.getMobCount(); i++) {
                    location.getWorld().spawnEntity(
                            location.clone().add(random.nextInt(3) - 1, 0, random.nextInt(3) - 1),
                            entry.getMobType());
                }
                spawnParticles(location, Particle.LARGE_SMOKE, 20);
            }

            case COINS -> {
                // Coins removed from system — converted to points
                api.givePoints(player.getUniqueId(), entry.getRewardAmount());
                spawnParticles(location, Particle.TOTEM_OF_UNDYING, 20);
            }

            case POINTS -> {
                api.givePoints(player.getUniqueId(), entry.getRewardAmount());
                spawnParticles(location, Particle.ENCHANT, 20);
            }

            case INSTANT_KILL -> {
                // Kill the player — their elimination is handled by PlayerDeathListener
                location.getWorld().strikeLightningEffect(location);
                player.setHealth(0);
            }

            case FULL_ARMOR -> {
                Material base = entry.getArmorMaterial();
                String prefix = base.name().split("_")[0]; // e.g. DIAMOND
                giveOrDrop(player, new ItemStack(Material.valueOf(prefix + "_HELMET")));
                giveOrDrop(player, new ItemStack(Material.valueOf(prefix + "_CHESTPLATE")));
                giveOrDrop(player, new ItemStack(Material.valueOf(prefix + "_LEGGINGS")));
                giveOrDrop(player, new ItemStack(Material.valueOf(prefix + "_BOOTS")));
                spawnParticles(location, Particle.FIREWORK, 40);
            }

            case TNT_RAIN -> {
                for (int i = 0; i < entry.getTntCount(); i++) {
                    Location above = location.clone().add(
                            random.nextInt(7) - 3, 8 + random.nextInt(4), random.nextInt(7) - 3);
                    TNTPrimed tnt = (TNTPrimed) above.getWorld().spawnEntity(above,
                            org.bukkit.entity.EntityType.TNT);
                    tnt.setFuseTicks(40 + random.nextInt(20)); // 2-3 second fuse
                }
                spawnParticles(location, Particle.EXPLOSION, 30);
            }

            case TELEPORT_RANDOM -> {
                List<Location> spawns = plugin.getKmcCore().getArenaManager().getSoloSpawns("lucky_block");
                if (!spawns.isEmpty()) {
                    Location dest = spawns.get(random.nextInt(spawns.size()));
                    spawnParticles(player.getLocation(), Particle.PORTAL, 30);
                    player.teleport(dest);
                    spawnParticles(dest, Particle.PORTAL, 30);
                }
            }

//            case SWAP_POSITION -> {
//                // Find a random other alive player in the game
//                List<Player> others = new ArrayList<>(
//                        plugin.getGameState().getAlivePlayers());
//                others.remove(player);
//                if (!others.isEmpty()) {
//                    Player other = others.get(random.nextInt(others.size()));
//                    Location myLoc    = player.getLocation().clone();
//                    Location theirLoc = other.getLocation().clone();
//                    spawnParticles(myLoc,    Particle.PORTAL, 20);
//                    spawnParticles(theirLoc, Particle.PORTAL, 20);
//                    player.teleport(theirLoc);
//                    other.teleport(myLoc);
//                    other.sendMessage(ChatColor.LIGHT_PURPLE + "🔀 Je bent gewisseld van positie!");
//                }
//            }
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        for (ItemStack drop : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private void spawnParticles(Location loc, Particle particle, int count) {
        try {
            loc.getWorld().spawnParticle(particle, loc.clone().add(0.5, 0.5, 0.5), count);
        } catch (Exception ignored) {
            // Some particles need extra data — silently skip if unsupported
        }
    }
}
