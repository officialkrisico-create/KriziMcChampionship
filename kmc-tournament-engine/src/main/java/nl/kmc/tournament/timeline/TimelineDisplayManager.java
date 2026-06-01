package nl.kmc.tournament.timeline;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.service.TeamService;
import nl.kmc.tournament.timeline.TournamentTimeline.GameStatus;
import nl.kmc.tournament.timeline.TournamentTimeline.TimelineEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the /kmctimeline chest GUI for a player.
 *
 * Layout (54 slots — 6 rows):
 *   Row 0  – header info (round progress, current phase)
 *   Rows 1–3 – timeline entries (completed → active → upcoming)
 *   Row 4  – live standings (top 8 teams)
 *   Row 5  – navigation / close button
 */
public final class TimelineDisplayManager implements Listener {

    private static final String TITLE_PREFIX = ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "KMC Timeline";

    private final JavaPlugin        plugin;
    private final TeamService       teams;

    public TimelineDisplayManager(JavaPlugin plugin, TeamService teams) {
        this.plugin = plugin;
        this.teams  = teams;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Open the timeline GUI for the given player. */
    public void open(Player player, TournamentTimeline timeline) {
        Inventory gui = Bukkit.createInventory(null, 54,
                TITLE_PREFIX + " §8— §7Round " + timeline.getCurrentRound()
                + "/" + timeline.getTotalRounds());

        fillBorder(gui);
        placeHeader(gui, timeline);
        placeEntries(gui, timeline);
        placeStandings(gui);
        placeCloseButton(gui);

        player.openInventory(gui);
    }

    // ── GUI sections ──────────────────────────────────────────────────────────

    private void placeHeader(Inventory gui, TournamentTimeline timeline) {
        ItemStack info = item(Material.CLOCK, ChatColor.GOLD + "" + ChatColor.BOLD + "Tournament Status",
                List.of(
                    ChatColor.GRAY + "Phase: " + ChatColor.YELLOW + formatPhase(timeline),
                    ChatColor.GRAY + "Round: " + ChatColor.AQUA + timeline.getCurrentRound()
                            + ChatColor.GRAY + "/" + timeline.getTotalRounds(),
                    ChatColor.GRAY + "Games played: " + ChatColor.GREEN + timeline.getCompleted().size(),
                    ChatColor.GRAY + "Games left: " + ChatColor.WHITE + timeline.getUpcoming().size()
                ));
        gui.setItem(4, info);
    }

    private void placeEntries(Inventory gui, TournamentTimeline timeline) {
        List<TimelineEntry> entries = timeline.getEntries();
        int[] entrySlots = {9, 10, 11, 12, 13, 14, 15, 16, 17,
                            18, 19, 20, 21, 22, 23, 24, 25, 26,
                            27, 28, 29, 30, 31, 32, 33, 34, 35};
        int slotIdx = 0;

        for (TimelineEntry e : entries) {
            if (slotIdx >= entrySlots.length) break;
            gui.setItem(entrySlots[slotIdx++], entryItem(e));
        }
    }

    private void placeStandings(Inventory gui) {
        List<KMCTeam> standings = teams.getStandings();
        int[] standingSlots = {37, 38, 39, 40, 41, 42, 43, 44};
        for (int i = 0; i < Math.min(standings.size(), standingSlots.length); i++) {
            KMCTeam team = standings.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Points: " + ChatColor.GOLD + team.getPoints());
            lore.add(ChatColor.GRAY + "Wins: "   + ChatColor.GREEN + team.getWins());
            ItemStack item = item(Material.PLAYER_HEAD, "#" + (i + 1) + " " + team.getColouredName(), lore);
            gui.setItem(standingSlots[i], item);
        }

        // Row label
        gui.setItem(36, item(Material.GOLD_INGOT,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Standings", List.of()));
    }

    private void placeCloseButton(Inventory gui) {
        gui.setItem(49, item(Material.BARRIER,
                ChatColor.RED + "Close", List.of(ChatColor.GRAY + "Click to close.")));
    }

    private void fillBorder(Inventory gui) {
        ItemStack glass = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        int[] border = {0,1,2,3,5,6,7,8, 45,46,47,48,50,51,52,53};
        for (int slot : border) gui.setItem(slot, glass);
    }

    // ── Item factories ────────────────────────────────────────────────────────

    private ItemStack entryItem(TimelineEntry entry) {
        Material mat;
        String statusStr;
        switch (entry.status()) {
            case COMPLETED -> { mat = Material.GREEN_STAINED_GLASS;  statusStr = ChatColor.GREEN + "✔ Completed"; }
            case ACTIVE    -> { mat = Material.YELLOW_STAINED_GLASS; statusStr = ChatColor.YELLOW + "▶ In Progress"; }
            default        -> { mat = Material.WHITE_STAINED_GLASS;  statusStr = ChatColor.GRAY  + "○ Upcoming"; }
        }

        List<String> lore = new ArrayList<>();
        lore.add(statusStr);
        lore.add(ChatColor.GRAY + "Round: " + ChatColor.WHITE + entry.round());
        if (entry.status() == GameStatus.COMPLETED && entry.mvpName() != null) {
            lore.add(ChatColor.GRAY + "MVP: " + ChatColor.GOLD + entry.mvpName());
        }
        if (entry.status() == GameStatus.COMPLETED) {
            lore.add(ChatColor.GRAY + "Winner pts: " + ChatColor.YELLOW + entry.winnerTeamPoints());
        }

        return item(mat, entry.game().getDisplayName(), lore);
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta  meta  = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ── Click handler ─────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTitle().startsWith(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "KMC Timeline")))
            return;
        event.setCancelled(true);
        if (event.getSlot() == 49 && event.getWhoClicked() instanceof Player p) {
            p.closeInventory();
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String formatPhase(TournamentTimeline timeline) {
        return timeline.getCurrentPhase().name().replace('_', ' ');
    }
}
