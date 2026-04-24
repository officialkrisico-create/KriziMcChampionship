package nl.kmc.adventure.models;

import org.bukkit.Material;
import org.bukkit.Sound;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One entry from the effect-blocks config section.
 *
 * <p>When a player walks over a block of the specified {@link #material},
 * they get the configured effects and equipment for {@link #durationSeconds}.
 */
public class EffectBlock {

    private final String   id;
    private final Material material;
    private final int      durationSeconds;
    /** List of maps with keys: "type" (effect id) and "amplifier" (int). */
    private final List<Map<String, Object>> effects;
    private final List<String>              equipment;
    private final String                    message;
    private final Sound                     sound;

    public EffectBlock(String id, Material material, int durationSeconds,
                       List<Map<String, Object>> effects,
                       List<String> equipment,
                       String message, Sound sound) {
        this.id              = id;
        this.material        = material;
        this.durationSeconds = durationSeconds;
        this.effects         = effects != null   ? effects   : Collections.emptyList();
        this.equipment       = equipment != null ? equipment : Collections.emptyList();
        this.message         = message;
        this.sound           = sound;
    }

    public String    getId()              { return id; }
    public Material  getMaterial()        { return material; }
    public int       getDurationSeconds() { return durationSeconds; }
    public List<Map<String, Object>> getEffects() { return effects; }
    public List<String> getEquipment()    { return equipment; }
    public String    getMessage()         { return message; }
    public Sound     getSound()           { return sound; }
}
