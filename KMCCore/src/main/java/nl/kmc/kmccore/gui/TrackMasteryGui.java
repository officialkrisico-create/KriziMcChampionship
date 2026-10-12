package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.scoring.TrackRecords;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track Mastery: for every track (circuit / course) the all-time record, your personal best, the gap to the
 * record and the top 3. Click a track to see the sector records in chat.
 */
public final class TrackMasteryGui extends Gui {

    private final KMCCore plugin;

    public TrackMasteryGui(KMCCore plugin, Player viewer) {
        super("&1&lBaanrecords & Track Mastery", 6);
        this.plugin = plugin;
        render(viewer);
    }

    private void render(Player viewer) {
        TrackRecords tr = plugin.getTrackRecords();
        UUID me = viewer.getUniqueId();
        List<String[]> tracks = tr.tracks();
        int mastered = 0, driven = 0;

        int slot = 10;
        for (String[] t : tracks) {
            String game = t[0], track = t[1];
            var rec = tr.record(game, track, 0);
            long best = tr.best(game, track, 0, me);
            if (best >= 0) driven++;
            if (rec != null && best >= 0 && best == rec.millis()) mastered++;
            if (slot >= 44) continue;
            if (slot % 9 == 8) slot += 2;   // skip the border columns

            KMCGame g = plugin.getGameManager().getGame(game);
            Material icon = g != null && g.getIcon() != null ? g.getIcon() : Material.FILLED_MAP;
            List<String> lore = new ArrayList<>();
            lore.add("&7Game: &f" + (g != null ? g.getDisplayName() : game));
            lore.add("&7Record: &6" + (rec != null ? TrackRecords.format(rec.millis()) + " &8— &f" + rec.name() : "—"));
            lore.add("&7Jouw beste: " + (best >= 0 ? "&a" + TrackRecords.format(best) : "&8nog geen tijd"));
            if (rec != null && best >= 0) {
                long gap = best - rec.millis();
                lore.add("&7Verschil: " + (gap == 0 ? "&6&lJIJ HEBT HET RECORD!" : "&c+" + TrackRecords.format(gap)));
            }
            var top = tr.top(game, track, 0, 3);
            if (!top.isEmpty()) {
                lore.add("");
                lore.add("&8Top 3:");
                for (int i = 0; i < top.size(); i++)
                    lore.add("  &7" + (i + 1) + ". &f" + top.get(i).name() + " &8— &e" + TrackRecords.format(top.get(i).millis()));
            }
            if (!tr.sectors(game, track).isEmpty()) {
                lore.add("");
                lore.add("&eKlik voor de sector-records");
            }
            final String fGame = game, fTrack = track;
            button(slot, item(icon, "&e&l" + track, lore.toArray(new String[0])), p -> showSectors(p, fGame, fTrack));
            slot++;
        }

        set(4, item(Material.CLOCK, "&6&lTrack Mastery",
                "&7Banen met een tijd: &e" + tracks.size(),
                "&7Jij hebt gereden: &f" + driven,
                "&7Records van jou: &6" + mastered));
        if (tracks.isEmpty()) set(22, item(Material.BARRIER, "&7Nog geen baantijden", "&8Race een game om records te zetten."));
        fillEmpty();
    }

    private void showSectors(Player p, String game, String track) {
        TrackRecords tr = plugin.getTrackRecords();
        var sectors = tr.sectors(game, track);
        if (sectors.isEmpty()) return;
        p.closeInventory();
        p.sendMessage("§6§m                                        ");
        p.sendMessage("  §e§l" + track + " §7— sector-records");
        for (int s : sectors) {
            var rec = tr.record(game, track, s);
            long mine = tr.best(game, track, s, p.getUniqueId());
            p.sendMessage("  §7S" + s + ": §6" + (rec != null ? TrackRecords.format(rec.millis()) + " §8(" + rec.name() + ")" : "—")
                    + "  §7jij: §a" + (mine >= 0 ? TrackRecords.format(mine) : "—"));
        }
        p.sendMessage("§6§m                                        ");
    }
}
