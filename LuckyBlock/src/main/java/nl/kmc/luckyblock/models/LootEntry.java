package nl.kmc.luckyblock.models;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;

/**
 * Represents one entry in the Lucky Block loot table.
 *
 * <p>The {@code type} field determines which fields are relevant:
 * <ul>
 *   <li>ITEM          – drops an ItemStack with optional enchants</li>
 *   <li>EFFECT        – applies a potion effect to the breaker</li>
 *   <li>EXPLOSION     – creates an explosion at the block location</li>
 *   <li>LIGHTNING     – strikes lightning at the block location</li>
 *   <li>MOB           – spawns one or more mobs</li>
 *   <li>COINS         – awards coins via KMCCore API</li>
 *   <li>POINTS        – awards points via KMCCore API</li>
 *   <li>INSTANT_KILL  – kills the player immediately</li>
 *   <li>FULL_ARMOR    – equips a full armour set</li>
 *   <li>TNT_RAIN      – drops TNT from above</li>
 *   <li>TELEPORT_RANDOM – teleports to a random arena spawn</li>
 *   <li>SWAP_POSITION – swaps position with a random other player</li>
 * </ul>
 */
public class LootEntry {

    public enum Type {
        ITEM, EFFECT, EXPLOSION, LIGHTNING, MOB,
        COINS, POINTS, INSTANT_KILL, FULL_ARMOR,
        TNT_RAIN, TELEPORT_RANDOM, SWAP_POSITION
    }

    // ---- Common ------------------------------------------------
    private final String id;
    private final Type   type;
    private final int    weight;   // relative chance; higher = more common
    private final String message;  // broadcast to the player on trigger

    // ---- ITEM --------------------------------------------------
    private Material                 item;
    private int                      amount = 1;
    private Map<Enchantment, Integer> enchants;

    // ---- EFFECT ------------------------------------------------
    private PotionEffectType potionEffect;
    private int              durationSeconds;
    private int              amplifier;

    // ---- EXPLOSION ---------------------------------------------
    private float explosionPower;

    // ---- MOB ---------------------------------------------------
    private EntityType mobType;
    private int        mobCount = 1;

    // ---- COINS / POINTS ----------------------------------------
    private int rewardAmount;

    // ---- FULL_ARMOR --------------------------------------------
    private Material armorMaterial; // e.g. DIAMOND for full diamond set

    // ---- TNT_RAIN ----------------------------------------------
    private int tntCount;

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public LootEntry(String id, Type type, int weight, String message) {
        this.id      = id;
        this.type    = type;
        this.weight  = weight;
        this.message = message;
    }

    // ----------------------------------------------------------------
    // Getters / setters
    // ----------------------------------------------------------------

    public String  getId()       { return id; }
    public Type    getType()     { return type; }
    public int     getWeight()   { return weight; }
    public String  getMessage()  { return message; }

    // ITEM
    public Material                  getItem()        { return item; }
    public void                      setItem(Material m) { this.item = m; }
    public int                       getAmount()      { return amount; }
    public void                      setAmount(int a) { this.amount = a; }
    public Map<Enchantment, Integer> getEnchants()    { return enchants; }
    public void setEnchants(Map<Enchantment, Integer> e) { this.enchants = e; }

    // EFFECT
    public PotionEffectType getPotionEffect()         { return potionEffect; }
    public void             setPotionEffect(PotionEffectType t) { this.potionEffect = t; }
    public int              getDurationSeconds()      { return durationSeconds; }
    public void             setDurationSeconds(int s) { this.durationSeconds = s; }
    public int              getAmplifier()            { return amplifier; }
    public void             setAmplifier(int a)       { this.amplifier = a; }

    // EXPLOSION
    public float getExplosionPower()         { return explosionPower; }
    public void  setExplosionPower(float p)  { this.explosionPower = p; }

    // MOB
    public EntityType getMobType()           { return mobType; }
    public void       setMobType(EntityType t) { this.mobType = t; }
    public int        getMobCount()          { return mobCount; }
    public void       setMobCount(int c)     { this.mobCount = c; }

    // COINS / POINTS
    public int  getRewardAmount()    { return rewardAmount; }
    public void setRewardAmount(int a) { this.rewardAmount = a; }

    // FULL_ARMOR
    public Material getArmorMaterial()        { return armorMaterial; }
    public void     setArmorMaterial(Material m) { this.armorMaterial = m; }

    // TNT_RAIN
    public int  getTntCount()     { return tntCount; }
    public void setTntCount(int c){ this.tntCount = c; }
}
