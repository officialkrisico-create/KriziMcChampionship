package nl.kmc.kmccore.director;

import nl.kmc.core.DryRun;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Event-director tools that sit next to the automation engine:
 *
 * <ul>
 *   <li><b>Dry-run</b> — play one game with {@link DryRun} on: no points, kills, medals, MVP or momentum,
 *       and the whole tournament state is restored from a snapshot afterwards as a safety net.</li>
 *   <li><b>Emergency freeze</b> — stops every player in place (a game's own timers keep running; it is a
 *       noodrem for incidents, not a real pause).</li>
 * </ul>
 */
public final class DirectorService implements Listener {

    private final KMCCore plugin;
    private final PresetManager presets;

    // Dry-run bookkeeping
    private String  dryRunGame;
    private String  dryRunSnapshot;
    private String  dryRunStore;
    private boolean dryRunPlayedBefore;
    private int[] dryRunLedgerMarks;

    // Emergency freeze
    private boolean frozen;
    private BukkitTask freezeTask;
    private final java.util.Map<UUID, GameMode> frozenModes = new java.util.HashMap<>();

    public DirectorService(KMCCore plugin) {
        this.plugin  = plugin;
        this.presets = new PresetManager(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public PresetManager presets() { return presets; }

    // ── Dry-run ───────────────────────────────────────────────────────────────

    public boolean isDryRunning() { return dryRunGame != null; }
    public String  getDryRunGame() { return dryRunGame; }

    /**
     * Starts {@code gameId} in dry-run mode.
     *
     * @return null on success, otherwise a Dutch error message
     */
    public String startDryRun(String gameId) {
        if (isDryRunning()) return "Er draait al een dry-run (" + dryRunGame + ").";
        var am = plugin.getAutomationManager();
        if (am != null && am.getState() != nl.kmc.kmccore.managers.AutomationManager.State.IDLE)
            return "Stop eerst de automatisering (/kmcauto stop) — een dry-run draait los van het toernooi.";
        var gm = plugin.getGameManager();
        if (gm.isGameActive()) return "Er is al een game actief.";
        KMCGame game = gm.getGame(gameId);
        if (game == null) return "Onbekende game: " + gameId;

        dryRunPlayedBefore = gm.getPlayedGamesThisTournament().contains(gameId);
        dryRunSnapshot = "dryrun-" + gameId + "-" + System.currentTimeMillis();
        plugin.getSnapshotManager().snapshot(dryRunSnapshot);
        dryRunStore = plugin.getTournamentDataStore().exportState();
        var ledger = plugin.getPointsLedger();
        dryRunLedgerMarks = new int[]{ledger.size(), ledger.highlightCount(), ledger.lastGameSeq()};
        dryRunGame = gameId;
        DryRun.begin(gameId);

        Bukkit.broadcastMessage("§6§l[KMC] §eDRY-RUN §7— §f" + game.getDisplayName()
                + " §7wordt gespeeld zonder punten of statistieken.");
        if (!gm.startGame(gameId)) {
            finishDryRun(false);
            return "De game kon niet gestart worden.";
        }
        return null;
    }

    /** Aborts a running dry-run (admin). */
    public boolean stopDryRun() {
        if (!isDryRunning()) return false;
        var gm = plugin.getGameManager();
        if (gm.isGameActive()) gm.forceSkipCurrentGame();
        finishDryRun(false);
        return true;
    }

    /** Called by the game API when any game signals that it is over. */
    public void onGameSignalEnd(String gameId, String winner) {
        if (!isDryRunning() || !gameId.equals(dryRunGame)) return;
        // Let the game's own end-of-game handling finish first.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isDryRunning()) return;
            if (plugin.getGameManager().isGameActive()) plugin.getGameManager().stopGame(winner != null ? winner : "dry-run");
            finishDryRun(true);
        });
    }

    private void finishDryRun(boolean completed) {
        String game = dryRunGame;
        int blocked = DryRun.suppressedCount();
        // Safety net: put every number back exactly as it was.
        try { plugin.getSnapshotManager().restore(dryRunSnapshot); } catch (Throwable ignored) { }
        try { plugin.getTournamentDataStore().importState(dryRunStore); } catch (Throwable ignored) { }
        if (dryRunLedgerMarks != null)
            plugin.getPointsLedger().truncate(dryRunLedgerMarks[0], dryRunLedgerMarks[1], dryRunLedgerMarks[2]);
        dryRunLedgerMarks = null;
        if (!dryRunPlayedBefore) plugin.getGameManager().unmarkPlayed(game);
        DryRun.end();
        dryRunGame = null;
        dryRunSnapshot = null;
        dryRunStore = null;

        if (completed && game != null) {
            plugin.getConfig().set("director.tested." + game, System.currentTimeMillis());
            plugin.saveConfig();
        }
        try { plugin.getScoreboardManager().refreshAll(); } catch (Throwable ignored) { }
        try { plugin.getTabListManager().refreshAll(); } catch (Throwable ignored) { }
        Bukkit.broadcastMessage("§6§l[KMC] §eDRY-RUN " + (completed ? "klaar" : "gestopt") + " §7— "
                + blocked + " beloning(en) genegeerd, toernooistand ongewijzigd.");
    }

    /** True once a dry-run of this game finished normally (shown in the checklist). */
    public boolean isTested(String gameId) {
        return plugin.getConfig().getLong("director.tested." + gameId, 0L) > 0;
    }

    // ── Emergency freeze ──────────────────────────────────────────────────────

    public boolean isFrozen() { return frozen; }

    public void setFrozen(boolean on) {
        if (on == frozen) return;
        frozen = on;
        if (on) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§c§l⏸ PAUZE", "§7Even wachten...", 5, 60, 10);
            }
            freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::holdEveryoneStill, 1L, 2L);
            Bukkit.broadcastMessage("§6§l[KMC] §c⏸ Noodpauze §7— iedereen staat even stil.");
        } else {
            if (freezeTask != null) { freezeTask.cancel(); freezeTask = null; }
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§a§l▶ DOOR!", "", 0, 25, 10);
            }
            Bukkit.broadcastMessage("§6§l[KMC] §a▶ Het spel gaat verder.");
        }
    }

    private void holdEveryoneStill() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            if (p.getVehicle() != null) p.getVehicle().setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            p.sendActionBar(net.kyori.adventure.text.Component.text("§c⏸ Pauze — even geduld"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!frozen) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || e.getPlayer().getGameMode() == GameMode.SPECTATOR) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());       // looking around stays possible
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (frozen && e.getEntity() instanceof Player) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (frozen && e.getPlayer().getGameMode() != GameMode.SPECTATOR) e.setCancelled(true);
    }

    public List<String> describeStatus() {
        List<String> out = new ArrayList<>();
        out.add("&7Dry-run: " + (isDryRunning() ? "&e" + dryRunGame : "&7uit"));
        out.add("&7Noodpauze: " + (frozen ? "&caan" : "&7uit"));
        return out;
    }
}
