package nl.kmc.speedbuild.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Quick at-a-glance info via the action bar. */
public final class ActionBarManager {

    private ActionBarManager() {}

    public static void send(Player p, String text) {
        p.sendActionBar(Component.text(text));
    }
}
