package nl.kmc.bingo.objectives;

/**
 * Categories of bingo objective. Listeners filter on these.
 *
 * <p>Each type has its own listener that hooks the relevant Bukkit
 * event and feeds progress to matching objectives on every team's card.
 */
public enum ObjectiveType {
    /** "Collect N of item X" — fired on PlayerPickupItem + InventoryClick. */
    COLLECT_ITEM,

    /** "Craft item X" — fired on CraftItemEvent. */
    CRAFT_ITEM,

    /** "Kill N mobs of type X" — fired on EntityDeathEvent. */
    KILL_MOB,

    /** "Visit biome X" — fired on PlayerMoveEvent (debounced). */
    VISIT_BIOME,

    /** "Fish item X" — fired on PlayerFishEvent. */
    FISH_ITEM,

    /** "Reach Y blocks deep" — fired on PlayerMoveEvent (debounced). */
    REACH_DEPTH
}
