package nl.kmc.corecharge;

import nl.kmc.kmccore.gui.Gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/** The ability menu of a team (also opened by /corecharge use). */
final class CoreGui extends Gui {

    CoreGui(CoreChargeManager mgr, Player p) {
        super("&b&lKern-vaardigheden", 3);
        UUID id = p.getUniqueId();
        var api = nl.kmc.core.api.KMCApiProvider.get();

        set(4, item(Material.NETHER_STAR, "&b&l" + (int) mgr.energyOf(p) + " &7/ &b" + (int) mgr.capacity() + " &7energie",
                mgr.isFinalPhase() ? "&c&lFINAL CHARGE" : "&8Final Overload komt in de laatste fase"));
        int slot = 10;
        for (CoreAbility a : CoreAbility.values()) {
            String state = mgr.abilityState(p, a);
            boolean ok = CoreChargeManager.OK.equals(state);
            String status = ok ? "§a✔ " + api.tr(id, "core.menu.ready")
                    : "§c✘ " + api.tr(id, state)
                    + (state.endsWith("cooldown") ? " (" + mgr.cooldownSeconds(p, a) + "s)" : "");
            button(slot++, item(ok ? a.icon : Material.GRAY_DYE,
                    (ok ? "&a&l" : "&7&l") + api.tr(id, "core.ability." + a.key + ".name"),
                    "&7" + api.tr(id, "core.menu.cost", (int) mgr.cost(a)),
                    "&7" + api.tr(id, "core.ability." + a.key + ".desc"),
                    "", status), pl -> {
                String result = mgr.activate(pl, a);
                if (CoreChargeManager.OK.equals(result)) {
                    pl.closeInventory();
                } else {
                    pl.sendMessage(api.tr(pl.getUniqueId(), result));
                    new CoreGui(mgr, pl).open(pl);
                }
            });
        }
        fillEmpty();
    }
}
