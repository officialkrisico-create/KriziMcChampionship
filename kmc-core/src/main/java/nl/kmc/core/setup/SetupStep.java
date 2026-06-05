package nl.kmc.core.setup;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * One line of a game's setup checklist, shown in the Setup Dashboard GUI.
 *
 * <p>A step has a label, a current status string, a done/not-done flag, an icon,
 * and an optional {@link #action} that runs the actual setup (e.g. "add a spawn
 * at your location"). When the action is null the step is display-only (used to
 * surface validation problems the admin must fix elsewhere).
 */
public final class SetupStep {

    private final String label;
    private final String status;
    private final boolean done;
    private final Material icon;
    private final Consumer<Player> action;   // nullable
    private final String actionHint;         // nullable — shown as "Click to ..."

    public SetupStep(String label, String status, boolean done, Material icon,
                     Consumer<Player> action, String actionHint) {
        this.label      = label;
        this.status     = status;
        this.done       = done;
        this.icon       = icon != null ? icon : Material.PAPER;
        this.action     = action;
        this.actionHint = actionHint;
    }

    /** Display-only step (no clickable action). */
    public static SetupStep info(String label, String status, boolean done, Material icon) {
        return new SetupStep(label, status, done, icon, null, null);
    }

    /** Actionable step. */
    public static SetupStep action(String label, String status, boolean done, Material icon,
                                   Consumer<Player> action, String hint) {
        return new SetupStep(label, status, done, icon, action, hint);
    }

    public String  getLabel()      { return label; }
    public String  getStatus()     { return status; }
    public boolean isDone()        { return done; }
    public Material getIcon()      { return icon; }
    public boolean hasAction()     { return action != null; }
    public Consumer<Player> getAction() { return action; }
    public String  getActionHint() { return actionHint; }
}
