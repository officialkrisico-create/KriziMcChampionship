package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.CeremonyFireworks;
import nl.kmc.kmccore.presentation.CeremonyFireworks.Moment;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * {@code /kmcopeningsceremony}: pick a moment of the opening ceremony and set up its fireworks —
 * on/off, spots (where you stand), shape, colours and number of salvos, with a Test button.
 */
public final class OpeningCeremonyGui extends Gui {

    private final KMCCore plugin;

    public OpeningCeremonyGui(KMCCore plugin) {
        super("&5&lOpeningsceremonie — vuurwerk", 4);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();
        var fw = plugin.getCeremonyFireworks();

        set(4, item(Material.FIREWORK_STAR, "&6&lVuurwerk per moment",
                "&7Kies een moment om het vuurwerk in te stellen.",
                "&7Zonder spots schiet het boven de spelers af."));

        Moment[] moments = Moment.values();
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < moments.length && i < slots.length; i++) {
            Moment m = moments[i];
            var s = fw.get(m);
            button(slots[i], item(s.enabled ? Material.FIREWORK_ROCKET : Material.GRAY_DYE,
                    (s.enabled ? "&a&l" : "&7&l") + m.label,
                    "&7" + m.description,
                    "&8Status: " + (s.enabled ? "&aaan" : "&cuit"),
                    "&8Spots: &f" + (s.spots.isEmpty() ? "boven spelers" : s.spots.size() + " ingesteld"),
                    "&8Vorm: &f" + s.type.name() + " &8• Kleuren: &f" + s.colors + " &8• Salvo's: &f" + s.salvos,
                    "", "&eKlik om in te stellen"),
                    p -> new MomentGui(m).open(p));
        }

        // Team-showcase podium: stand where a player should stand, face the way they should look.
        int pod = fw.getPodium().size();
        set(27, item(Material.ARMOR_STAND, "&b&lPodium (team-showcase)",
                "&7Spots: &f" + pod + (pod == 0 ? " &8(automatisch in de lobby)" : ""),
                "&7Elke teamspeler staat op een spot, kijkrichting = jouw kijkrichting."));
        button(28, item(Material.LIME_DYE, "&a&lPodium-spot toevoegen hier",
                "&7Staat op jouw positie en kijkt jouw kant op."),
                p -> { fw.addPodiumSpot(p.getLocation());
                       p.sendMessage("§a[Ceremonie] Podium-spot #" + fw.getPodium().size() + " gezet."); render(); p.updateInventory(); });
        button(29, item(Material.TNT, "&c&lPodium wissen"), p -> { fw.clearPodium(); render(); p.updateInventory(); });

        button(31, item(Material.BARRIER, "&c&lSluiten"), Player::closeInventory);
        fillEmpty();
    }

    /** Detail screen for one moment. */
    private final class MomentGui extends Gui {
        private final Moment moment;

        MomentGui(Moment moment) {
            super("&5&l" + moment.label + " — vuurwerk", 4);
            this.moment = moment;
            render();
        }

        private void render() {
            inventory.clear();
            clearActions();
            var fw = plugin.getCeremonyFireworks();
            var s = fw.get(moment);

            set(4, item(Material.FIREWORK_ROCKET, "&6&l" + moment.label, "&7" + moment.description));

            button(10, item(s.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    s.enabled ? "&a&lAan" : "&c&lUit", "&7Klik om te wisselen."),
                    p -> { fw.toggle(moment); refresh(p); });

            button(11, item(Material.COMPASS, "&b&lSpot toevoegen hier",
                    "&7Voegt jouw huidige positie toe als vuurwerk-punt.",
                    "&8Nu: &f" + s.spots.size() + " spot(s)"),
                    p -> { fw.addSpot(moment, p.getLocation());
                           p.sendMessage("§a[Ceremonie] Spot #" + fw.get(moment).spots.size() + " toegevoegd voor §e" + moment.label + "§a.");
                           refresh(p); });

            button(12, item(Material.TNT, "&c&lSpots wissen", "&7Terug naar vuurwerk boven de spelers."),
                    p -> { fw.clearSpots(moment); refresh(p); });

            button(13, item(Material.FIREWORK_STAR, "&e&lVorm: &f" + s.type.name(), "&7Klik om te wisselen."),
                    p -> { fw.cycleType(moment); refresh(p); });

            button(14, item(Material.PAINTING, "&d&lKleuren: &f" + s.colors,
                    "&7TEAM = teamkleuren, GOLD, RAINBOW."), p -> { fw.cycleColors(moment); refresh(p); });

            button(15, item(Material.CLOCK, "&6&lSalvo's: &f" + s.salvos,
                    "&7Aantal keer dat het vuurwerk afgaat."), p -> { fw.cycleSalvos(moment); refresh(p); });

            button(16, item(Material.GLOWSTONE_DUST, "&f&lSpoor: " + (s.trail ? "&aaan" : "&cuit"),
                    "&7Vonkenspoor achter elke raket."), p -> { fw.toggleTrail(moment); refresh(p); });

            button(22, item(Material.REDSTONE_TORCH, "&a&lTest nu", "&7Schiet dit vuurwerk meteen af."),
                    p -> { p.closeInventory(); fw.play(moment, true); });

            button(30, item(Material.ARROW, "&7« Terug"), p -> { OpeningCeremonyGui.this.render(); OpeningCeremonyGui.this.open(p); });
            fillEmpty();
        }

        private void refresh(Player p) { render(); p.updateInventory(); }
    }

    /** Lists the settings in chat (console-friendly). */
    public static void describe(KMCCore plugin, org.bukkit.command.CommandSender to) {
        CeremonyFireworks fw = plugin.getCeremonyFireworks();
        to.sendMessage("§6§lOpeningsceremonie — vuurwerk");
        for (Moment m : Moment.values()) {
            var s = fw.get(m);
            to.sendMessage("  " + (s.enabled ? "§a●" : "§c○") + " §e" + m.id + " §7(" + m.label + ") §8— spots: §f"
                    + (s.spots.isEmpty() ? "boven spelers" : s.spots.size()) + " §8vorm: §f" + s.type.name()
                    + " §8kleur: §f" + s.colors + " §8salvo's: §f" + s.salvos);
        }
    }
}
