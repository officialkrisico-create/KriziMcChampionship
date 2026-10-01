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

    public enum NPCType { STATS, HOF, EASTER_EGG }

    public static final NamespacedKey NPC_KEY = NamespacedKey.minecraft("kmc_lobby_npc");
    public static final NamespacedKey NPC_TYPE_KEY = NamespacedKey.minecraft("kmc_lobby_npc_type");
    /** Stable per-NPC index for easter eggs — fixes which line it says and lets us track "found" per player. */
    public static final NamespacedKey EASTER_EGG_ID_KEY = NamespacedKey.minecraft("kmc_easter_egg_id");

    /** Fallback lines if `easter-egg-npc.lines` isn't set in config.yml — one NPC, one fixed line each. */
    private static final List<String> DEFAULT_EASTER_EGG_LINES = List.of(
            "&7\"Heb je al geprobeerd het uit en weer aan te zetten?\"",
            "&7\"Ik heb ooit 500 lucky blocks geopend. Vraag niet wat erin zat.\"",
            "&7\"De ronde-multiplier is eigenlijk gewoon een schattingsfout van de ontwikkelaar.\"",
            "&7\"Psst... niemand leest deze tekst, behalve jij nu.\"",
            "&7\"Fun fact: ik ben gewoon een dorpeling met een identiteitscrisis.\"");

    private final KMCCore plugin;
    private final Map<UUID, Long> lastEasterEggMs = new HashMap<>();
    /** Cache of each player's found easter-egg IDs — lazily loaded from the DB, then kept in sync. */
    private final Map<UUID, Set<Integer>> foundEggsCache = new HashMap<>();

    public LobbyNPCManager(KMCCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ensureEasterEggTable();
    }

    /** Spawns a stats/HoF/easter-egg NPC at the given location. */
    public Villager spawnNPC(Location loc, NPCType type) {
        int eggId = type == NPCType.EASTER_EGG ? nextEasterEggId() : -1;
        Villager v = loc.getWorld().spawn(loc, Villager.class, npc -> {
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setCustomName(switch (type) {
                case STATS      -> ChatColor.AQUA + "" + ChatColor.BOLD + "📊 My Stats";
                case HOF        -> ChatColor.GOLD + "" + ChatColor.BOLD + "🏆 Hall of Fame";
                case EASTER_EGG -> ChatColor.GRAY + "" + ChatColor.ITALIC + "??? ";
            });
            npc.setCustomNameVisible(type != NPCType.EASTER_EGG); // hidden — no floating name, find it by exploring
            npc.setProfession(switch (type) {
                case STATS      -> Villager.Profession.LIBRARIAN;
                case HOF        -> Villager.Profession.CARTOGRAPHER;
                case EASTER_EGG -> Villager.Profession.NITWIT;
            });
            npc.getPersistentDataContainer().set(NPC_KEY, PersistentDataType.BYTE, (byte) 1);
            npc.getPersistentDataContainer().set(NPC_TYPE_KEY,
                    PersistentDataType.STRING, type.name());
            if (type == NPCType.EASTER_EGG) {
                npc.getPersistentDataContainer().set(EASTER_EGG_ID_KEY, PersistentDataType.INTEGER, eggId);
            }
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
            case STATS      -> openStatsGUI(p);
            case HOF        -> openHoFGUI(p);
            case EASTER_EGG -> handleEasterEgg(p, pdc);
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
    }

    // ----------------------------------------------------------------
    // Easter eggs — one fixed line per NPC, "find them all" tracking
    // ----------------------------------------------------------------

    private void handleEasterEgg(Player p, org.bukkit.persistence.PersistentDataContainer pdc) {
        long now = System.currentTimeMillis();
        long last = lastEasterEggMs.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < 3000) return; // ignore rapid re-clicks
        lastEasterEggMs.put(p.getUniqueId(), now);

        Integer eggId = pdc.get(EASTER_EGG_ID_KEY, PersistentDataType.INTEGER);
        if (eggId == null) eggId = 0; // defensive — NPCs spawned before this feature existed

        List<String> lines = plugin.getConfig().getStringList("easter-egg-npc.lines");
        if (lines.isEmpty()) lines = DEFAULT_EASTER_EGG_LINES;
        String line = lines.get(eggId % lines.size()); // fixed per NPC — not random

        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&7???&8] " + line));

        Set<Integer> found = foundEggsFor(p.getUniqueId());
        if (found.contains(eggId)) return; // already found this one before — just the line, no fanfare

        markFound(p.getUniqueId(), eggId);
        int total = countEasterEggNpcs();
        int have  = found.size();
        p.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&6&l✨ Nieuwe easter egg gevonden! &e(" + have + "/" + total + ")"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);

        if (total > 0 && have >= total) {
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                    "&6&l✨ " + p.getName() + " heeft ALLE verborgen easter eggs gevonden! ✨"));
            if (plugin.getAchievementManager() != null) {
                plugin.getAchievementManager().unlock(p.getUniqueId(), "easter_egg_hunter");
            }
        }
    }

    /** Every easter-egg NPC currently placed in any loaded world. */
    private int countEasterEggNpcs() {
        int count = 0;
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (e instanceof Villager v && v.getPersistentDataContainer().has(EASTER_EGG_ID_KEY, PersistentDataType.INTEGER)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Next free easter-egg ID — scans existing NPCs so IDs stay unique across restarts. */
    private int nextEasterEggId() {
        int max = -1;
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (!(e instanceof Villager v)) continue;
                Integer id = v.getPersistentDataContainer().get(EASTER_EGG_ID_KEY, PersistentDataType.INTEGER);
                if (id != null) max = Math.max(max, id);
            }
        }
        return max + 1;
    }

    private void ensureEasterEggTable() {
        plugin.getDatabaseManager().runWithConnection(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_easter_eggs (
                        uuid VARCHAR(36) NOT NULL,
                        egg_id INT NOT NULL,
                        found_at BIGINT NOT NULL,
                        PRIMARY KEY (uuid, egg_id)
                    )""");
            }
        });
    }

    private Set<Integer> foundEggsFor(UUID uuid) {
        return foundEggsCache.computeIfAbsent(uuid, this::loadFoundEggs);
    }

    private Set<Integer> loadFoundEggs(UUID uuid) {
        Set<Integer> out = new HashSet<>();
        plugin.getDatabaseManager().runWithConnection(c -> {
            try (var ps = c.prepareStatement("SELECT egg_id FROM player_easter_eggs WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getInt("egg_id"));
                }
            }
        });
        return out;
    }

    private void markFound(UUID uuid, int eggId) {
        foundEggsFor(uuid).add(eggId);
        plugin.getDatabaseManager().runWithConnection(c -> {
            try (var ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO player_easter_eggs (uuid, egg_id, found_at) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, eggId);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
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
