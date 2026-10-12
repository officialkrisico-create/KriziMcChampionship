package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.EditionArchive;
import nl.kmc.kmccore.scoring.EditionArchive.Edition;
import nl.kmc.kmccore.scoring.SeasonRanking;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Season / all-time ranking over every archived edition: best players, best teams and the editions
 * themselves (champion, rounds, crowd rating). Left-click the bottom button to switch between
 * "all time" and "this season" (the last {@code season.editions} editions).
 */
public final class SeasonGui extends Gui {

    private final KMCCore plugin;
    private final boolean seasonOnly;

    public SeasonGui(KMCCore plugin, boolean seasonOnly) {
        super(seasonOnly ? "&1&lSeizoen" : "&1&lAll-time ranglijst", 6);
        this.plugin = plugin;
        this.seasonOnly = seasonOnly;
        render();
    }

    private void render() {
        List<Edition> all = EditionArchive.readAll(plugin);
        int seasonSize = Math.max(1, plugin.getConfig().getInt("season.editions", 4));
        List<Edition> used = seasonOnly ? SeasonRanking.lastN(all, seasonSize) : all;

        set(4, item(Material.NETHER_STAR, seasonOnly ? "&6&lDit seizoen" : "&6&lAll-time",
                "&7Edities: &e" + used.size() + (seasonOnly ? "&7 (laatste " + seasonSize + ")" : ""),
                used.isEmpty() ? "&8Nog geen afgeronde toernooien." : ""));

        // Players: top 10 in two rows.
        var players = SeasonRanking.players(used);
        for (int i = 0; i < Math.min(10, players.size()); i++) {
            var p = players.get(i);
            int slot = (i < 5 ? 10 : 19) + (i % 5);
            set(slot, head(Bukkit.getOfflinePlayer(p.uuid()),
                    (i == 0 ? "&6&l" : "&e") + "#" + (i + 1) + " " + p.name(),
                    "&7Punten: &a" + p.points(),
                    "&7Edities: &f" + p.editions() + " &8| &7Titels: &6" + p.editionWins(),
                    "&7Kills: &c" + p.kills() + " &8| &7Game-wins: &f" + p.wins(),
                    "&7Beste plek: &f#" + p.bestRank()));
        }

        // Teams: top 7.
        var teams = SeasonRanking.teams(used);
        for (int i = 0; i < Math.min(7, teams.size()); i++) {
            var t = teams.get(i);
            set(28 + i, item(i == 0 ? Material.GOLDEN_HELMET : Material.WHITE_BANNER,
                    (i == 0 ? "&6&l" : "&f") + "#" + (i + 1) + " " + t.name(),
                    "&7Titels: &6" + t.titles(),
                    "&7Punten: &a" + t.points(),
                    "&7Edities: &f" + t.editions() + " &8| &7Beste: &f#" + t.bestRank()));
        }

        // Editions: the latest 7.
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", new Locale("nl"));
        int start = Math.max(0, used.size() - 7);
        for (int i = start; i < used.size(); i++) {
            Edition e = used.get(i);
            set(37 + (i - start), item(Material.PAPER, "&e&lKMC #" + e.number(),
                    "&7Datum: &f" + df.format(new Date(e.date())),
                    "&7Kampioen: &6" + (e.championName() != null ? e.championName() : "—"),
                    "&7Rondes: &f" + e.rounds() + " &8| &7Spelers: &f" + e.players().size(),
                    e.feedbackVotes() > 0
                            ? "&7Beoordeling: &a" + String.format(Locale.ROOT, "%.1f", e.feedbackAverage()) + "&7/5 &8(" + e.feedbackVotes() + ")"
                            : "&8Geen beoordelingen"));
        }

        button(49, item(seasonOnly ? Material.BOOK : Material.CLOCK,
                        seasonOnly ? "&b&lToon all-time" : "&b&lToon dit seizoen",
                        "&7Wisselt tussen alle edities en de laatste " + seasonSize + "."),
                p -> new SeasonGui(plugin, !seasonOnly).open(p));
        fillEmpty();
    }
}
