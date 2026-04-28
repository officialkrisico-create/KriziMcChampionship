package nl.kmc.kmccore.lobby;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Lobby stat NPCs.
 *
 * <p>Spawns simple Villager-based NPCs in the lobby that, when
 * right-clicked, open a stats GUI showing the player's tournament
 * data: total points, games played, win rate, kill count, top game,
 * etc. Pulled from KMCCore PlayerData.
 *
 * <p>Two NPC types:
 * <ul>
 *   <li><b>STATS</b> — your personal stats</li>
 *   <li><b>HOF</b> — global hall of fame top performers</li>
 * </ul>
 *
 * <p>Admins spawn NPCs via /kmcnpc spawn stats / /kmcnpc spawn hof.
 * NPCs persist across restarts (saved to lobbynpcs.yml).
 *
 * <p>This is the Villager-based fallback. The Citizens / FancyNpcs
 * integration in {@link nl.kmc.kmccore.npc.NPCManager} (already
 * existing) handles head-mounted leaderboards. This NPC system is
 * specifically for the interactive stats kiosks.
 */
public class LobbyNPCManager implements Listener {

    public enum NPCType { STATS, HOF }

    public static final NamespacedKey NPC_KEY = NamespacedKey.minecraft("kmc_lobby_npc");
    public static final NamespacedKey NPC_TYPE_KEY = NamespacedKey.minecraft("kmc_lobby_npc_type");

    private final KMCCore plugin;

    public LobbyNPCManager(KMCCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Spawns a stats NPC at the given location. */
    public Villager spawnNPC(Location loc, NPCType type) {
        Villager v = loc.getWorld().spawn(loc, Villager.class, npc -> {
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setCustomName(type == NPCType.STATS
                    ? ChatColor.AQUA + "" + ChatColor.BOLD + "📊 My Stats"
                    : ChatColor.GOLD + "" + ChatColor.BOLD + "🏆 Hall of Fame");
            npc.setCustomNameVisible(true);
            npc.setProfession(type == NPCType.STATS ? Villager.Profession.LIBRARIAN : Villager.Profession.CARTOGRAPHER);
            npc.getPersistentDataContainer().set(NPC_KEY, PersistentDataType.BYTE, (byte) 1);
            npc.getPersistentDataContainer().set(NPC_TYPE_KEY,
                    PersistentDataType.STRING, type.name());
        });
        return v;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager v)) return;
        var pdc = v.getPersistentDataContainer();
        if (!pdc.has(NPC_KEY, PersistentDataType.BYTE)) return;
        event.setCancelled(true);

        Player p = event.getPlayer();
        String typeStr = pdc.get(NPC_TYPE_KEY, PersistentDataType.STRING);
        NPCType type = typeStr != null ? NPCType.valueOf(typeStr) : NPCType.STATS;

        switch (type) {
            case STATS -> openStatsGUI(p);
            case HOF -> openHoFGUI(p);
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
    }

    // ----------------------------------------------------------------
    // GUIs
    // ----------------------------------------------------------------

    private void openStatsGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27,
                ChatColor.AQUA + "" + ChatColor.BOLD + "📊 Your Stats");

        PlayerData data = plugin.getPlayerDataManager().get(p.getUniqueId());

        inv.setItem(4, makeItem(Material.PLAYER_HEAD, ChatColor.YELLOW + p.getName(),
                List.of(ChatColor.GRAY + "Tournament participant"), p));

        inv.setItem(10, makeItem(Material.EMERALD, ChatColor.GREEN + "Total Points",
                List.of(ChatColor.WHITE + String.valueOf(data != null ? data.getPoints() : 0))));

        inv.setItem(11, makeItem(Material.DIAMOND_SWORD, ChatColor.RED + "Total Kills",
                List.of(ChatColor.WHITE + String.valueOf(data != null ? data.getKills() : 0))));

        inv.setItem(12, makeItem(Material.EXPERIENCE_BOTTLE, ChatColor.GOLD + "Games Played",
                List.of(ChatColor.WHITE + String.valueOf(data != null ? data.getGamesPlayed() : 0))));

        inv.setItem(13, makeItem(Material.GOLDEN_APPLE, ChatColor.YELLOW + "Wins",
                List.of(ChatColor.WHITE + String.valueOf(data != null ? data.getWins() : 0))));

        int gamesPlayed = data != null ? data.getGamesPlayed() : 0;
        int gamesWon = data != null ? data.getWins() : 0;
        double winRate = gamesPlayed > 0 ? (gamesWon * 100.0 / gamesPlayed) : 0;
        inv.setItem(14, makeItem(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "Win Rate",
                List.of(ChatColor.WHITE + String.format("%.1f%%", winRate))));

        inv.setItem(15, makeItem(Material.BLAZE_POWDER, ChatColor.RED + "Best Streak",
                List.of(ChatColor.WHITE + String.valueOf(data != null ? data.getBestWinStreak() : 0))));

        inv.setItem(16, makeItem(Material.PAPER, ChatColor.AQUA + "Current Streak",
                List.of(ChatColor.WHITE + String.valueOf(data != null ? data.getWinStreak() : 0))));

        // Team info
        var team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (team != null) {
            inv.setItem(22, makeItem(Material.WHITE_BANNER,
                    team.getColor() + "" + ChatColor.BOLD + team.getDisplayName(),
                    List.of(ChatColor.GRAY + "Team Points: "
                            + ChatColor.WHITE + team.getPoints())));
        }

        p.openInventory(inv);
    }

    private void openHoFGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54,
                ChatColor.GOLD + "" + ChatColor.BOLD + "🏆 Hall of Fame");

        var leaderboard = plugin.getPlayerDataManager().getLeaderboard();

        inv.setItem(4, makeItem(Material.NETHER_STAR,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Top Players",
                List.of(ChatColor.GRAY + "Tournament leaderboard")));

        // Top 10 players by points
        for (int i = 0; i < Math.min(10, leaderboard.size()); i++) {
            PlayerData data = leaderboard.get(i);
            String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : "#" + (i + 1);
            inv.setItem(9 + i + (i / 9), makeItem(Material.PLAYER_HEAD,
                    ChatColor.YELLOW + medal + " " + data.getName(),
                    List.of(
                            ChatColor.GRAY + "Points: " + ChatColor.WHITE + data.getPoints(),
                            ChatColor.GRAY + "Kills: " + ChatColor.WHITE + data.getKills(),
                            ChatColor.GRAY + "Wins: " + ChatColor.WHITE + data.getWins()
                    ), null));
        }

        // Team leaderboard at bottom
        inv.setItem(31, makeItem(Material.WHITE_BANNER,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Top Teams",
                List.of(ChatColor.GRAY + "Team standings")));

        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        for (int i = 0; i < Math.min(5, teams.size()); i++) {
            var t = teams.get(i);
            String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : "#" + (i + 1);
            inv.setItem(36 + i, makeItem(Material.WHITE_WOOL,
                    t.getColor() + medal + " " + t.getDisplayName(),
                    List.of(ChatColor.GRAY + "Points: " + ChatColor.WHITE + t.getPoints())));
        }

        p.openInventory(inv);
    }

    // ----------------------------------------------------------------

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        return makeItem(mat, name, lore, null);
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore, Player owner) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text(name));
        if (lore != null && !lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> parts = new ArrayList<>();
            for (String s : lore) parts.add(net.kyori.adventure.text.Component.text(s));
            meta.lore(parts);
        }
        if (owner != null && meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            sm.setOwningPlayer(owner);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Removes all spawned KMC lobby NPCs (cleanup on plugin disable). */
    public void despawnAll() {
        for (World w : Bukkit.getWorlds()) {
            for (var entity : w.getEntities()) {
                if (!(entity instanceof Villager v)) continue;
                if (v.getPersistentDataContainer().has(NPC_KEY, PersistentDataType.BYTE)) {
                    v.remove();
                }
            }
        }
    }
}
