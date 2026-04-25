package nl.kmc.bingo.objectives;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * "Kill N mobs of type X" — fired on EntityDeathEvent where killer
 * is a player on the team.
 */
public class KillObjective implements BingoObjective {

    private final EntityType mobType;
    private final int        amount;

    public KillObjective(EntityType mobType, int amount) {
        this.mobType = mobType;
        this.amount  = Math.max(1, amount);
    }

    @Override public String getId()         { return "kill:" + mobType.name() + ":" + amount; }
    @Override public int    getTargetAmount() { return amount; }
    @Override public ObjectiveType getType()  { return ObjectiveType.KILL_MOB; }
    @Override public String getMatchKey()     { return mobType.name(); }

    @Override
    public Material getDisplayIcon() {
        // Best icon guess per common mob
        return switch (mobType) {
            case CREEPER       -> Material.CREEPER_HEAD;
            case ZOMBIE        -> Material.ZOMBIE_HEAD;
            case SKELETON      -> Material.SKELETON_SKULL;
            case ENDER_DRAGON  -> Material.DRAGON_HEAD;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case SPIDER        -> Material.STRING;
            case CAVE_SPIDER   -> Material.FERMENTED_SPIDER_EYE;
            case BLAZE         -> Material.BLAZE_ROD;
            case GHAST         -> Material.GHAST_TEAR;
            case ENDERMAN      -> Material.ENDER_PEARL;
            case WITCH         -> Material.GLASS_BOTTLE;
            case PIGLIN        -> Material.GOLD_INGOT;
            case PILLAGER      -> Material.CROSSBOW;
            case GUARDIAN      -> Material.PRISMARINE_SHARD;
            case SLIME         -> Material.SLIME_BALL;
            case MAGMA_CUBE    -> Material.MAGMA_CREAM;
            case PHANTOM       -> Material.PHANTOM_MEMBRANE;
            default            -> Material.IRON_SWORD;
        };
    }

    @Override
    public Component getDisplayName() {
        String label = amount > 1 ? amount + "× kill " + formatEntity(mobType.name())
                                   : "Kill " + formatEntity(mobType.name());
        return Component.text(label, NamedTextColor.RED);
    }

    public EntityType getMobType() { return mobType; }

    private static String formatEntity(String name) {
        String[] words = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(words[i].charAt(0)));
            sb.append(words[i].substring(1));
        }
        return sb.toString();
    }
}
