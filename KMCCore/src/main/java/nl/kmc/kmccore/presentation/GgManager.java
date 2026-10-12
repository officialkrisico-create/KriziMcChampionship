package nl.kmc.kmccore.presentation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The GG button: after a game everybody gets a clickable "GG" in chat for a short window. One click per
 * player per game puts a small burst of hearts over their head and a short "name: GG" line in chat, and
 * counts towards their sportsmanship total (shown in the profile).
 */
public final class GgManager {

    private final KMCCore plugin;
    private final Set<UUID> sentThisWindow = new HashSet<>();
    private long openUntilMs;

    public GgManager(KMCCore plugin) { this.plugin = plugin; }

    /** Opens a GG window and shows the clickable button to everyone. */
    public void open() {
        if (!plugin.getConfig().getBoolean("gg.enabled", true)) return;
        int seconds = Math.max(10, plugin.getConfig().getInt("gg.window-seconds", 45));
        sentThisWindow.clear();
        openUntilMs = System.currentTimeMillis() + seconds * 1000L;
        var lm = plugin.getLanguageManager();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Component btn = LegacyComponentSerializer.legacySection()
                    .deserialize(lm.tr(p, "gg.button"))
                    .clickEvent(ClickEvent.runCommand("/gg"))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            org.bukkit.ChatColor.stripColor(lm.tr(p, "gg.hover")))));
            p.sendMessage(btn);
        }
    }

    /** A player pressed the button (or typed /gg). */
    public void send(Player p) {
        var lm = plugin.getLanguageManager();
        if (System.currentTimeMillis() > openUntilMs) { p.sendMessage(lm.tr(p, "gg.closed")); return; }
        if (!sentThisWindow.add(p.getUniqueId())) { p.sendMessage(lm.tr(p, "gg.already")); return; }

        var team = plugin.getTeamManager().getTeamByPlayer(p.getUniqueId());
        String name = (team != null ? team.getColor().toString() : "§f") + p.getName();
        for (Player v : Bukkit.getOnlinePlayers()) v.sendMessage(lm.tr(v, "gg.line", name));
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2.1, 0), 6, 0.4, 0.2, 0.4, 0);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.7f);
        try { plugin.getTournamentDataStore().addGg(p.getUniqueId()); } catch (Throwable ignored) { }
    }
}
