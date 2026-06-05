package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Settings GUI — flip the most-used tournament settings without editing YAML.
 * Every click saves {@code config.yml} immediately and refreshes the screen.
 * (Bool = toggle, numbers = click to cycle through sensible values.)
 */
public final class SettingsGui extends Gui {

    private final KMCCore plugin;

    public SettingsGui(KMCCore plugin) {
        super("&1&lKMC Instellingen", 4);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();
        var cfg = plugin.getConfig();

        set(4, item(Material.COMPARATOR, "&6&lInstellingen",
                "&7Klik om aan te passen — wijzigingen worden direct opgeslagen."));

        boolean voting = cfg.getBoolean("games.voting-enabled", true);
        button(10, item(voting ? Material.LIME_DYE : Material.GRAY_DYE,
                "&f&lStemmen",
                voting ? "&aAAN" : "&cUIT",
                "&7Spelers stemmen op de volgende game.",
                "&eKlik om te wisselen"),
                p -> toggle("games.voting-enabled", voting, p));

        String ach = cfg.getString("settings.achievement-system", "v1");
        button(12, item(Material.EXPERIENCE_BOTTLE, "&f&lAchievement-systeem",
                "&7Actief: &e" + ach.toUpperCase(),
                "&7v1 = legacy, v2 = nieuw (kmc-stats)",
                "&eKlik om te wisselen"),
                p -> { cfg.set("settings.achievement-system", ach.equalsIgnoreCase("v1") ? "v2" : "v1");
                       save(p); });

        int gpr = cfg.getInt("automation.games-per-round", 3);
        button(14, item(Material.REPEATER, "&f&lGames per ronde",
                "&7Nu: &e" + gpr,
                "&eKlik: cycle 1 → 6"),
                p -> { cfg.set("automation.games-per-round", gpr >= 6 ? 1 : gpr + 1); save(p); });

        int rounds = cfg.getInt("tournament.total-rounds", 8);
        button(16, item(Material.CLOCK, "&f&lAantal rondes",
                "&7Nu: &e" + rounds,
                "&eKlik: cycle 1 → 12"),
                p -> { cfg.set("tournament.total-rounds", rounds >= 12 ? 1 : rounds + 1); save(p); });

        int inter = cfg.getInt("automation.intermission-seconds", 30);
        button(22, item(Material.HOPPER, "&f&lTussenpauze (sec)",
                "&7Nu: &e" + inter,
                "&eKlik: +5 (10 → 60)"),
                p -> { cfg.set("automation.intermission-seconds", inter >= 60 ? 10 : inter + 5); save(p); });

        fillEmpty();
    }

    private void toggle(String path, boolean current, Player p) {
        plugin.getConfig().set(path, !current);
        save(p);
    }

    private void save(Player p) {
        plugin.saveConfig();
        render();
        p.updateInventory();
    }
}
