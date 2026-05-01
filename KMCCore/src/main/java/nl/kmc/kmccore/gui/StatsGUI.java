package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.achievements.Achievement;
import nl.kmc.kmccore.achievements.AchievementManager;
import nl.kmc.kmccore.achievements.AchievementRegistry;
import nl.kmc.kmccore.history.TournamentHistoryManager;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Multi-page stats GUI for /kmcstats.
 *
 * <p>Pages:
 * <ol>
 *   <li>Overview — career + current tournament stats</li>
 *   <li>Per-game breakdown — wins by minigame</li>
 *   <li>Achievements — locked + unlocked, with rarity coloring</li>
 *   <li>Tournament history — past placements</li>
 * </ol>
 *
 * <p>Navigation: bottom row has 4 tab buttons (one per page) + a back/close.
 */
public class StatsGUI implements Listener {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");

    private final KMCCore plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey targetUuidKey;
    private final NamespacedKey pageKey;

    public StatsGUI(KMCCore plugin) {
        this.plugin        = plugin;
        this.markerKey     = new NamespacedKey(plugin, "stats_gui");
        this.targetUuidKey = new NamespacedKey(plugin, "stats_target");
        this.pageKey       = new NamespacedKey(plugin, "stats_page");
    }

    public void open(Player viewer, UUID targetUuid, int page) {
        PlayerData pd = plugin.getPlayerDataManager().get(targetUuid);
        if (pd == null) {
            viewer.sendMessage(ChatColor.RED + "Geen statistieken gevonden voor die speler.");
            return;
        }

        Inventory inv;
        switch (page) {
            case 1 -> inv = buildOverviewPage(pd);
            case 2 -> inv = buildGameBreakdownPage(pd);
            case 3 -> inv = buildAchievementsPage(targetUuid);
            case 4 -> inv = buildHistoryPage(targetUuid);
            default -> { open(viewer, targetUuid, 1); return; }
        }

        // Bottom row: navigation
        addNavRow(inv, targetUuid, page);

        viewer.openInventory(inv);
    }

    // ----------------------------------------------------------------
    // Page builders
    // ----------------------------------------------------------------

    private Inventory buildOverviewPage(PlayerData pd) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA
                + "Stats: " + ChatColor.WHITE + pd.getName());

        // Player head — slot 4 (top center)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(pd.getUuid()));
        sm.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + pd.getName());
        sm.setLore(List.of(
                ChatColor.GRAY + "Team: " + ChatColor.WHITE
                        + (pd.getTeamId() != null ? pd.getTeamId() : "Geen"),
                "",
                ChatColor.GRAY + "Klik op een tab onderin voor meer details."
        ));
        head.setItemMeta(sm);
        inv.setItem(4, head);

        // Career stats — left column
        inv.setItem(19, statBlock(Material.EXPERIENCE_BOTTLE, "&aTotaal Punten",
                String.valueOf(pd.getPoints())));
        inv.setItem(20, statBlock(Material.IRON_SWORD, "&cKills",
                String.valueOf(pd.getKills())));
        inv.setItem(21, statBlock(Material.GOLD_INGOT, "&6Wins",
                String.valueOf(pd.getWins())));
        inv.setItem(22, statBlock(Material.PAPER, "&7Games Gespeeld",
                String.valueOf(pd.getGamesPlayed())));

        // Streaks + win rate — right column
        int games = pd.getGamesPlayed();
        double winRate = games > 0 ? (100.0 * pd.getWins() / games) : 0;
        inv.setItem(23, statBlock(Material.LIME_DYE, "&aWin Rate",
                String.format("%.1f%%", winRate)));
        inv.setItem(24, statBlock(Material.NETHER_STAR, "&eHuidige Streak",
                pd.getWinStreak() + " games"));
        inv.setItem(25, statBlock(Material.BEACON, "&eBeste Streak",
                pd.getBestWinStreak() + " games"));

        // Achievement progress summary
        AchievementManager am = plugin.getAchievementManager();
        int unlocked = am.unlockedCount(pd.getUuid());
        int total = AchievementRegistry.count();
        inv.setItem(31, statBlock(Material.FILLED_MAP, "&dAchievements",
                unlocked + " / " + total));

        return inv;
    }

    private Inventory buildGameBreakdownPage(PlayerData pd) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA
                + "Per-Game: " + ChatColor.WHITE + pd.getName());

        Map<String, Integer> wpg = pd.getWinsPerGame();
        if (wpg == null || wpg.isEmpty()) {
            inv.setItem(22, statBlock(Material.BARRIER, "&7Geen game-overwinningen",
                    "Win een game om data te tonen."));
            return inv;
        }

        // Game icon mapping
        Map<String, Material> icons = Map.ofEntries(
                Map.entry("adventure_escape", Material.GOLDEN_BOOTS),
                Map.entry("skywars",          Material.FEATHER),
                Map.entry("survival_games",   Material.STONE_AXE),
                Map.entry("quakecraft",       Material.BOW),
                Map.entry("parkour_warrior",  Material.LIME_WOOL),
                Map.entry("tgttos",           Material.RABBIT_FOOT),
                Map.entry("the_bridge",       Material.IRON_SWORD),
                Map.entry("elytra_endrium",   Material.ELYTRA),
                Map.entry("spleef",           Material.IRON_SHOVEL),
                Map.entry("meltdown_mayhem",  Material.LAVA_BUCKET),
                Map.entry("mob_mayhem",       Material.ZOMBIE_HEAD),
                Map.entry("lucky_block",      Material.GOLD_BLOCK),
                Map.entry("bingo",            Material.PAPER)
        );

        // Sort entries by wins descending, place in 3x4 grid
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(wpg.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int[] slots = { 10, 11, 12, 13, 14, 15, 16,
                        19, 20, 21, 22, 23, 24, 25 };
        for (int i = 0; i < sorted.size() && i < slots.length; i++) {
            var e = sorted.get(i);
            Material icon = icons.getOrDefault(e.getKey(), Material.PAPER);
            String label = ChatColor.GREEN + prettify(e.getKey());
            inv.setItem(slots[i], statBlock(icon, label, e.getValue() + " wins"));
        }

        return inv;
    }

    private Inventory buildAchievementsPage(UUID uuid) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA + "Achievements");

        AchievementManager am = plugin.getAchievementManager();
        Set<String> unlocked = am.getUnlocked(uuid);

        // Sort: unlocked legendary, unlocked rare, unlocked common, then locked legendary, rare, common
        List<Achievement> sorted = new ArrayList<>(AchievementRegistry.getAll());
        sorted.sort((a, b) -> {
            boolean aUnlocked = unlocked.contains(a.getId());
            boolean bUnlocked = unlocked.contains(b.getId());
            if (aUnlocked != bUnlocked) return aUnlocked ? -1 : 1;
            // Same unlock status — sort by rarity (LEGENDARY first)
            return Integer.compare(b.getRarity().ordinal(), a.getRarity().ordinal());
        });

        // Place into 5×9 grid (slots 0-44), leaving bottom row for nav
        for (int i = 0; i < sorted.size() && i < 45; i++) {
            Achievement a = sorted.get(i);
            boolean isUnlocked = unlocked.contains(a.getId());
            inv.setItem(i, achievementItem(a, isUnlocked, uuid));
        }

        return inv;
    }

    private Inventory buildHistoryPage(UUID uuid) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA + "Toernooi Historie");

        TournamentHistoryManager hm = plugin.getTournamentHistoryManager();
        var history = hm != null ? hm.getPlayerHistory(uuid, 28) : List.<TournamentHistoryManager.PlayerResult>of();

        if (history.isEmpty()) {
            inv.setItem(22, statBlock(Material.BARRIER, "&7Nog geen toernooi-geschiedenis",
                    "Speel een toernooi om data te zien."));
            return inv;
        }

        for (int i = 0; i < history.size() && i < 45; i++) {
            var pr = history.get(i);

            Material icon = switch (pr.rank) {
                case 1  -> Material.GOLD_INGOT;
                case 2  -> Material.IRON_INGOT;
                case 3  -> Material.COPPER_INGOT;
                default -> Material.PAPER;
            };

            ChatColor rankColor = pr.rank == 1 ? ChatColor.GOLD
                    : pr.rank == 2 ? ChatColor.GRAY
                    : pr.rank == 3 ? ChatColor.RED
                    : ChatColor.WHITE;

            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(rankColor + "#" + pr.rank
                    + ChatColor.GRAY + " — Toernooi " + pr.tournamentId);
            meta.setLore(List.of(
                    ChatColor.GRAY + "Datum: " + ChatColor.WHITE + DATE_FMT.format(new Date(pr.endedAtMs)),
                    ChatColor.GRAY + "Punten: " + ChatColor.YELLOW + pr.points,
                    ChatColor.GRAY + "Kills: " + ChatColor.RED + pr.kills,
                    ChatColor.GRAY + "Wins: " + ChatColor.GOLD + pr.wins,
                    ChatColor.GRAY + "Winnend team: " + ChatColor.WHITE
                            + (pr.winningTeam != null ? pr.winningTeam : "—")
            ));
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }

        return inv;
    }

    // ----------------------------------------------------------------
    // Item builders
    // ----------------------------------------------------------------

    private ItemStack statBlock(Material mat, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (loreLines.length > 0) {
            List<String> lore = new ArrayList<>();
            for (String l : loreLines) {
                lore.add(ChatColor.translateAlternateColorCodes('&', ChatColor.WHITE + l));
            }
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack achievementItem(Achievement a, boolean unlocked, UUID forUuid) {
        ItemStack item = new ItemStack(unlocked ? a.getIcon() : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();

        ChatColor color = unlocked ? a.getRarity().getChatColor() : ChatColor.DARK_GRAY;
        meta.setDisplayName(color + (unlocked ? "✔ " : "🔒 ") + a.getName()
                + ChatColor.GRAY + " [" + a.getRarity().getLabel() + "]");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.WHITE + a.getDescription());
        lore.add("");
        if (unlocked) {
            lore.add(ChatColor.GREEN + "Behaald!");
        } else if (a.isProgressBased()) {
            int progress = plugin.getAchievementManager().getProgress(forUuid, a.getId());
            int target = a.getProgressThreshold();
            lore.add(ChatColor.YELLOW + "Progressie: " + progress + " / " + target);
        } else {
            lore.add(ChatColor.RED + "Nog niet behaald.");
        }
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    private void addNavRow(Inventory inv, UUID targetUuid, int currentPage) {
        // Slot 45-53 is the bottom row
        inv.setItem(45, navButton(Material.NETHER_STAR, "&aOverzicht",   1, currentPage, targetUuid));
        inv.setItem(46, navButton(Material.PAPER,        "&ePer Game",    2, currentPage, targetUuid));
        inv.setItem(47, navButton(Material.FILLED_MAP,   "&dAchievements", 3, currentPage, targetUuid));
        inv.setItem(48, navButton(Material.BOOK,         "&6Historie",    4, currentPage, targetUuid));
        // Filler glass
        for (int i = 49; i < 53; i++) {
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = glass.getItemMeta();
            m.setDisplayName(" ");
            glass.setItemMeta(m);
            inv.setItem(i, glass);
        }
        // Close button
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Sluiten");
        cm.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "close");
        close.setItemMeta(cm);
        inv.setItem(53, close);
    }

    private ItemStack navButton(Material mat, String name, int page, int current, UUID target) {
        ItemStack item = new ItemStack(page == current ? Material.LIME_STAINED_GLASS_PANE : mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                (page == current ? "&l→ " : "") + name));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "nav");
        meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
        meta.getPersistentDataContainer().set(targetUuidKey, PersistentDataType.STRING, target.toString());
        item.setItemMeta(meta);
        return item;
    }

    // ----------------------------------------------------------------
    // Click handler
    // ----------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // Identify our GUIs by title prefix. Cancel ALL clicks (including
        // shift-clicks, hotbar swaps, and drags) if it's one of ours,
        // so players can't yoink display items out.
        String title = e.getView().getTitle();
        if (!isStatsGuiTitle(title)) return;

        e.setCancelled(true);

        ItemStack item = e.getCurrentItem();
        if (item == null || item.getItemMeta() == null) return;

        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(markerKey, PersistentDataType.STRING)) return;

        String marker = pdc.get(markerKey, PersistentDataType.STRING);

        if ("close".equals(marker)) {
            p.closeInventory();
            return;
        }
        if ("nav".equals(marker)) {
            Integer page = pdc.get(pageKey, PersistentDataType.INTEGER);
            String targetStr = pdc.get(targetUuidKey, PersistentDataType.STRING);
            if (page == null || targetStr == null) return;
            try {
                UUID target = UUID.fromString(targetStr);
                p.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> open(p, target, page), 1L);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /** Block dragging items (paint-style drags can move items in shared slot ranges). */
    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!isStatsGuiTitle(e.getView().getTitle())) return;
        e.setCancelled(true);
    }

    /** True if {@code title} is one of our stats GUIs (overview/stats page/achievements/history). */
    private boolean isStatsGuiTitle(String title) {
        if (title == null) return false;
        // Strip color codes for matching
        String stripped = ChatColor.stripColor(title);
        if (stripped == null) return false;
        return stripped.startsWith("Stats: ")
            || stripped.startsWith("Achievements")
            || stripped.startsWith("Toernooi Historie");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static String prettify(String snake) {
        if (snake == null) return "?";
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : snake.toCharArray()) {
            if (c == '_') { sb.append(' '); cap = true; }
            else { sb.append(cap ? Character.toUpperCase(c) : c); cap = false; }
        }
        return sb.toString();
    }
}
