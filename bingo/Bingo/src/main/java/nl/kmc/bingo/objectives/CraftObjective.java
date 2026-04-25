package nl.kmc.bingo.objectives;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * "Craft item X" — fired on CraftItemEvent (player crafts).
 * Single-instance — once anyone on the team crafts it, done.
 */
public class CraftObjective implements BingoObjective {

    private final Material target;

    public CraftObjective(Material target) {
        this.target = target;
    }

    @Override public String getId()           { return "craft:" + target.name(); }
    @Override public Material getDisplayIcon() { return target; }
    @Override public int getTargetAmount()    { return 1; }
    @Override public ObjectiveType getType()  { return ObjectiveType.CRAFT_ITEM; }
    @Override public String getMatchKey()     { return target.name(); }

    @Override
    public Component getDisplayName() {
        return Component.text("Craft: " + CollectObjective.readable(target), NamedTextColor.YELLOW);
    }

    public Material getTarget() { return target; }
}
