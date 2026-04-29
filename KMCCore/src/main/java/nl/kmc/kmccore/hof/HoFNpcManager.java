package nl.kmc.kmccore.hof;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Binds the three Hall of Fame NPCs (top player, MVP, most wins) to
 * FancyNpcs entities. Updates their displayed skin/name/holograms every
 * 5 minutes and on tournament-end events.
 *
 * <p>Configuration in {@code config.yml}:
 * <pre>
 * hof:
 *   refresh-interval-seconds: 300
 *   npcs:
 *     top_player:  npc_id_string_from_fancynpcs
 *     mvp:         npc_id_string_from_fancynpcs
 *     most_wins:   npc_id_string_from_fancynpcs
 * </pre>
 *
 * <p>FancyNpcs is a soft dependency. If not installed, this manager
 * does nothing (logs a single warning at startup).
 *
 * <p>Right-click handler: when a player right-clicks one of the bound
 * NPCs, opens that featured player's StatsGUI.
 */
public class HoFNpcManager implements Listener {

    public enum Slot { TOP_PLAYER, MVP, MOST_WINS }

    private final KMCCore plugin;

    /** slot -> FancyNpc string id */
    private final Map<Slot, String> npcBindings = new EnumMap<>(Slot.class);

    /** slot -> currently featured player UUID */
    private final Map<Slot, UUID>   featured = new EnumMap<>(Slot.class);

    private boolean fancyNpcsAvailable;
    private BukkitTask refreshTask;

    public HoFNpcManager(KMCCore plugin) {
        this.plugin = plugin;
        this.fancyNpcsAvailable =
                Bukkit.getPluginManager().getPlugin("FancyNpcs") != null;
        loadBindings();
    }

    private void loadBindings() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("hof.npcs");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                Slot slot = Slot.valueOf(key.toUpperCase());
                String npcId = sec.getString(key);
                if (npcId != null && !npcId.isBlank()) {
                    npcBindings.put(slot, npcId);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void start() {
        if (!fancyNpcsAvailable) {
            plugin.getLogger().warning("HoFNpcManager: FancyNpcs not installed — HoF NPCs disabled.");
            return;
        }
        refresh();
        long ticks = 20L * plugin.getConfig().getLong("hof.refresh-interval-seconds", 300);
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, ticks, ticks);
    }

    public void stop() {
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
    }

    /**
     * Recomputes who should be featured and updates each NPC's skin/name.
     * Called on schedule + on tournament-end.
     */
    public void refresh() {
        if (!fancyNpcsAvailable) return;
        if (npcBindings.isEmpty()) return;

        // 1. Determine winners from the leaderboard
        var leaderboard = plugin.getPlayerDataManager().getLeaderboard();
        if (leaderboard.isEmpty()) return;

        // Top player = #1 by points (all-time)
        PlayerData topPlayer = leaderboard.get(0);

        // MVP = #1 by points (most recent tournament). Pull from history.
        PlayerData mvp = topPlayer;  // fallback
        try {
            var recent = plugin.getTournamentHistoryManager().getRecentTournaments(1);
            if (!recent.isEmpty()) {
                // Find the rank-1 player from that tournament
                long tid = recent.get(0).id;
                for (PlayerData pd : leaderboard) {
                    var hist = plugin.getTournamentHistoryManager().getPlayerHistory(pd.getUuid(), 5);
                    for (var pr : hist) {
                        if (pr.tournamentId == tid && pr.rank == 1) {
                            mvp = pd;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Most wins = highest career wins
        PlayerData mostWins = leaderboard.stream()
                .max(Comparator.comparingInt(PlayerData::getWins))
                .orElse(topPlayer);

        // 2. Update each bound NPC
        applyToNpc(Slot.TOP_PLAYER, topPlayer, "&6&l★ TOP PLAYER",
                topPlayer.getPoints() + " punten");
        applyToNpc(Slot.MVP, mvp, "&b&l✦ MVP",
                mvp.getName() + " — laatste toernooi");
        applyToNpc(Slot.MOST_WINS, mostWins, "&e&l⚔ MOST WINS",
                mostWins.getWins() + " wins");
    }

    private void applyToNpc(Slot slot, PlayerData pd, String topLine, String botLine) {
        String npcId = npcBindings.get(slot);
        if (npcId == null) return;

        featured.put(slot, pd.getUuid());

        try {
            // FancyNpcs API — update via reflection so we don't hard-link
            Object fnPlugin = Bukkit.getPluginManager().getPlugin("FancyNpcs");
            if (fnPlugin == null) return;

            // de.oliver.fancynpcs.api.FancyNpcsPlugin.get().getNpcManager()
            Class<?> fnpc = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
            Object api = fnpc.getMethod("get").invoke(null);
            Object npcManager = api.getClass().getMethod("getNpcManager").invoke(api);
            Object npc = npcManager.getClass().getMethod("getNpcById", String.class)
                    .invoke(npcManager, npcId);
            if (npc == null) {
                plugin.getLogger().warning("HoF NPC '" + npcId + "' not found in FancyNpcs.");
                return;
            }

            // Update display name
            Object data = npc.getClass().getMethod("getData").invoke(npc);
            String displayName = ChatColor.translateAlternateColorCodes('&', topLine + "\n&f" + pd.getName() + "\n&7" + botLine);
            data.getClass().getMethod("setDisplayName", String.class).invoke(data, displayName);

            // Update skin to featured player
            try {
                Class<?> skinDataClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData");
                Object skinData = skinDataClass.getConstructor(String.class).newInstance(pd.getName());
                data.getClass().getMethod("setSkinData", skinDataClass).invoke(data, skinData);
            } catch (Throwable ignored) {
                // older FancyNpcs API — skip skin update
            }

            // Force re-render
            try { npc.getClass().getMethod("updateForAll").invoke(npc); }
            catch (Throwable ignored) {}

        } catch (Throwable e) {
            plugin.getLogger().warning("HoF NPC update failed for " + slot + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // Right-click → stats GUI
    // ----------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!fancyNpcsAvailable) return;
        // Identify if the entity is one of our bound NPCs
        // FancyNpcs entities are tagged — we check via reflection.
        try {
            Class<?> fnpc = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
            Object api = fnpc.getMethod("get").invoke(null);
            Object npcManager = api.getClass().getMethod("getNpcManager").invoke(api);
            Object npc = npcManager.getClass().getMethod("getNpcByEntityId", int.class)
                    .invoke(npcManager, e.getRightClicked().getEntityId());
            if (npc == null) return;

            String entityNpcId = (String) npc.getClass().getMethod("getId").invoke(npc);

            // Find which slot this is
            for (Map.Entry<Slot, String> entry : npcBindings.entrySet()) {
                if (entry.getValue().equals(entityNpcId)) {
                    UUID target = featured.get(entry.getKey());
                    if (target != null && plugin.getStatsGUI() != null) {
                        plugin.getStatsGUI().open(e.getPlayer(), target, 1);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {
            // FancyNpcs not loaded or API mismatch — ignore
        }
    }

    // ----------------------------------------------------------------
    // Admin binding
    // ----------------------------------------------------------------

    public void bind(Slot slot, String npcId) {
        npcBindings.put(slot, npcId);
        plugin.getConfig().set("hof.npcs." + slot.name().toLowerCase(), npcId);
        plugin.saveConfig();
        refresh();
    }

    public Map<Slot, String> getBindings() {
        return Collections.unmodifiableMap(npcBindings);
    }
}
