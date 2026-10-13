package nl.kmc.kmccore.platform;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.TeamProfileGui;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /clip, /kmcaudit, /kmcemblem, /teamprofile, /kmchud and /kmcapi (see plugin.yml). */
public final class PlatformCommands implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;
    private final Map<UUID, Long> clipCooldown = new HashMap<>();

    public PlatformCommands(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        var lm = plugin.getLanguageManager();
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "clip" -> clip(s, a);
            case "kmcaudit" -> audit(s, a);
            case "kmcapi" -> api(s, a);
            case "kmchud" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                if (a.length > 0 && a[0].equalsIgnoreCase("test")) {
                    HudTest.start(plugin, p);
                    return true;
                }
                String mode = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : HudScale.next(HudScale.get(plugin, p));
                if (!HudScale.MODES.contains(mode)) { p.sendMessage(lm.tr(p, "platform.hud.usage")); return true; }
                HudScale.set(plugin, p, mode);
                p.sendMessage(lm.tr(p, "platform.hud.set", mode));
                try { plugin.getScoreboardManager().forceRefreshPlayer(p); } catch (Throwable ignored) { }
            }
            case "kmcspec" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                var sm = plugin.getSpectatorManager();
                if (sm == null || !sm.isSpectating(p)) { p.sendMessage(lm.tr(p, "spectator.not-spectating")); return true; }
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "follow" -> sm.cycleFollow(p);
                    case "stop" -> sm.stopFollow(p);
                    default -> sm.openMenu(p);
                }
            }
            case "kmcemblem" -> emblem(s, a);
            case "teamprofile" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return true; }
                KMCTeam team = null;
                if (a.length > 0) team = plugin.getTeamManager().getTeam(a[0]);
                if (team == null) team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
                if (team == null) {
                    var all = plugin.getTeamManager().getTeamsSortedByPoints();
                    if (all.isEmpty()) { p.sendMessage(lm.tr(p, "platform.teaminfo.none")); return true; }
                    team = all.get(0);
                }
                new TeamProfileGui(plugin, p, team).open(p);
            }
            default -> { }
        }
        return true;
    }

    // ── /clip ─────────────────────────────────────────────────────────────────

    private void clip(CommandSender s, String[] a) {
        var lm = plugin.getLanguageManager();
        var clips = plugin.getClipMarkers();
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("start")) {
            if (!s.hasPermission("kmc.clip.admin")) { s.sendMessage(lm.tr(s, "common.no-permission")); return; }
            clips.startClock();
            s.sendMessage(lm.tr(s, "platform.clip.started"));
            return;
        }
        if (sub.equals("list") || a.length == 0) {
            var recent = clips.recent(a.length > 1 ? parse(a[1], 8) : 8);
            if (recent.isEmpty()) { s.sendMessage(lm.tr(s, "platform.clip.empty")); return; }
            s.sendMessage(lm.tr(s, "platform.clip.header", clips.count()));
            for (var m : recent) s.sendMessage("§8" + m.timecode() + " §7[" + m.category() + "] §f" + m.label());
            return;
        }
        if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return; }
        long now = System.currentTimeMillis();
        if (now - clipCooldown.getOrDefault(p.getUniqueId(), 0L) < 5000) {
            p.sendMessage(lm.tr(p, "platform.clip.cooldown"));
            return;
        }
        clipCooldown.put(p.getUniqueId(), now);
        String text = String.join(" ", a);
        clips.add("player", text.length() > 80 ? text.substring(0, 80) : text, p.getName());
        p.sendMessage(lm.tr(p, "platform.clip.added", clips.recent(1).get(0).timecode()));
    }

    // ── /kmcaudit ─────────────────────────────────────────────────────────────

    private void audit(CommandSender s, String[] a) {
        var lm = plugin.getLanguageManager();
        if (!s.hasPermission("kmc.audit")) { s.sendMessage(lm.tr(s, "common.no-permission")); return; }
        var g = plugin.getSafeTeleportGuard();
        if (a.length > 0 && a[0].equalsIgnoreCase("clear")) {
            g.clear();
            s.sendMessage(lm.tr(s, "platform.audit.cleared"));
            return;
        }
        s.sendMessage(lm.tr(s, "platform.audit.header", g.checked(), g.corrected(), g.voidWarnings()));
        var list = g.findings();
        if (list.isEmpty()) s.sendMessage(lm.tr(s, "platform.audit.clean"));
        for (var f : list.stream().limit(12).toList()) {
            s.sendMessage("§7" + f.caller() + " §8→ §f" + f.world() + " " + f.x() + "," + f.y() + "," + f.z()
                    + " §8(" + f.kind() + ", " + f.player() + ")");
        }
        // The lobby spawn is the one every player is sent to: check it directly.
        var lobby = plugin.getArenaManager().getLobby();
        if (lobby != null) {
            boolean blocked = !lobby.getBlock().isPassable() || !lobby.getBlock().getRelative(0, 1, 0).isPassable();
            s.sendMessage(lm.tr(s, blocked ? "platform.audit.lobby-bad" : "platform.audit.lobby-ok"));
        }
    }

    // ── /kmcapi ───────────────────────────────────────────────────────────────

    private void api(CommandSender s, String[] a) {
        var lm = plugin.getLanguageManager();
        if (!s.hasPermission("kmc.api.admin")) { s.sendMessage(lm.tr(s, "common.no-permission")); return; }
        var srv = plugin.getLiveApi();
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "start" -> {
                plugin.getConfig().set("live-api.enabled", true);
                srv.start();
                s.sendMessage(lm.tr(s, srv.isRunning() ? "platform.api.started" : "platform.api.failed"));
            }
            case "stop" -> { srv.stop(); s.sendMessage(lm.tr(s, "platform.api.stopped")); }
            default -> s.sendMessage(lm.tr(s, srv.isRunning() ? "platform.api.on" : "platform.api.off",
                    plugin.getConfig().getString("live-api.bind", "127.0.0.1"),
                    plugin.getConfig().getInt("live-api.port", 8765)));
        }
    }

    // ── /kmcemblem ────────────────────────────────────────────────────────────

    private void emblem(CommandSender s, String[] a) {
        var lm = plugin.getLanguageManager();
        if (!(s instanceof Player p)) { s.sendMessage("§cAlleen spelers."); return; }
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (a.length > 0 && !a[0].equalsIgnoreCase("wear") && !a[0].equalsIgnoreCase("give")) {
            KMCTeam other = plugin.getTeamManager().getTeam(a[0]);
            if (other != null) team = other;
        }
        if (team == null) { p.sendMessage(lm.tr(p, "platform.emblem.no-team")); return; }
        ItemStack banner = plugin.getTeamEmblems().banner(team);
        if (a.length > 0 && a[0].equalsIgnoreCase("wear")) {
            p.getInventory().setHelmet(banner);
            p.sendMessage(lm.tr(p, "platform.emblem.worn"));
        } else {
            p.getInventory().addItem(banner);
            p.sendMessage(lm.tr(p, "platform.emblem.given"));
        }
    }

    private static int parse(String s, int def) {
        try { return Math.max(1, Math.min(30, Integer.parseInt(s))); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (a.length != 1) return List.of();
        return switch (n) {
            case "kmchud" -> HudScale.MODES;
            case "kmcspec" -> List.of("menu", "follow", "stop");
            case "kmcapi" -> List.of("status", "start", "stop");
            case "kmcaudit" -> List.of("clear");
            case "clip" -> List.of("list", "start");
            case "teamprofile", "kmcemblem" -> {
                List<String> out = new ArrayList<>();
                if (n.equals("kmcemblem")) { out.add("give"); out.add("wear"); }
                for (KMCTeam t : plugin.getTeamManager().getAllTeams()) out.add(t.getId());
                yield out;
            }
            default -> List.of();
        };
    }
}
