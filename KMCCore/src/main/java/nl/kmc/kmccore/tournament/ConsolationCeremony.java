package nl.kmc.kmccore.tournament;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.TeamJingles;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The last-place ritual: the team at the bottom of the table gets the Wooden Spoon — a title, a gentle
 * roast in chat, a sad little descending tune, a rain cloud over their heads and a spoon item each.
 * Meant as a warm joke, not a punishment; switch it off with {@code consolation.enabled: false}.
 */
public final class ConsolationCeremony {

    private ConsolationCeremony() {}

    /** Plays the ritual and calls {@code done} when it is over (immediately when there is nobody to roast). */
    public static void run(KMCCore plugin, Runnable done) {
        if (!plugin.getConfig().getBoolean("consolation.enabled", true)) { done.run(); return; }
        List<KMCTeam> standings = plugin.getTournamentManager().getFinalStandings();
        if (standings.size() < 3) { done.run(); return; }   // with two teams "last" is just "second"
        KMCTeam last = standings.get(standings.size() - 1);
        if (last.getMembers().isEmpty()) { done.run(); return; }

        var lm = plugin.getLanguageManager();
        String name = last.getColor() + last.getDisplayName();
        int variant = new Random().nextInt(3) + 1;

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage("");
            p.sendMessage("§8§m                                        ");
            p.sendMessage("        " + lm.tr(p, "consolation.title"));
            p.sendMessage("  " + lm.tr(p, "consolation.line." + variant, name + "§7"));
            p.sendMessage("  " + lm.tr(p, "consolation.sub", last.getPoints()));
            p.sendMessage("§8§m                                        ");
            p.sendTitle(lm.tr(p, "consolation.title"), name, 10, 70, 20);
        }

        // The sad little tune: five notes, falling.
        int[] notes = {12, 10, 8, 6, 1};
        for (int i = 0; i < notes.length; i++) {
            final float pitch = TeamJingles.pitch(notes[i]);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers())
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, pitch);
            }, 10L + i * 8L);
        }

        // A rain cloud over each member, and the spoon in their hands.
        for (UUID u : last.getMembers()) {
            Player m = Bukkit.getPlayer(u);
            if (m == null) continue;
            ItemStack spoon = new ItemStack(Material.WOODEN_SHOVEL);
            ItemMeta meta = spoon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(lm.tr(m, "consolation.item"));
                meta.setLore(List.of(lm.tr(m, "consolation.item-lore")));
                spoon.setItemMeta(meta);
            }
            var left = m.getInventory().addItem(spoon);
            if (!left.isEmpty()) m.getWorld().dropItem(m.getLocation(), spoon);
            for (int t = 0; t < 6; t++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!m.isOnline()) return;
                    m.getWorld().spawnParticle(Particle.CLOUD, m.getLocation().add(0, 2.6, 0), 6, 0.5, 0.1, 0.5, 0);
                    m.getWorld().spawnParticle(Particle.FALLING_WATER, m.getLocation().add(0, 2.4, 0), 14, 0.5, 0.1, 0.5, 0);
                }, t * 10L);
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, done, Math.max(5, plugin.getConfig().getInt("consolation.seconds", 8)) * 20L);
    }
}
