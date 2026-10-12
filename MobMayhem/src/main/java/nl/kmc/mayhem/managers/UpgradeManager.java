package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.TeamGameState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Roguelike upgrades between waves: after a team clears a wave every surviving player picks
 * 1 of 3 random perks (better weapon, armour, extra hearts, speed, bow, a revive, ...). Picks stack
 * for the rest of the game, so later waves get easier to handle as the team grows stronger.
 */
public final class UpgradeManager implements Listener {

    public enum Perk {
        WEAPON  ("Sterker wapen",   Material.IRON_SWORD,        3, "Je zwaard en bijl worden een tier beter (hout → steen → ijzer → diamant)."),
        ARMOR   ("Sterker harnas",  Material.IRON_CHESTPLATE,   3, "Je hele harnas wordt een tier beter (leer → maliën → ijzer → diamant)."),
        SHARP   ("Scherpte",        Material.ENCHANTED_BOOK,    3, "Je zwaard krijgt Sharpness (+1 per keer)."),
        HEARTS  ("Extra harten",    Material.GOLDEN_APPLE,      3, "+2 harten maximale gezondheid en je wordt direct genezen."),
        SPEED   ("Snelle voeten",   Material.SUGAR,             2, "Permanente snelheid voor de rest van de game."),
        BOW     ("Boogschutter",    Material.BOW,               3, "Betere boog (Power) en 32 extra pijlen."),
        FEAST   ("Festijn",         Material.COOKED_BEEF,       99, "6 gouden appels en 16 gekookte biefstuk."),
        MEDIC   ("Medic",           Material.GLISTERING_MELON_SLICE, 99, "Volledig genezen + 4 extra absorptie-harten voor de volgende golf."),
        REVIVE  ("Wederopstanding", Material.TOTEM_OF_UNDYING,  99, "Een gevallen teamgenoot komt terug in het gevecht.");

        final String name; final Material icon; final int max; final String desc;
        Perk(String name, Material icon, int max, String desc) { this.name = name; this.icon = icon; this.max = max; this.desc = desc; }
    }

    private static final String TITLE = "§4§lKies je upgrade";
    private static final String[] WEAPON_TIERS = {"WOODEN", "STONE", "IRON", "DIAMOND"};
    private static final String[] ARMOR_TIERS  = {"LEATHER", "CHAINMAIL", "IRON", "DIAMOND"};

    private final MobMayhemPlugin plugin;
    private final Random rng = new Random();

    /** Stacks taken this game, per player. */
    private final Map<UUID, Map<Perk, Integer>> stacks = new HashMap<>();
    /** Open offers: player → the three perks on offer. */
    private final Map<UUID, List<Perk>> open = new HashMap<>();
    /** Teams play independently, so each has its own offer (players still choosing + timeout + continuation). */
    private final Map<String, Offer> offers = new HashMap<>();
    private final Map<UUID, Offer> offerOf = new HashMap<>();
    private Consumer<TeamGameState> reviver;

    private static final class Offer {
        final TeamGameState ts;
        final Set<UUID> pending = new HashSet<>();
        BukkitTask timeout;
        Runnable done;
        Offer(TeamGameState ts, Runnable done) { this.ts = ts; this.done = done; }
    }

    public UpgradeManager(MobMayhemPlugin plugin) { this.plugin = plugin; }

    /** Hook that revives one fallen teammate of the given team (supplied by the game manager). */
    public void setReviver(Consumer<TeamGameState> reviver) { this.reviver = reviver; }

    // ── Offer flow ────────────────────────────────────────────────────────────

    /**
     * Opens the upgrade menu for every living online player of {@code ts}. {@code done} runs once everybody
     * has picked, or when {@code seconds} have passed (undecided players get a random pick).
     */
    public void offer(TeamGameState ts, int waveCleared, int seconds, Runnable done) {
        Offer old = offers.remove(ts.getTeamId());
        if (old != null && old.timeout != null) old.timeout.cancel();
        Offer offer = new Offer(ts, done);
        offers.put(ts.getTeamId(), offer);

        List<Player> players = new ArrayList<>();
        for (UUID id : ts.getAlivePlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) players.add(p);
        }
        if (players.isEmpty()) { finish(offer); return; }

        boolean someoneDead = ts.getAlivePlayers().size() < ts.getAllPlayers().size() && reviver != null;
        for (Player p : players) {
            List<Perk> options = roll(p, someoneDead);
            open.put(p.getUniqueId(), options);
            offer.pending.add(p.getUniqueId());
            offerOf.put(p.getUniqueId(), offer);
            p.sendMessage(tr(p, "mm.upgrade.offer", waveCleared, seconds));
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            openMenu(p, options);
        }
        offer.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID id : new ArrayList<>(offer.pending)) {
                List<Perk> options = open.get(id);
                Player p = Bukkit.getPlayer(id);
                if (options != null && p != null) {
                    p.closeInventory();
                    Perk pick = options.get(rng.nextInt(options.size()));
                    p.sendMessage(tr(p, "mm.upgrade.timeout"));
                    pickFor(p, pick, offer);
                } else {
                    offer.pending.remove(id);
                }
            }
            finish(offer);
        }, Math.max(5, seconds) * 20L);
    }

    private void finish(Offer offer) {
        if (offer.timeout != null) { offer.timeout.cancel(); offer.timeout = null; }
        for (UUID id : offer.pending) { offerOf.remove(id); open.remove(id); }
        offer.pending.clear();
        offers.remove(offer.ts.getTeamId(), offer);
        Runnable r = offer.done;
        offer.done = null;
        if (r != null) r.run();
    }

    private void cancelAllOffers() {
        for (Offer o : offers.values()) if (o.timeout != null) o.timeout.cancel();
        offers.clear();
        offerOf.clear();
        open.clear();
    }

    private List<Perk> roll(Player p, boolean someoneDead) {
        List<Perk> pool = new ArrayList<>();
        for (Perk perk : Perk.values()) {
            if (perk == Perk.REVIVE && !someoneDead) continue;
            if (stacksOf(p.getUniqueId(), perk) >= perk.max) continue;
            pool.add(perk);
            if (perk == Perk.REVIVE) { pool.add(perk); pool.add(perk); }   // a revive is rare but valuable: weight it
        }
        Collections.shuffle(pool, rng);
        List<Perk> out = new ArrayList<>();
        for (Perk perk : pool) {
            if (!out.contains(perk)) out.add(perk);
            if (out.size() == 3) break;
        }
        if (out.isEmpty()) out.add(Perk.FEAST);
        return out;
    }

    private String tr(Player p, String key, Object... args) {
        return plugin.getKmcCore().getLanguageManager().tr(p, key, args);
    }

    private String perkName(Player p, Perk perk) { return tr(p, "mm.perk." + perk.name().toLowerCase() + ".name"); }
    private String perkDesc(Player p, Perk perk) { return tr(p, "mm.perk." + perk.name().toLowerCase() + ".desc"); }

    private void openMenu(Player p, List<Perk> options) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27, tr(p, "mm.upgrade.title"));
        int[] slots = {11, 13, 15};
        for (int i = 0; i < options.size(); i++) {
            Perk perk = options.get(i);
            ItemStack it = new ItemStack(perk.icon);
            ItemMeta meta = it.getItemMeta();
            int have = stacksOf(p.getUniqueId(), perk);
            meta.setDisplayName("§e§l" + perkName(p, perk) + (have > 0 ? " " + tr(p, "mm.upgrade.level", have + 1) : ""));
            meta.setLore(List.of("§7" + perkDesc(p, perk), "", tr(p, "mm.upgrade.click")));
            it.setItemMeta(meta);
            inv.setItem(slots[i], it);
        }
        p.openInventory(inv);
    }

    // ── Picking ───────────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        List<Perk> options = open.get(p.getUniqueId());
        Offer offer = offerOf.get(p.getUniqueId());
        if (options == null || offer == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int[] slots = {11, 13, 15};
        for (int i = 0; i < options.size(); i++) {
            if (e.getSlot() == slots[i]) {
                p.closeInventory();
                pickFor(p, options.get(i), offer);
                if (offer.pending.isEmpty()) finish(offer);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        List<Perk> options = open.get(p.getUniqueId());
        if (options == null || !offerOf.containsKey(p.getUniqueId())) return;
        // Closed without choosing — reopen so the pick can't be skipped by accident (timeout still applies).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && offerOf.containsKey(p.getUniqueId())) openMenu(p, options);
        }, 2L);
    }

    private void pickFor(Player p, Perk perk, Offer offer) {
        TeamGameState ts = offer.ts;
        offer.pending.remove(p.getUniqueId());
        offerOf.remove(p.getUniqueId());
        open.remove(p.getUniqueId());
        apply(p, perk, ts);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        p.sendMessage(tr(p, "mm.upgrade.picked", perkName(p, perk)));
        if (ts != null) {
            for (UUID id : ts.getAllPlayers()) {
                Player mate = Bukkit.getPlayer(id);
                if (mate != null && !mate.equals(p))
                    mate.sendMessage(tr(mate, "mm.upgrade.mate-picked", p.getName(), perkName(mate, perk)));
            }
        }
    }

    // ── Effects ───────────────────────────────────────────────────────────────

    private void apply(Player p, Perk perk, TeamGameState ts) {
        Map<Perk, Integer> mine = stacks.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Perk.class));
        mine.merge(perk, 1, Integer::sum);
        int level = mine.get(perk);
        PlayerInventory inv = p.getInventory();

        switch (perk) {
            case WEAPON -> upgradeWeapons(p, level);
            case ARMOR -> upgradeArmor(p, level);
            case SHARP -> applySharpness(p, level);
            case HEARTS -> { applyHearts(p); healFully(p); }
            case SPEED -> reapply(p);
            case BOW -> {
                enchantBow(inv, level);
                give(p, new ItemStack(Material.ARROW, 32));
            }
            case FEAST -> {
                give(p, new ItemStack(Material.GOLDEN_APPLE, 6));
                give(p, new ItemStack(Material.COOKED_BEEF, 16));
            }
            case MEDIC -> {
                healFully(p);
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 1, true, false, true));
            }
            case REVIVE -> {
                if (reviver != null && ts != null) {
                    reviver.accept(ts);
                    nl.kmc.core.api.KMCApiProvider.get().games().recordHighlight(p.getUniqueId(), "teammate", 3, "mob_mayhem");
                }
            }
        }
    }

    private void upgradeWeapons(Player p, int level) {
        int tier = Math.min(level, WEAPON_TIERS.length - 1);
        PlayerInventory inv = p.getInventory();
        replaceTool(inv, "_SWORD", WEAPON_TIERS[tier] + "_SWORD");
        replaceTool(inv, "_AXE", WEAPON_TIERS[tier] + "_AXE");
        applySharpness(p, stacksOf(p.getUniqueId(), Perk.SHARP));
    }

    private void upgradeArmor(Player p, int level) {
        String tier = ARMOR_TIERS[Math.min(level, ARMOR_TIERS.length - 1)];
        PlayerInventory inv = p.getInventory();
        inv.setHelmet(new ItemStack(Material.valueOf(tier + "_HELMET")));
        inv.setChestplate(new ItemStack(Material.valueOf(tier + "_CHESTPLATE")));
        inv.setLeggings(new ItemStack(Material.valueOf(tier + "_LEGGINGS")));
        inv.setBoots(new ItemStack(Material.valueOf(tier + "_BOOTS")));
    }

    private void applySharpness(Player p, int level) {
        if (level <= 0) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType().name().endsWith("_SWORD")) {
                it.addUnsafeEnchantment(Enchantment.SHARPNESS, level);
                inv.setItem(i, it);
            }
        }
    }

    private static void enchantBow(PlayerInventory inv, int power) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType() == Material.BOW) {
                it.addUnsafeEnchantment(Enchantment.POWER, power);
                inv.setItem(i, it);
                return;
            }
        }
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addUnsafeEnchantment(Enchantment.POWER, power);
        inv.addItem(bow);
    }

    private void applyHearts(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(20.0 + 4.0 * stacksOf(p.getUniqueId(), Perk.HEARTS));
    }

    private void healFully(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(attr != null ? attr.getValue() : 20.0);
        p.setFoodLevel(20);
    }

    /** Re-applies the lasting parts (hearts, speed, sharpness) — called at the start of every wave. */
    public void reapply(Player p) {
        applyHearts(p);
        int speed = stacksOf(p.getUniqueId(), Perk.SPEED);
        if (speed > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION,
                    speed - 1, true, false, true));
        }
    }

    /** Wipes a player's upgrades and attribute changes (game end). */
    public void clear(Player p) {
        stacks.remove(p.getUniqueId());
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(20.0);
    }

    public void clearAll() {
        cancelAllOffers();
        for (UUID id : new ArrayList<>(stacks.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) clear(p);
        }
        stacks.clear();
    }

    /** Re-applies everything a revived player had earned (kit is re-issued by the caller first). */
    public void restoreGear(Player p) {
        Map<Perk, Integer> mine = stacks.get(p.getUniqueId());
        if (mine == null) return;
        int w = mine.getOrDefault(Perk.WEAPON, 0);
        int a = mine.getOrDefault(Perk.ARMOR, 0);
        if (w > 0) upgradeWeapons(p, w);
        if (a > 0) upgradeArmor(p, a);
        if (mine.getOrDefault(Perk.BOW, 0) > 0) enchantBow(p.getInventory(), mine.get(Perk.BOW));
        applySharpness(p, mine.getOrDefault(Perk.SHARP, 0));
        reapply(p);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public int stacksOf(UUID id, Perk perk) {
        Map<Perk, Integer> m = stacks.get(id);
        return m == null ? 0 : m.getOrDefault(perk, 0);
    }

    private static void replaceTool(PlayerInventory inv, String suffix, String newMaterial) {
        Material to = Material.matchMaterial(newMaterial);
        if (to == null) return;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType().name().endsWith(suffix)) {
                ItemStack up = new ItemStack(to);
                up.addUnsafeEnchantments(it.getEnchantments());
                inv.setItem(i, up);
            }
        }
    }

    private static void give(Player p, ItemStack item) {
        var left = p.getInventory().addItem(item);
        left.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
    }

    /** Marker holder so the click/close handlers only touch our menu. */
    private static final class MenuHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
