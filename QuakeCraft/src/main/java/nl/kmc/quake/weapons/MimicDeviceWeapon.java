package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.Sfx;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Mimic Device — visual deception. Temporarily dresses the player in another
 * team's coloured leather so they read as that team from a distance.
 *
 * <p><b>Purely cosmetic.</b> The player's real team, kills and points are
 * untouched — only their displayed armour changes, then reverts. Great for
 * bluffing your way past enemies in the chaos.
 */
public final class MimicDeviceWeapon {

    private MimicDeviceWeapon() {}

    public static boolean activate(QuakeCraftPlugin plugin, Player player, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.mimic_device.cooldown-ms", 2000);
        if (!state.canShoot(cd)) return false;

        var teamManager = plugin.getKmcCore().getTeamManager();
        var myTeam = teamManager.getTeamByPlayer(player.getUniqueId());

        // Candidate teams to mimic (config allow-list, or all other teams).
        List<String> allowed = plugin.getConfig().getStringList("powerups.mimic_device.allowed-targets");
        List<Object[]> candidates = new ArrayList<>(); // [id, ChatColor]
        for (var team : teamManager.getAllTeams()) {
            if (myTeam != null && team.getId().equals(myTeam.getId())) continue;
            if (!allowed.isEmpty() && !allowed.contains(team.getId())) continue;
            candidates.add(new Object[]{ team.getDisplayName(), team.getColor() });
        }
        if (candidates.isEmpty()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.GRAY + "Geen team om na te doen!"));
            return false;
        }

        state.markShot();
        Object[] pick = candidates.get((int) (Math.random() * candidates.size()));
        String mimicName  = (String) pick[0];
        Color  mimicColor = toColor((ChatColor) pick[1]);

        // Save current armour, equip dyed leather in the mimicked colour.
        ItemStack[] original = player.getInventory().getArmorContents().clone();
        player.getInventory().setArmorContents(new ItemStack[]{
                dyed(Material.LEATHER_BOOTS,     mimicColor),
                dyed(Material.LEATHER_LEGGINGS,  mimicColor),
                dyed(Material.LEATHER_CHESTPLATE,mimicColor),
                dyed(Material.LEATHER_HELMET,    mimicColor)
        });

        Sfx.play(plugin, player.getLocation(), "mimic_device.activate", Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1f);
        player.getWorld().spawnParticle(Particle.WITCH, player.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0);
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                ChatColor.LIGHT_PURPLE + "Vermomd als " + ChatColor.stripColor(mimicName) + "!"));

        int durationSec = plugin.getConfig().getInt("powerups.mimic_device.duration-seconds", 12);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.getInventory().setArmorContents(original);
            Sfx.play(plugin, player.getLocation(), "mimic_device.deactivate", Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.2f);
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.GRAY + "Vermomming uitgewerkt."));
        }, durationSec * 20L);

        return true;
    }

    private static ItemStack dyed(Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Color toColor(ChatColor c) {
        return switch (c) {
            case RED, DARK_RED        -> Color.RED;
            case GOLD                 -> Color.ORANGE;
            case YELLOW               -> Color.YELLOW;
            case GREEN, DARK_GREEN    -> Color.LIME;
            case AQUA, DARK_AQUA      -> Color.AQUA;
            case BLUE, DARK_BLUE      -> Color.BLUE;
            case DARK_PURPLE          -> Color.PURPLE;
            case LIGHT_PURPLE         -> Color.FUCHSIA;
            case WHITE, GRAY          -> Color.WHITE;
            case BLACK, DARK_GRAY     -> Color.GRAY;
            default                   -> Color.WHITE;
        };
    }
}
