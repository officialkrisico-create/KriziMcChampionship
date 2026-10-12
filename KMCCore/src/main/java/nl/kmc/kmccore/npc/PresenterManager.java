package nl.kmc.kmccore.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;

/**
 * The presenter: an NPC in the lobby that announces the next game, the results of a game and the finale
 * in a speech bubble above its head (and with a "[Presentator]" line in everybody's chat, in their own
 * language). Place it with {@code /kmcnpc presenter set}; give it a skin with
 * {@code /kmcnpc presenter skin <playername>}.
 */
public final class PresenterManager {

    private final KMCCore plugin;
    private final File file;
    private Location spot;
    private String skinName;
    private Mannequin body;
    private TextDisplay bubble;
    private BukkitTask hideTask;

    public PresenterManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "presenter.yml");
        load();
        if (spot != null) Bukkit.getScheduler().runTaskLater(plugin, this::spawn, 40L);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public void setSpot(Location where) {
        removeEntities();
        spot = where.clone();
        save();
        spawn();
    }

    public boolean remove() {
        if (spot == null) return false;
        removeEntities();
        spot = null;
        save();
        return true;
    }

    public void setSkin(String playerName) {
        this.skinName = playerName;
        save();
        if (body != null && body.isValid()) applySkin();
    }

    public boolean isPlaced() { return spot != null; }

    // ── Speaking ──────────────────────────────────────────────────────────────

    /**
     * Presenter says something. {@code key} is a language key: everybody gets a chat line in their own
     * language; the bubble shows the server default language.
     */
    public void say(String key, int seconds, Object... args) {
        if (!plugin.getConfig().getBoolean("presenter.enabled", true)) return;
        var lm = plugin.getLanguageManager();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(lm.tr(p, "presenter.prefix") + lm.tr(p, key, args));
        }
        if (spot == null) return;
        ensureSpawned();
        String bubbleText = lm.tr("nl", key, args);
        if (bubble != null && bubble.isValid()) {
            bubble.text(LegacyComponentSerializer.legacySection().deserialize("§f§l" + wrap(bubbleText)));
            if (hideTask != null) hideTask.cancel();
            hideTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (bubble != null && bubble.isValid()) bubble.text(Component.empty());
            }, Math.max(3, seconds) * 20L);
        }
        if (spot.getWorld() != null)
            spot.getWorld().playSound(spot, Sound.ENTITY_VILLAGER_YES, 1f, 1.1f);
    }

    /** Soft-wraps a bubble at ~28 characters. */
    private static String wrap(String s) {
        String plain = org.bukkit.ChatColor.stripColor(s);
        if (plain.length() <= 30) return s;
        StringBuilder out = new StringBuilder();
        int line = 0;
        for (String w : s.split(" ")) {
            int len = org.bukkit.ChatColor.stripColor(w).length();
            if (line + len > 28) { out.append('\n'); line = 0; }
            out.append(w).append(' ');
            line += len + 1;
        }
        return out.toString().trim();
    }

    // ── Entities ──────────────────────────────────────────────────────────────

    private void ensureSpawned() {
        if (body == null || !body.isValid() || bubble == null || !bubble.isValid()) spawn();
    }

    private void spawn() {
        if (spot == null || spot.getWorld() == null) return;
        removeEntities();
        try {
            body = spot.getWorld().spawn(spot, Mannequin.class, m -> {
                m.setImmovable(true);
                m.setGravity(false);
                m.setInvulnerable(true);
                m.setSilent(true);
                m.setPersistent(false);
                m.setDescription(null);
                m.setCustomNameVisible(false);
            });
            applySkin();
            bubble = spot.getWorld().spawn(spot.clone().add(0, 2.4, 0), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
                d.text(Component.empty());
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Presenter NPC unavailable: " + t);
        }
    }

    private void applySkin() {
        if (body == null || skinName == null || skinName.isBlank()) return;
        try {
            body.setProfile(ResolvableProfile.resolvableProfile().name(skinName.length() > 16
                    ? skinName.substring(0, 16) : skinName).build());
        } catch (Throwable ignored) { }
    }

    private void removeEntities() {
        if (hideTask != null) { hideTask.cancel(); hideTask = null; }
        if (body != null) { body.remove(); body = null; }
        if (bubble != null) { bubble.remove(); bubble = null; }
    }

    public void shutdown() { removeEntities(); }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        spot = y.getLocation("spot");
        skinName = y.getString("skin");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (spot != null) y.set("spot", spot);
        if (skinName != null) y.set("skin", skinName);
        try { y.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Could not save presenter.yml: " + e.getMessage()); }
    }
}
