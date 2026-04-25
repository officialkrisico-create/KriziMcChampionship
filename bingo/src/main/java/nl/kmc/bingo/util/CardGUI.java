package nl.kmc.bingo.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.models.BingoCard;
import nl.kmc.bingo.models.TeamCardState;
import nl.kmc.bingo.objectives.BingoObjective;
import nl.kmc.bingo.objectives.CollectObjective;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the bingo card view for a player.
 *
 * <p>Layout: 6-row chest (54 slots).
 * <pre>
 *   Row 0: filler glass with team info
 *   Rows 1-5: the 5×5 card (slots 10-14, 19-23, 28-32, 37-41, 46-50)
 *   Right column slots: line completion indicators
 * </pre>
 *
 * <p>Completed squares show as the same item enchanted. Incomplete
 * squares show with progress lore "X / Y".
 */
public final class CardGUI {

    private CardGUI() {}

    private static final int[] CARD_SLOTS = {
        10, 11, 12, 13, 14,
        19, 20, 21, 22, 23,
        28, 29, 30, 31, 32,
        37, 38, 39, 40, 41,
        46, 47, 48, 49, 50
    };

    public static void open(BingoPlugin plugin, Player viewer) {
        var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(viewer.getUniqueId());
        if (team == null) {
            viewer.sendMessage(ChatColor.RED + "Je zit niet in een team!");
            return;
        }
        TeamCardState state = plugin.getGameManager().getTeamState(team.getId());
        if (state == null) {
            viewer.sendMessage(ChatColor.RED + "Geen Bingo game actief!");
            return;
        }

        Component title = Component.text("Bingo — ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(team.getDisplayName(), getTeamColor(team)));

        Inventory inv = org.bukkit.Bukkit.createInventory(new BingoHolder(), 54, title);

        // Filler glass
        ItemStack filler = makeFiller();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Header row — team info
        ItemStack header = new ItemStack(Material.PAPER);
        var hMeta = header.getItemMeta();
        hMeta.displayName(Component.text(team.getDisplayName() + " Card",
                getTeamColor(team), TextDecoration.BOLD));
        hMeta.lore(List.of(
                Component.text("✔ Vakjes: " + state.getCompletedSquareCount() + "/25", NamedTextColor.GRAY),
                Component.text("✔ Lijnen: " + state.getCompletedLineCount() + "/12", NamedTextColor.GRAY)
        ));
        header.setItemMeta(hMeta);
        inv.setItem(4, header);

        // Card squares
        BingoCard card = state.getCard();
        for (int idx = 0; idx < BingoCard.TOTAL; idx++) {
            BingoObjective obj = card.get(idx);
            ItemStack item = buildSquareItem(obj, state, idx);
            inv.setItem(CARD_SLOTS[idx], item);
        }

        viewer.openInventory(inv);
    }

    private static ItemStack buildSquareItem(BingoObjective obj, TeamCardState state, int idx) {
        ItemStack stack = new ItemStack(obj.getDisplayIcon());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        boolean done = state.isCompleted(idx);
        int progress = state.getProgress(idx);
        int target   = obj.getTargetAmount();

        Component name = obj.getDisplayName();
        if (done) {
            name = name.color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                    org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        }
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (done) {
            lore.add(Component.text("✔ Compleet!", NamedTextColor.GREEN));
        } else if (target > 1) {
            lore.add(Component.text("Voortgang: " + progress + " / " + target,
                    NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Verzamel dit item",
                    NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack makeFiller() {
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        var meta = glass.getItemMeta();
        meta.displayName(Component.text(" "));
        glass.setItemMeta(meta);
        return glass;
    }

    private static NamedTextColor getTeamColor(KMCTeam team) {
        // ChatColor → NamedTextColor mapping
        try {
            return NamedTextColor.NAMES.value(team.getColor().name().toLowerCase());
        } catch (Exception e) {
            return NamedTextColor.WHITE;
        }
    }

    /** Marker for InventoryClickListener to identify our GUI. */
    public static class BingoHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
