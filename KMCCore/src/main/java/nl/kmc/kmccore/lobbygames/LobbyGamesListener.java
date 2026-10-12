package nl.kmc.kmccore.lobbygames;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Darts (snowballs at target blocks), the fishing contest, and cleaning up when somebody leaves. */
public final class LobbyGamesListener implements Listener {

    private final KMCCore plugin;
    private final LobbyGamesManager mgr;
    private final Random rng = new Random();

    public LobbyGamesListener(KMCCore plugin, LobbyGamesManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    // ── Darts ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDartHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball ball)) return;
        if (!(ball.getShooter() instanceof Player p)) return;
        ItemStack stack = ball.getItem();
        if (stack == null || !stack.hasItemMeta()
                || !stack.getItemMeta().getPersistentDataContainer().has(mgr.dartKey, PersistentDataType.BYTE)) return;
        var lm = plugin.getLanguageManager();
        if (e.getHitBlock() == null || !mgr.isBoard(e.getHitBlock().getLocation())) {
            p.sendActionBar(net.kyori.adventure.text.Component.text(lm.tr(p, "lobbygames.darts.miss")));
            return;
        }
        if (!mgr.available(p)) return;

        BlockFace face = e.getHitBlockFace();
        Location c = e.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
        Location hit = ball.getLocation();
        double dx = hit.getX() - c.getX(), dy = hit.getY() - c.getY(), dz = hit.getZ() - c.getZ();
        if (face != null) {            // only the offset within the face counts
            if (face.getModX() != 0) dx = 0;
            if (face.getModY() != 0) dy = 0;
            if (face.getModZ() != 0) dz = 0;
        }
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int score = (int) Math.max(1, Math.min(10, Math.ceil(10 * (1 - d / 0.71))));

        int[] r = mgr.dart(p, score);
        hit.getWorld().spawnParticle(score >= 9 ? Particle.CRIT : Particle.SMOKE, hit, 8, 0.1, 0.1, 0.1, 0.02);
        p.playSound(p.getLocation(), score >= 9 ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_HAT,
                1f, score >= 9 ? 1.6f : 1.0f);
        int per = Math.max(1, plugin.getConfig().getInt("lobby-games.darts.per-turn", 5));
        p.sendActionBar(net.kyori.adventure.text.Component.text(
                lm.tr(p, "lobbygames.darts.throw", score, r[0], per, r[1])));
        if (r[2] == 1) {
            p.sendMessage(lm.tr(p, "lobbygames.darts.turn-done", r[1], r[4]));
            if (r[3] == 1) p.sendMessage(lm.tr(p, "lobbygames.darts.new-best", r[1]));
        }
    }

    /** The five darts of /darts. */
    public ItemStack dartStack(Player p) {
        int n = Math.max(1, plugin.getConfig().getInt("lobby-games.darts.per-turn", 5));
        ItemStack s = new ItemStack(Material.SNOWBALL, n);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(plugin.getLanguageManager().tr(p, "lobbygames.darts.item"));
        m.getPersistentDataContainer().set(mgr.dartKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    // ── Fishing ───────────────────────────────────────────────────────────────

    private static final List<Material> FISH = List.of(Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);
    private static final List<String> FISH_NL = List.of("Kabeljauw", "Zalm", "Tropische vis", "Kogelvis");
    private static final List<String> FISH_EN = List.of("Cod", "Salmon", "Tropical fish", "Pufferfish");

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!plugin.getConfig().getBoolean("lobby-games.fishing.enabled", true)) return;
        Player p = e.getPlayer();
        if (!mgr.available(p)) return;
        if (!(e.getCaught() instanceof Item caught)) return;

        int idx = rng.nextInt(FISH.size());
        // Skewed towards small catches: most are 0.3-1.5 kg, a few monsters up to 6 kg.
        double kg = 0.3 + 5.7 * Math.pow(rng.nextDouble(), 3);
        kg = Math.round(kg * 100) / 100.0;
        double serverRecord = mgr.serverFishRecord();
        boolean personalBest = mgr.fishCaught(p, kg);

        var lm = plugin.getLanguageManager();
        boolean nl = !lm.getLanguage(p.getUniqueId()).equalsIgnoreCase("en");
        String fishName = nl ? FISH_NL.get(idx) : FISH_EN.get(idx);
        ItemStack stack = new ItemStack(FISH.get(idx));
        ItemMeta m = stack.getItemMeta();
        m.setDisplayName("§b" + fishName + " §7— §e" + String.format(Locale.ROOT, "%.2f", kg) + " kg");
        stack.setItemMeta(m);
        caught.setItemStack(stack);
        e.setExpToDrop(0);

        p.sendMessage(lm.tr(p, "lobbygames.fish.caught", fishName, String.format(Locale.ROOT, "%.2f", kg)));
        if (kg > serverRecord && kg >= 1.0) {
            for (Player v : Bukkit.getOnlinePlayers())
                v.sendMessage(lm.tr(v, "lobbygames.fish.record", p.getName(), String.format(Locale.ROOT, "%.2f", kg)));
        } else if (personalBest) {
            p.sendMessage(lm.tr(p, "lobbygames.fish.pb"));
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mgr.forfeit(e.getPlayer());
    }
}
