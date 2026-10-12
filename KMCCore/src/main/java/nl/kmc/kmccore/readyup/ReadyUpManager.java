package nl.kmc.kmccore.readyup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Team ready check, used after a break (see {@code automation.ready-check} in config.yml).
 *
 * <p>Every team with at least one online member gets a clickable chat message
 * "Is jullie team ready?". Anyone in the team can click it (or type {@code /kmcready}); that marks the
 * <em>team</em> ready. The check passes when every required team is ready, or when the timeout runs out
 * (teams that did not answer are named and the tournament carries on — one sleeping team must not stall
 * the whole event). An admin can end it at any time with {@code /kmcready force}.
 */
public class ReadyUpManager implements Listener {

    private final KMCCore plugin;
    /** Teams that have to answer (ids). */
    private final Set<String> required = new LinkedHashSet<>();
    private final Set<String> ready    = new LinkedHashSet<>();
    private BukkitTask timeoutTask;
    private BukkitTask uiTask;
    private Runnable onComplete;
    private long startMs;
    private int timeoutSeconds;
    /** Short label shown in the message ("de volgende game", "de finale"). */
    private String purposeKey = "director.ready.purpose.next";

    public ReadyUpManager(KMCCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Ready check for every team that has an online member. */
    public void beginReadyCheck(int timeoutSeconds, Runnable onComplete) {
        beginReadyCheck(timeoutSeconds, null, "director.ready.purpose.next", onComplete);
    }

    /**
     * @param only       the teams that must answer, or {@code null} for every team with an online member
     * @param purposeKey language key describing what the teams are getting ready for
     */
    public void beginReadyCheck(int timeoutSeconds, Collection<String> only, String purposeKey, Runnable onComplete) {
        if (this.onComplete != null) finish(false, true);          // never two checks at once
        this.ready.clear();
        this.required.clear();
        this.startMs = System.currentTimeMillis();
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.onComplete = onComplete;
        this.purposeKey = purposeKey != null ? purposeKey : "director.ready.purpose.next";

        for (KMCTeam team : plugin.getTeamManager().getAllTeams()) {
            if (only != null && !only.contains(team.getId())) continue;
            if (onlineMembers(team).isEmpty()) continue;
            required.add(team.getId());
        }
        if (required.isEmpty()) { finish(true, false); return; }

        for (String teamId : required) {
            KMCTeam team = plugin.getTeamManager().getTeam(teamId);
            if (team != null) for (Player p : onlineMembers(team)) sendPrompt(p, team);
        }

        uiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickUi, 0L, 20L);
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(false, false), this.timeoutSeconds * 20L);
    }

    /** Marks the team of {@code p} as ready. @return false if there is no check or the player has no team. */
    public boolean markReady(Player p) {
        if (onComplete == null) return false;
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (team == null || !required.contains(team.getId())) return false;
        if (!ready.add(team.getId())) {
            p.sendMessage(tr(p, "director.ready.already"));
            return true;
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            v.sendMessage(tr(v, "director.ready.team-ready", team.getColor() + team.getDisplayName(),
                    ready.size(), required.size()));
        }
        for (Player m : onlineMembers(team)) m.playSound(m.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);
        if (ready.containsAll(required)) finish(true, false);
        return true;
    }

    /** Ends the check immediately (admin). */
    public void forceSkip() { finish(true, true); }

    public boolean isActive() { return onComplete != null; }

    public boolean isReady(UUID uuid) {
        KMCTeam t = plugin.getTeamManager().getTeamByPlayer(uuid);
        return t != null && ready.contains(t.getId());
    }

    public Set<String> getReadyTeams()    { return Collections.unmodifiableSet(ready); }
    public Set<String> getRequiredTeams() { return Collections.unmodifiableSet(required); }

    public void shutdown() {
        if (timeoutTask != null) timeoutTask.cancel();
        if (uiTask != null) uiTask.cancel();
        timeoutTask = null; uiTask = null;
        ready.clear(); required.clear();
        onComplete = null;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private List<Player> onlineMembers(KMCTeam team) {
        List<Player> out = new ArrayList<>();
        for (UUID id : team.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    private String tr(Player p, String key, Object... args) {
        return plugin.getLanguageManager().tr(p, key, args);
    }

    private void sendPrompt(Player p, KMCTeam team) {
        p.sendMessage("");
        p.sendMessage(tr(p, "director.ready.header"));
        p.sendMessage(tr(p, "director.ready.question", team.getColor() + team.getDisplayName(),
                tr(p, purposeKey), timeoutSeconds));
        Component button = LegacyComponentSerializer.legacySection()
                .deserialize(MessageUtil.color(tr(p, "director.ready.button")))
                .clickEvent(ClickEvent.runCommand("/kmcready"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        org.bukkit.ChatColor.stripColor(MessageUtil.color(tr(p, "director.ready.hover"))))));
        p.sendMessage(button);
        p.sendMessage("");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        p.sendTitle(tr(p, "director.ready.title"), tr(p, "director.ready.sub"), 5, 50, 10);
    }

    private void tickUi() {
        if (onComplete == null) return;
        long elapsed = (System.currentTimeMillis() - startMs) / 1000L;
        long remaining = Math.max(0, timeoutSeconds - elapsed);
        for (Player p : Bukkit.getOnlinePlayers()) {
            KMCTeam mine = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
            String status = mine != null && required.contains(mine.getId())
                    ? (ready.contains(mine.getId()) ? tr(p, "director.ready.bar-ready") : tr(p, "director.ready.bar-click"))
                    : tr(p, "director.ready.bar-wait");
            p.sendActionBar(Component.text(MessageUtil.color(status + " &8| &7" + ready.size() + "/" + required.size()
                    + " teams &8| &b" + remaining + "s")));
        }
    }

    private void finish(boolean allReady, boolean forced) {
        if (onComplete == null) return;
        Runnable cb = onComplete;
        onComplete = null;
        if (timeoutTask != null) { timeoutTask.cancel(); timeoutTask = null; }
        if (uiTask != null) { uiTask.cancel(); uiTask = null; }

        if (allReady && !forced) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(tr(p, "director.ready.all-ready"));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.5f);
            }
        } else if (forced) {
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(tr(p, "director.ready.forced"));
        } else {
            List<String> missing = new ArrayList<>();
            for (String id : required) {
                if (ready.contains(id)) continue;
                KMCTeam t = plugin.getTeamManager().getTeam(id);
                if (t != null) missing.add(t.getColor() + t.getDisplayName());
            }
            for (Player p : Bukkit.getOnlinePlayers())
                p.sendMessage(tr(p, "director.ready.timeout", String.join("§7, ", missing)));
        }
        ready.clear();
        required.clear();

        try { cb.run(); } catch (Exception e) {
            plugin.getLogger().warning("ReadyCheck callback failed: " + e.getMessage());
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (onComplete == null) return;
        Player p = event.getPlayer();
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (team == null) return;
        // A team that was offline when the check began may now be part of it.
        if (!required.contains(team.getId()) && !ready.contains(team.getId())) required.add(team.getId());
        if (!ready.contains(team.getId())) sendPrompt(p, team);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (onComplete == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (onComplete == null) return;
            // A team whose last member left can't answer — drop it from the check.
            required.removeIf(id -> {
                KMCTeam t = plugin.getTeamManager().getTeam(id);
                return t == null || (onlineMembers(t).isEmpty() && !ready.contains(id));
            });
            if (required.isEmpty() || ready.containsAll(required)) finish(true, false);
        });
    }
}
