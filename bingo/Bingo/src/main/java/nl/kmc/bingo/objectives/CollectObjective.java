package nl.kmc.bingo.objectives;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * "Collect N of material X" — completed when a team member's inventory
 * holds at least N of the target material.
 */
public class CollectObjective implements BingoObjective {

    private final Material target;
    private final int      amount;

    public CollectObjective(Material target, int amount) {
        this.target = target;
        this.amount = Math.max(1, amount);
    }

    @Override public String getId()         { return "collect:" + target.name() + ":" + amount; }
    @Override public Material getDisplayIcon() { return target; }
    @Override public int getTargetAmount()    { return amount; }
    @Override public ObjectiveType getType()  { return ObjectiveType.COLLECT_ITEM; }
    @Override public String getMatchKey()     { return target.name(); }

    @Override
    public Component getDisplayName() {
        String name = readable(target);
        String label = amount > 1 ? amount + "× " + name : name;
        return Component.text(label, NamedTextColor.WHITE);
    }

    public Material getTarget() { return target; }

    static String readable(Material m) {
        String[] words = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(words[i].charAt(0)));
            sb.append(words[i].substring(1));
        }
        return sb.toString();
    }
}
