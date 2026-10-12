package nl.kmc.kmccore.lobbygames;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.Gui;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;

/** One tic-tac-toe match in a 3x3 chest GUI. X (the challenger) starts. */
final class TicTacToeMatch extends Gui {

    private static final int[] SLOTS = {3, 4, 5, 12, 13, 14, 21, 22, 23};
    private static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};

    private final LobbyGamesManager mgr;
    private final KMCCore plugin;
    private final UUID x, o;
    private final String xName, oName;
    private final char[] board = new char[9];
    private boolean xTurn = true;
    private boolean over;

    TicTacToeMatch(LobbyGamesManager mgr, KMCCore plugin, Player x, Player o) {
        super("&6&lBoter-kaas-en-eieren", 3);
        this.mgr = mgr;
        this.plugin = plugin;
        this.x = x.getUniqueId();
        this.o = o.getUniqueId();
        this.xName = x.getName();
        this.oName = o.getName();
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            char c = board[i];
            if (c == 'X') set(SLOTS[i], item(Material.RED_CONCRETE, "&c&lX"));
            else if (c == 'O') set(SLOTS[i], item(Material.BLUE_CONCRETE, "&9&lO"));
            else button(SLOTS[i], item(Material.WHITE_STAINED_GLASS_PANE, "&7Klik om te zetten"), p -> play(p, cell));
        }
        set(0, head(Bukkit().getOfflinePlayer(x), "&c&lX &f" + xName, over ? "" : (xTurn ? "&a◀ aan de beurt" : "")));
        set(8, head(Bukkit().getOfflinePlayer(o), "&9&lO &f" + oName, over ? "" : (xTurn ? "" : "&a◀ aan de beurt")));
        fillEmpty();
    }

    private static org.bukkit.Server Bukkit() { return org.bukkit.Bukkit.getServer(); }

    @Override
    public void handleClick(Player p, int slot) {
        super.handleClick(p, slot);
    }

    private void play(Player p, int cell) {
        if (over || board[cell] != 0) return;
        boolean isX = p.getUniqueId().equals(x);
        boolean isO = p.getUniqueId().equals(o);
        if (!isX && !isO) return;
        var lm = plugin.getLanguageManager();
        if (isX != xTurn) { p.sendMessage(lm.tr(p, "lobbygames.ttt.wait")); return; }

        board[cell] = isX ? 'X' : 'O';
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, isX ? 1.2f : 0.9f);

        int[] win = winningLine();
        if (win != null) { finish(isX ? x : o, isX ? o : x, false); return; }
        boolean full = true;
        for (char c : board) if (c == 0) { full = false; break; }
        if (full) { finish(null, null, true); return; }

        xTurn = !xTurn;
        render();
        Player next = org.bukkit.Bukkit.getPlayer(xTurn ? x : o);
        if (next != null) {
            next.sendMessage(lm.tr(next, "lobbygames.ttt.your-turn"));
            next.playSound(next.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.5f);
        }
    }

    private int[] winningLine() {
        for (int[] l : LINES) {
            char a = board[l[0]];
            if (a != 0 && a == board[l[1]] && a == board[l[2]]) return l;
        }
        return null;
    }

    private void finish(UUID winner, UUID loser, boolean draw) {
        over = true;
        mgr.recordName(x, xName);
        mgr.recordName(o, oName);
        mgr.tttResult(winner, loser, draw, x, o);
        render();
        var lm = plugin.getLanguageManager();
        for (UUID id : new UUID[] {x, o}) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p == null) continue;
            if (draw) {
                p.sendMessage(lm.tr(p, "lobbygames.ttt.draw"));
            } else if (id.equals(winner)) {
                p.sendMessage(lm.tr(p, "lobbygames.ttt.win", mgr.tttWins(id)));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                p.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.5, 0), 20, 0.5, 0.5, 0.5);
            } else {
                p.sendMessage(lm.tr(p, "lobbygames.ttt.lose"));
            }
        }
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID id : new UUID[] {x, o}) {
                Player p = org.bukkit.Bukkit.getPlayer(id);
                if (p != null && p.getOpenInventory().getTopInventory().getHolder() == this) p.closeInventory();
            }
            mgr.matchEnded(this);
        }, 40L);
    }

    /** A player left or gave up: the other one wins. */
    void forfeit(Player quitter) {
        if (over) return;
        boolean quitterIsX = quitter.getUniqueId().equals(x);
        UUID winner = quitterIsX ? o : x;
        over = true;
        mgr.recordName(x, xName);
        mgr.recordName(o, oName);
        mgr.tttResult(winner, quitter.getUniqueId(), false, x, o);
        Player w = org.bukkit.Bukkit.getPlayer(winner);
        if (w != null) w.sendMessage(plugin.getLanguageManager().tr(w, "lobbygames.ttt.forfeit", quitter.getName()));
        quitter.sendMessage(plugin.getLanguageManager().tr(quitter, "lobbygames.ttt.you-forfeit"));
        closeBoth();
        mgr.matchEnded(this);
    }

    /** Server shutdown / game start: close the board, no result. */
    void abort() {
        over = true;
        closeBoth();
    }

    private void closeBoth() {
        for (UUID id : new UUID[] {x, o}) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() == this) p.closeInventory();
        }
    }
}
