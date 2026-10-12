package nl.kmc.crown;

import nl.kmc.kmccore.gui.Gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/** The ability menu of the crown holder (also opened by /crown use). */
final class AbilityGui extends Gui {

    AbilityGui(CrownControlManager mgr, Player p) {
        super("&6&lKroon-vaardigheden", 3);
        UUID id = p.getUniqueId();
        var api = nl.kmc.core.api.KMCApiProvider.get();

        set(4, item(Material.NETHER_STAR, "&e&l" + (int) mgr.energyOf(p) + " &7/ &e" + (int) mgr.capacity() + " &7energie",
                "&8Alleen de kroonhouder kan activeren"));
        int slot = 10;
        for (Ability a : Ability.values()) {
            String state = mgr.abilityState(p, a);
            boolean ok = CrownControlManager.OK.equals(state);
            String name = api.tr(id, "crown.ability." + a.key + ".name");
            String status = ok ? "§a✔ " + api.tr(id, "crown.menu.ready")
                    : "§c✘ " + api.tr(id, state)
                    + (state.endsWith("cooldown") ? " (" + mgr.cooldownSeconds(p, a) + "s)" : "");
            Material icon = ok ? a.icon : Material.GRAY_DYE;
            button(slot++, item(icon, (ok ? "&a&l" : "&7&l") + name,
                    "&7" + api.tr(id, "crown.menu.cost", (int) mgr.cost(a)),
                    "&7" + api.tr(id, "crown.ability." + a.key + ".desc"),
                    "", status), pl -> {
                String result = mgr.activate(pl, a);
                if (CrownControlManager.OK.equals(result)) {
                    pl.closeInventory();
                } else {
                    pl.sendMessage(api.tr(pl.getUniqueId(), result));
                    new AbilityGui(mgr, pl).open(pl);
                }
            });
        }
        fillEmpty();
    }
}
