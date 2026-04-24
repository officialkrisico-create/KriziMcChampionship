package nl.kmc.kmccore.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.List;

/**
 * Chest-GUI vote menu.
 *
 * <p>When the vote opens, every player gets a clickable item in chat
 * (handled by ClickableVoteMessage) OR they can run /kmcvote to open
 * this GUI. Inside is one item per unplayed game; clicking casts the vote.
 *
 * <p>The GUI supports any number of games (up to 54 in a double-chest).
 * If there are more than 45 games we'd need pagination, but for now
 * 10+ fits fine in a 54-slot inventory.
 */
public class VoteGuiListener implements Listener {

    public static final String GUI_TITLE_PLAIN = "Stem op de volgende game";
    private static final NamespacedKey GAME_ID_KEY =
            new NamespacedKey("kmccore", "vote_game_id");

    private final KMCCore plugin;

    public VoteGuiListener(KMCCore plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Opening the GUI
    // ----------------------------------------------------------------

    /**
     * Opens the vote GUI for a player, showing every game still
     * available to vote on (unplayed this tournament).
     */
    public void openVoteGui(Player player) {
        List<KMCGame> options = plugin.getGameManager().getVoteOptions();

        // Round up to next multiple of 9, min 18 slots, max 54
        int slots = Math.max(18, Math.min(54, ((options.size() + 8) / 9) * 9));

        Inventory inv = Bukkit.createInventory(null, slots,
                Component.text(GUI_TITLE_PLAIN, NamedTextColor.GOLD, TextDecoration.BOLD));

        for (int i = 0; i < options.size() && i < slots; i++) {
            KMCGame game = options.get(i);
            inv.setItem(i, buildGameItem(game, i + 1));
        }

        player.openInventory(inv);
    }

    /** Builds the clickable item for one game in the vote GUI. */
    private ItemStack buildGameItem(KMCGame game, int optionNumber) {
        Material mat = game.getIcon() != null ? game.getIcon() : Material.PAPER;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(game.getDisplayName(),
                NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(List.of(
                Component.text("Klik om te stemmen!", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("(Optie " + optionNumber + ")", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));

        // Store game ID on the item so we know what was clicked
        meta.getPersistentDataContainer().set(GAME_ID_KEY, PersistentDataType.STRING, game.getId());
        item.setItemMeta(meta);
        return item;
    }

    // ----------------------------------------------------------------
    // Click handling
    // ----------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // Only our vote GUI
        if (event.getView().getTitle() == null) return;
        Component title = event.getView().title();
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(title);
        if (!plain.equals(GUI_TITLE_PLAIN)) return;

        event.setCancelled(true); // prevent item movement

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String gameId = meta.getPersistentDataContainer().get(GAME_ID_KEY, PersistentDataType.STRING);
        if (gameId == null) return;

        // Find option number
        List<KMCGame> options = plugin.getGameManager().getVoteOptions();
        int optionNumber = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).getId().equals(gameId)) {
                optionNumber = i + 1;
                break;
            }
        }

        if (optionNumber > 0) {
            boolean ok = plugin.getGameManager().castVote(player, optionNumber);
            if (ok) player.closeInventory();
        }
    }
}
