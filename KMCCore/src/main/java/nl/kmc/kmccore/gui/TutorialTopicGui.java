package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generic /tutorial detail page: a centred row of info cards explaining a
 * topic, plus an optional row of buttons that jump straight into the real
 * GUI being explained. Always returns to {@link TutorialGui} via "Terug".
 */
public final class TutorialTopicGui extends Gui {

    private final KMCCore plugin;

    /** A button that opens the real system being explained (icon + label + lore handled by the caller). */
    public record Link(ItemStack icon, Consumer<Player> onClick) {}

    public TutorialTopicGui(KMCCore plugin, String title, List<ItemStack> cards, List<Link> links) {
        super(title, 6);
        this.plugin = plugin;
        render(cards, links);
    }

    private void render(List<ItemStack> cards, List<Link> links) {
        int[] cardSlots = {19, 20, 21, 22, 23, 24, 25};
        int start = Math.max(0, (cardSlots.length - cards.size()) / 2);
        for (int i = 0; i < cards.size() && start + i < cardSlots.length; i++) {
            set(cardSlots[start + i], cards.get(i));
        }

        int[] linkSlots = {29, 30, 31, 32, 33, 34};
        int lstart = Math.max(0, (linkSlots.length - links.size()) / 2);
        for (int i = 0; i < links.size() && lstart + i < linkSlots.length; i++) {
            Link link = links.get(i);
            button(linkSlots[lstart + i], link.icon(), link.onClick());
        }

        button(49, item(Material.ARROW, "&e&lTerug naar /tutorial"),
                p -> new TutorialGui(plugin).open(p));
        fillEmpty();
    }

    /** Word-wraps {@code text} to lines of ~{@code width} chars, each prefixed with {@code colour}. */
    public static List<String> wrap(String text, int width, String colour) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                out.add(colour + line);
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(colour + line);
        return out;
    }
}
