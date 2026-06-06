package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-game repetitions GUI — set how many times each game is played in a row
 * (e.g. 3× SkyWars but only 1× Bingo). Writes {@code games.list.<id>.repetitions}
 * which the AutomationManager already honours.
 *
 * <p>Left-click = +1, right-click = -1 (range 1–6).
 */
public final class GameRepetitionsGui extends Gui {

    private static final int MIN = 1, MAX = 6;
    private final KMCCore plugin;

    public GameRepetitionsGui(KMCCore plugin) {
        super("&1&lGame-herhalingen", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();

        set(4, item(Material.REPEATING_COMMAND_BLOCK, "&6&lGame-herhalingen",
                "&7Stel per game in hoe vaak hij achter elkaar",
                "&7gespeeld wordt (bijv. 3× SkyWars, 1× Bingo).",
                "",
                "&aLinkermuisknop&7: +1   &cRechtermuisknop&7: -1"));

        List<KMCGame> games = new ArrayList<>(plugin.getGameManager().getAllGames());
        games.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));

        // Centered 7-wide grid in rows 1-4 (columns 1-7).
        int idx = 0;
        for (KMCGame game : games) {
            int slot = (1 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 45) break;
            renderGameButton(slot, game);
            idx++;
        }

        button(49, item(Material.BARRIER, "&c&lSluiten"), Player::closeInventory);
        fillEmpty();
    }

    private void renderGameButton(int slot, KMCGame game) {
        int reps = repetitionsOf(game.getId());
        set(slot, item(game.getIcon() != null ? game.getIcon() : Material.PAPER,
                "&e&l" + game.getDisplayName(),
                "&7Wordt gespeeld: &a" + reps + "×",
                "",
                "&aLinks&7: +1   &cRechts&7: -1"));
    }

    private int repetitionsOf(String gameId) {
        int reps = plugin.getConfig().getInt("games.list." + gameId + ".repetitions", 1);
        return Math.max(MIN, Math.min(MAX, reps));
    }

    /** Left/right click on a game tile adjusts its repetitions; other slots use actions. */
    @Override
    public void handleClick(Player p, int slot, boolean rightClick) {
        boolean inGrid = slot >= 9 && slot < 45 && slot % 9 != 0 && slot % 9 != 8;
        if (!inGrid) { super.handleClick(p, slot, rightClick); return; }

        List<KMCGame> games = new ArrayList<>(plugin.getGameManager().getAllGames());
        games.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        int idx = (slot / 9 - 1) * 7 + (slot % 9 - 1);
        if (idx < 0 || idx >= games.size()) return;

        KMCGame game = games.get(idx);
        int reps = repetitionsOf(game.getId());
        reps = rightClick ? Math.max(MIN, reps - 1) : Math.min(MAX, reps + 1);
        plugin.getConfig().set("games.list." + game.getId() + ".repetitions", reps);
        plugin.saveConfig();
        render();
        p.updateInventory();
    }
}
