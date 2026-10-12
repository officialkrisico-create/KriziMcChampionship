package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Player Profile GUI — a polished overview of a player's tournament stats,
 * team, streaks, favourite game and achievement progress.
 */
public final class ProfileGui extends Gui {

    public ProfileGui(KMCCore plugin, UUID target, String name) {
        super("&1Profiel: &9" + name, 5);
        render(plugin, target, name);
    }

    private void render(KMCCore plugin, UUID uuid, String name) {
        PlayerData pd = plugin.getPlayerDataManager().getOrCreate(uuid, name);
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);

        var team = plugin.getTeamManager().getTeamByPlayer(uuid);
        String teamLine = team != null
                ? team.getColor() + team.getDisplayName()
                : "&8Geen team";

        // Header — player head.
        set(4, head(off, "&e&l" + name,
                "&7Team: " + teamLine,
                "&7Punten: &a" + pd.getPoints()));

        double kd = pd.getDeaths() == 0 ? pd.getKills() : (double) pd.getKills() / pd.getDeaths();

        set(19, item(Material.DIAMOND_SWORD, "&c&lCombat",
                "&7Kills: &f" + pd.getKills(),
                "&7Deaths: &f" + pd.getDeaths(),
                "&7K/D: &f" + String.format("%.2f", kd)));

        set(21, item(Material.GOLD_INGOT, "&6&lPunten",
                "&7Totaal: &e" + pd.getPoints(),
                "&7Gewonnen games: &a" + pd.getWins()));

        set(22, item(Material.NETHER_STAR, "&b&lStreak",
                "&7Huidige winstreak: &f" + pd.getWinStreak(),
                "&7Beste streak ooit: &f" + pd.getBestWinStreak()));

        String fav = pd.getFavouriteGame();
        set(23, item(Material.NETHERITE_PICKAXE, "&d&lActiviteit",
                "&7Games gespeeld: &f" + pd.getGamesPlayed(),
                "&7Favoriete game: &f" + (fav != null ? fav : "—"),
                "&7Speeltijd: &f" + pd.getTotalPlayTimeMinutes() + " min"));

        // Medals (top-3 finishes across mini-games).
        int[] medals = plugin.getTournamentDataStore().getMedals(uuid);
        set(31, item(Material.GOLD_INGOT, "&6&lMedailles",
                "&6🥇 Goud: &f" + medals[0],
                "&f🥈 Zilver: &f" + medals[1],
                "&c🥉 Brons: &f" + medals[2],
                "&7Gebruik &e/kmcmedals &7voor de ranglijst"));

        // Last game: where the points came from (points ledger).
        var mine = plugin.getPointsLedger().forPlayer(uuid);
        int lastSeq = 0;
        for (var e : mine) lastSeq = Math.max(lastSeq, e.gameSeq());
        if (lastSeq > 0) {
            final int seq = lastSeq;
            var game = plugin.getPointsLedger().game(seq);
            var gm = game != null ? plugin.getGameManager().getGame(game.gameId()) : null;
            var es = mine.stream().filter(e -> e.gameSeq() == seq).toList();
            var by = nl.kmc.kmccore.scoring.PointsLedger.byReason(es);
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add("&7Game: &f" + (gm != null ? gm.getDisplayName() : "?"));
            int total = 0;
            for (var r : by.entrySet()) {
                lore.add("&7" + r.getKey().toLowerCase().replace('_', ' ') + ": &a+" + r.getValue());
                total += r.getValue();
            }
            lore.add("&7Totaal: &e+" + total);
            set(29, item(Material.PAPER, "&b&lLaatste game", lore.toArray(new String[0])));
        }

        // History over the archived editions.
        var editions = nl.kmc.kmccore.scoring.EditionArchive.readAll(plugin);
        int played = 0, titles = 0, best = Integer.MAX_VALUE;
        for (var ed : editions) {
            for (var pr : ed.players()) {
                if (!pr.uuid().equals(uuid)) continue;
                played++;
                best = Math.min(best, pr.rank());
                if (ed.championTeamId() != null && ed.championTeamId().equals(pr.teamId())) titles++;
            }
        }
        set(33, item(Material.BOOKSHELF, "&5&lEdities",
                "&7Gespeeld: &f" + played,
                "&7Titels: &6" + titles,
                "&7Beste plek: &f" + (best == Integer.MAX_VALUE ? "—" : "#" + best),
                "&7Gebruik &e/kmcseason &7voor de ranglijst"));

        // Achievements (V2).
        var ach = plugin.getAchievementServiceV2();
        if (ach != null) {
            int unlocked = ach.getUnlocked(uuid).size();
            int total    = ach.getAll().size();
            set(25, item(Material.EXPERIENCE_BOTTLE, "&a&lAchievements",
                    "&7Behaald: &f" + unlocked + "&7/&f" + total,
                    "&7Gebruik &e/kmcachievements &7voor details"));
        } else {
            set(25, item(Material.EXPERIENCE_BOTTLE, "&a&lAchievements",
                    "&7Gebruik &e/kmcachievements"));
        }

        fillEmpty();
    }
}
