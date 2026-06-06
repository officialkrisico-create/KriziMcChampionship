package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Detailed per-game explanation: objective, description, how points are scored,
 * and player count — opened from {@link HelpGui}.
 */
public final class HelpDetailGui extends Gui {

    private final KMCCore plugin;
    private final GameRegistration reg;

    public HelpDetailGui(KMCCore plugin, GameRegistration reg) {
        super("&1&l" + reg.getDisplayName(), 6);
        this.plugin = plugin;
        this.reg = reg;
        render();
    }

    private void render() {
        // Header — icon + name + player count.
        set(4, item(reg.getIcon() != null ? reg.getIcon() : Material.PAPER,
                "&e&l" + reg.getDisplayName(),
                "&7Minimaal &f" + reg.getMinPlayers() + " &7spelers"));

        // Description (wrapped over several lore lines).
        List<String> descLore = new ArrayList<>();
        descLore.add("");
        descLore.addAll(wrap(safe(reg.getDescription()), 34, "&7"));
        set(20, item(Material.BOOK, "&b&lWat is het?", descLore.toArray(new String[0])));

        // Objective.
        set(22, item(Material.NETHER_STAR, "&a&lDoel",
                "", "&f" + safe(reg.getObjective())));

        // Scoring (generic — exact point values staan in de game-intro vóór elke game).
        set(24, item(Material.GOLD_INGOT, "&6&lPunten verdienen",
                "",
                "&7• Haal het game-doel om punten te scoren",
                "&7• Hoe beter je plaatst, hoe meer punten",
                "&7• Punten tellen voor jou én je team",
                "&7• Latere rondes geven een hogere multiplier"));

        // Generic how-to-play tips.
        set(31, item(Material.OAK_SIGN, "&f&lTips",
                "", "&7• Speel samen met je team",
                "&7• Stem op de volgende game met &e/kmcvote",
                "&7• Bekijk de stand met &e/kmcstandings"));

        button(49, item(Material.ARROW, "&e&lTerug"),
                p -> new HelpGui(plugin).open(p));
        fillEmpty();
    }

    private static String safe(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    /** Word-wraps {@code text} to lines of ~{@code width} chars, each prefixed with {@code colour}. */
    private static List<String> wrap(String text, int width, String colour) {
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
