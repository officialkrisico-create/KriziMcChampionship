package nl.kmc.kmccore.scoring;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.lang.LanguageManager;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.scoring.PointsLedger.Entry;
import nl.kmc.kmccore.scoring.PointsLedger.GameInfo;
import nl.kmc.kmccore.tournament.EventEndSequence;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.*;

/**
 * The personal end-of-event book: a clickable table of contents and, per page, YOUR tournament —
 * final result, game by game, where the points came from, your team, the standings, the awards and
 * your track records. Built from the points ledger, in the reader's own language.
 */
public final class StatsBook {

    private static final int GAMES_PER_PAGE = 6;

    private StatsBook() {}

    public static ItemStack build(KMCCore plugin, Player viewer, int edition) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;
        LanguageManager lm = plugin.getLanguageManager();
        UUID me = viewer.getUniqueId();

        meta.author(Component.text("KMC", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.title(Component.text("KMC #" + edition, NamedTextColor.GOLD));

        var ledger = plugin.getPointsLedger();
        List<Entry> mine = ledger.forPlayer(me);
        List<GameInfo> played = new ArrayList<>();
        for (GameInfo g : ledger.games()) if (!ledger.forGame(g.seq()).isEmpty()) played.add(g);

        // Page numbers first (the cover links to them).
        int gamePages = Math.max(1, (int) Math.ceil(played.size() / (double) GAMES_PER_PAGE));
        int pResult = 2, pGames = 3, pReasons = pGames + gamePages, pTeam = pReasons + 1,
                pStand = pTeam + 1, pAwards = pStand + 1, pRecords = pAwards + 1;

        List<Component> pages = new ArrayList<>();
        pages.add(cover(plugin, lm, viewer, edition, pResult, pGames, pReasons, pTeam, pStand, pAwards, pRecords));
        pages.add(result(plugin, lm, viewer, mine));
        pages.addAll(gamePages(plugin, lm, viewer, played, gamePages));
        pages.add(reasons(lm, viewer, mine));
        pages.add(team(plugin, lm, viewer));
        pages.add(standings(plugin, lm, viewer));
        pages.add(awards(plugin, lm, viewer));
        pages.add(records(plugin, lm, viewer));

        meta.addPages(pages.toArray(new Component[0]));
        book.setItemMeta(meta);
        return book;
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    private static Component cover(KMCCore plugin, LanguageManager lm, Player v, int edition,
                                   int pResult, int pGames, int pReasons, int pTeam, int pStand,
                                   int pAwards, int pRecords) {
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(v.getUniqueId());
        KMCTeam champ = plugin.getTournamentManager().getFinalStandings().stream().findFirst().orElse(null);
        StringBuilder head = new StringBuilder();
        head.append("§4§lKMC #").append(edition).append("\n§0").append(v.getName()).append("\n");
        if (team != null) head.append("§0").append(strip(team.getDisplayName())).append("\n");
        if (champ != null) head.append("§6★ §0").append(lm.tr(v, "book.champion", strip(champ.getDisplayName()))).append("\n");
        head.append("\n§8").append(lm.tr(v, "book.contents")).append("\n");

        Component page = ld(head.toString());
        page = page.append(link(lm.tr(v, "book.p.result"), pResult));
        page = page.append(link(lm.tr(v, "book.p.games"), pGames));
        page = page.append(link(lm.tr(v, "book.p.reasons"), pReasons));
        page = page.append(link(lm.tr(v, "book.p.team"), pTeam));
        page = page.append(link(lm.tr(v, "book.p.standings"), pStand));
        page = page.append(link(lm.tr(v, "book.p.awards"), pAwards));
        page = page.append(link(lm.tr(v, "book.p.records"), pRecords));
        return page;
    }

    private static Component result(KMCCore plugin, LanguageManager lm, Player v, List<Entry> mine) {
        UUID me = v.getUniqueId();
        var board = plugin.getPlayerDataManager().getLeaderboard();
        int rank = 0;
        for (int i = 0; i < board.size(); i++) if (board.get(i).getUuid().equals(me)) { rank = i + 1; break; }
        PlayerData pd = plugin.getPlayerDataManager().get(me);
        int points = pd != null ? pd.getPoints() : sum(mine);
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(me);
        int teamRank = 0;
        var teams = plugin.getTournamentManager().getFinalStandings();
        for (int i = 0; i < teams.size(); i++) if (team != null && teams.get(i).getId().equals(team.getId())) teamRank = i + 1;
        int[] medals = plugin.getTournamentDataStore().getMedals(me);
        double kd = pd == null ? 0 : pd.getDeaths() == 0 ? pd.getKills() : (double) pd.getKills() / pd.getDeaths();

        StringBuilder sb = new StringBuilder();
        sb.append("§4§l").append(lm.tr(v, "book.p.result")).append("\n§8━━━━━━━━━━━━\n");
        sb.append("§0").append(lm.tr(v, "book.points")).append(": §4§l").append(points).append("\n");
        sb.append("§0").append(lm.tr(v, "book.rank")).append(": §4#").append(rank).append("§8/").append(board.size()).append("\n");
        if (team != null) sb.append("§0").append(lm.tr(v, "book.team-rank")).append(": §4#").append(teamRank)
                .append("§8/").append(teams.size()).append("\n");
        if (pd != null) {
            sb.append("§0Kills: §4").append(pd.getKills()).append(" §8(K/D ").append(String.format(Locale.ROOT, "%.2f", kd)).append(")\n");
            sb.append("§0").append(lm.tr(v, "book.games-won")).append(": §4").append(pd.getWins())
                    .append("§8/").append(pd.getGamesPlayed()).append("\n");
        }
        sb.append("§0").append(lm.tr(v, "book.medals")).append(": §6").append(medals[0]).append("§8/§7").append(medals[1])
                .append("§8/§c").append(medals[2]);
        return ld(sb.toString()).append(back(lm, v));
    }

    private static List<Component> gamePages(KMCCore plugin, LanguageManager lm, Player v,
                                             List<GameInfo> played, int pageCount) {
        var ledger = plugin.getPointsLedger();
        UUID me = v.getUniqueId();
        List<Component> out = new ArrayList<>();
        for (int page = 0; page < pageCount; page++) {
            StringBuilder sb = new StringBuilder();
            sb.append("§4§l").append(lm.tr(v, "book.p.games")).append(pageCount > 1 ? " §8(" + (page + 1) + "/" + pageCount + ")" : "")
                    .append("\n§8━━━━━━━━━━━━\n");
            if (played.isEmpty()) sb.append("§8").append(lm.tr(v, "book.none"));
            for (int i = page * GAMES_PER_PAGE; i < Math.min(played.size(), (page + 1) * GAMES_PER_PAGE); i++) {
                GameInfo g = played.get(i);
                List<Entry> es = ledger.forGame(g.seq());
                var byPlayer = PointsLedger.byPlayer(es);
                int pts = byPlayer.getOrDefault(me, 0);
                int rank = 0, n = 0;
                for (UUID id : byPlayer.keySet()) { n++; if (id.equals(me)) rank = n; }
                var game = plugin.getGameManager().getGame(g.gameId());
                String name = game != null ? game.getDisplayName() : String.valueOf(g.gameId());
                sb.append("§0").append(i + 1).append(". §4").append(strip(name)).append("\n");
                sb.append("   §0+").append(pts).append(" §8").append(lm.tr(v, "book.pts"));
                if (rank > 0) sb.append(" §8· #").append(rank).append("/").append(n);
                sb.append("\n");
            }
            out.add(ld(sb.toString()).append(back(lm, v)));
        }
        return out;
    }

    private static Component reasons(LanguageManager lm, Player v, List<Entry> mine) {
        StringBuilder sb = new StringBuilder();
        sb.append("§4§l").append(lm.tr(v, "book.p.reasons")).append("\n§8━━━━━━━━━━━━\n");
        var by = PointsLedger.byReason(mine);
        int total = sum(mine);
        if (by.isEmpty()) sb.append("§8").append(lm.tr(v, "book.none"));
        int shown = 0;
        for (var e : by.entrySet()) {
            if (shown++ >= 9) break;
            int pct = total > 0 ? Math.round(100f * e.getValue() / total) : 0;
            sb.append("§0").append(ScoreReport.reasonLabel(lm, v, e.getKey())).append(": §4+").append(e.getValue())
                    .append(" §8(").append(pct).append("%)\n");
        }
        if (!by.isEmpty()) sb.append("\n§0").append(lm.tr(v, "book.total")).append(": §4§l").append(total);
        return ld(sb.toString()).append(back(lm, v));
    }

    private static Component team(KMCCore plugin, LanguageManager lm, Player v) {
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(v.getUniqueId());
        StringBuilder sb = new StringBuilder();
        if (team == null) {
            sb.append("§4§l").append(lm.tr(v, "book.p.team")).append("\n§8━━━━━━━━━━━━\n§8").append(lm.tr(v, "book.no-team"));
            return ld(sb.toString()).append(back(lm, v));
        }
        sb.append("§4§l").append(strip(team.getDisplayName())).append("\n§8━━━━━━━━━━━━\n");
        List<PlayerData> members = new ArrayList<>();
        for (UUID u : team.getMembers()) {
            PlayerData pd = plugin.getPlayerDataManager().get(u);
            if (pd != null) members.add(pd);
        }
        members.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        int total = Math.max(1, team.getPoints());
        for (PlayerData pd : members) {
            boolean you = pd.getUuid().equals(v.getUniqueId());
            sb.append(you ? "§4§l" : "§0").append(pd.getName()).append("§8: §0").append(pd.getPoints())
                    .append(" §8(").append(Math.round(100f * pd.getPoints() / total)).append("%)\n");
        }
        sb.append("\n§0").append(lm.tr(v, "book.total")).append(": §4§l").append(team.getPoints());
        return ld(sb.toString()).append(back(lm, v));
    }

    private static Component standings(KMCCore plugin, LanguageManager lm, Player v) {
        StringBuilder sb = new StringBuilder();
        sb.append("§4§l").append(lm.tr(v, "book.p.standings")).append("\n§8━━━━━━━━━━━━\n");
        var teams = plugin.getTournamentManager().getFinalStandings();
        for (int i = 0; i < Math.min(8, teams.size()); i++) {
            sb.append(i == 0 ? "§6★ " : "§8" + (i + 1) + ". ").append("§0").append(strip(teams.get(i).getDisplayName()))
                    .append(" §8").append(teams.get(i).getPoints()).append("\n");
        }
        sb.append("\n§4").append(lm.tr(v, "book.top-players")).append("\n");
        var board = plugin.getPlayerDataManager().getLeaderboard();
        for (int i = 0; i < Math.min(5, board.size()); i++) {
            boolean you = board.get(i).getUuid().equals(v.getUniqueId());
            sb.append(you ? "§4§l" : "§0").append(i + 1).append(". ").append(board.get(i).getName())
                    .append(" §8").append(board.get(i).getPoints()).append("\n");
        }
        return ld(sb.toString()).append(back(lm, v));
    }

    private static Component awards(KMCCore plugin, LanguageManager lm, Player v) {
        StringBuilder sb = new StringBuilder();
        sb.append("§4§l").append(lm.tr(v, "book.p.awards")).append("\n§8━━━━━━━━━━━━\n");
        var all = EventEndSequence.lastAwards();
        if (all.isEmpty()) sb.append("§8").append(lm.tr(v, "book.none"));
        KMCTeam mine = plugin.getTeamManager().getTeamByPlayer(v.getUniqueId());
        int shown = 0;
        for (var a : all) {
            if (shown++ >= 8) break;
            boolean yours = a.teamAward() ? mine != null && mine.getId().equals(a.teamId())
                    : v.getUniqueId().equals(a.player());
            KMCTeam t = a.teamId() != null ? plugin.getTeamManager().getTeam(a.teamId()) : null;
            String who = a.teamAward() ? (t != null ? strip(t.getDisplayName()) : "?") : String.valueOf(a.name());
            sb.append(yours ? "§6★ " : "§8• ").append("§0")
                    .append(strip(lm.tr(v, "awards." + a.id() + ".name"))).append("\n   ")
                    .append(yours ? "§4§l" : "§0").append(who).append("\n");
        }
        return ld(sb.toString()).append(back(lm, v));
    }

    private static Component records(KMCCore plugin, LanguageManager lm, Player v) {
        StringBuilder sb = new StringBuilder();
        sb.append("§4§l").append(lm.tr(v, "book.p.records")).append("\n§8━━━━━━━━━━━━\n");
        var tr = plugin.getTrackRecords();
        int shown = 0;
        for (String[] t : tr.tracks()) {
            long best = tr.best(t[0], t[1], 0, v.getUniqueId());
            if (best < 0) continue;
            var rec = tr.record(t[0], t[1], 0);
            if (shown++ >= 6) break;
            sb.append("§0").append(t[1]).append("\n   §4").append(TrackRecords.format(best));
            if (rec != null && rec.millis() > 0 && rec.millis() != best)
                sb.append(" §8(+").append(TrackRecords.format(best - rec.millis())).append(")");
            sb.append("\n");
        }
        if (shown == 0) sb.append("§8").append(lm.tr(v, "book.none"));
        return ld(sb.toString()).append(back(lm, v));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Component ld(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private static Component link(String label, int page) {
        return LegacyComponentSerializer.legacySection().deserialize("§1» §0" + label + "\n")
                .clickEvent(ClickEvent.changePage(page))
                .hoverEvent(HoverEvent.showText(Component.text("→ " + page)));
    }

    private static Component back(LanguageManager lm, Player v) {
        return LegacyComponentSerializer.legacySection().deserialize("\n\n§1« " + lm.tr(v, "book.contents"))
                .clickEvent(ClickEvent.changePage(1));
    }

    private static int sum(List<Entry> es) {
        int s = 0;
        for (Entry e : es) s += e.points();
        return s;
    }

    private static String strip(String s) {
        return s == null ? "" : org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', s));
    }
}
