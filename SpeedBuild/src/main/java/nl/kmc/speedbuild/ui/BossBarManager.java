package nl.kmc.speedbuild.ui;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player boss bar showing overall challenge progress (builds completed). */
public final class BossBarManager {

    private final Map<UUID, BossBar> bars = new HashMap<>();

    public void show(Player p) {
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(),
                u -> Bukkit.createBossBar("§e§lSpeed Build", BarColor.YELLOW, BarStyle.SEGMENTED_10));
        bar.addPlayer(p);
        bar.setVisible(true);
    }

    public void update(Player p, int buildNumber, double totalScore) {
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) return;
        bar.setProgress(Math.max(0, Math.min(1, (buildNumber - 1) / 10.0)));
        bar.setTitle("§e§lSpeed Build §8| §fBuild §e" + buildNumber + "§7/§e10 §8| §6" + (int) totalScore + " ptn");
    }

    public void remove(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    public void clearAll() {
        bars.values().forEach(BossBar::removeAll);
        bars.clear();
    }
}
