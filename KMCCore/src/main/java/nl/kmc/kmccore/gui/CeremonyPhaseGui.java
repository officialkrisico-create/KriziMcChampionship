package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.CeremonyManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Edits a single ceremony phase: title, subtitle, message lines and duration.
 * Text edits use the shared {@link ChatInput} ("type the new value in chat").
 */
public final class CeremonyPhaseGui extends Gui {

    private static final Map<String, String> NO_PH = Map.of();

    private final KMCCore plugin;
    private final String  phase;

    public CeremonyPhaseGui(KMCCore plugin, String phase) {
        super("&1Ceremonie: &9" + phase, 5);
        this.plugin = plugin;
        this.phase  = phase;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();
        CeremonyManager cm = plugin.getCeremonyManager();

        String title    = cm.getTitle(phase, NO_PH);
        String subtitle = cm.getSubtitle(phase, NO_PH);
        List<String> msgs = cm.getMessages(phase, NO_PH);
        int dur = cm.getDuration(phase, 8);

        set(4, item(Material.PAPER, "&e&l" + phase, "&7Fase-instellingen"));

        // Duration — click to cycle +5s (0 → 120).
        button(10, item(Material.CLOCK, "&f&lDuur",
                "&7Nu: &e" + dur + "s",
                "&eKlik: +5s (0 → 120)"),
                p -> { cm.setDuration(phase, dur >= 120 ? 0 : dur + 5); render(); p.updateInventory(); });

        // Title.
        button(12, item(Material.NAME_TAG, "&f&lTitel",
                "&7Nu: " + (title.isBlank() ? "&8(leeg)" : "&r" + title),
                "&eKlik om te wijzigen (typ in chat)"),
                p -> plugin.getChatInput().await(p,
                        "Typ de nieuwe titel voor '" + phase + "' (& voor kleuren, of 'leeg'):",
                        in -> { cm.setTitle(phase, in.equalsIgnoreCase("leeg") ? "" : in);
                                new CeremonyPhaseGui(plugin, phase).open(p); }));

        // Subtitle.
        button(14, item(Material.NAME_TAG, "&f&lSubtitel",
                "&7Nu: " + (subtitle.isBlank() ? "&8(leeg)" : "&r" + subtitle),
                "&eKlik om te wijzigen (typ in chat)"),
                p -> plugin.getChatInput().await(p,
                        "Typ de nieuwe subtitel voor '" + phase + "' (of 'leeg'):",
                        in -> { cm.setSubtitle(phase, in.equalsIgnoreCase("leeg") ? "" : in);
                                new CeremonyPhaseGui(plugin, phase).open(p); }));

        // Messages: add / clear.
        button(16, item(Material.WRITABLE_BOOK, "&f&lBerichten (&e" + msgs.size() + "&f)",
                "&7De chatregels die spelers zien.",
                "&aLinkerklik-actie: voeg een regel toe"),
                p -> plugin.getChatInput().await(p,
                        "Typ een nieuwe berichtregel voor '" + phase + "':",
                        in -> { cm.addMessage(phase, in);
                                new CeremonyPhaseGui(plugin, phase).open(p); }));

        button(25, item(Material.LAVA_BUCKET, "&c&lWis alle berichten",
                "&7Verwijdert alle chatregels van deze fase."),
                p -> { cm.clearMessages(phase); render(); p.updateInventory(); });

        // Preview the messages (read-only) in lore of an info item.
        if (!msgs.isEmpty()) {
            String[] lore = msgs.stream().limit(8).map(m -> "&r" + m).toArray(String[]::new);
            set(22, item(Material.BOOK, "&7Huidige berichten", lore));
        }

        button(40, item(Material.ARROW, "&7« Terug"),
                p -> new CeremonyEditorGui(plugin).open(p));

        fillEmpty();
    }
}
