package nl.kmc.blockparty.models;

/**
 * Random "chaos" modifiers that spice up later rounds. Each carries a
 * spectator-facing title/colour; the game manager applies the mechanical
 * effect when a round begins.
 */
public enum ChaosEvent {

    LOW_GRAVITY   ("§b⬆ LOW GRAVITY",   "§7Spring hoog — verminderde zwaartekracht"),
    SPEED_ROUND   ("§e» SPEED ROUND",   "§7Iedereen krijgt snelheid"),
    ICE_FLOOR     ("§f❄ ICE FLOOR",     "§7Gladde vloer — pas op met sturen"),
    DARKNESS      ("§8🌑 DARKNESS",      "§7Beperkt zicht — vertrouw je ogen"),
    DOUBLE_COLOR  ("§a⧉ DOUBLE COLOR",  "§7Twee kleuren overleven deze ronde"),
    FAKE_COLOR    ("§c⚠ FAKE COLOR",    "§7De getoonde kleur is FOUT — let op de hint"),
    RANDOM_TP     ("§d✦ RANDOM TELEPORT","§7Iedereen wordt herplaatst"),
    COLOR_BLIND   ("§7◑ COLOR BLIND",    "§7Geen kleurnaam — herken het zelf"),
    RAPID_FIRE    ("§6⚡ RAPID FIRE",     "§7Extra korte timer");

    private final String title;
    private final String subtitle;

    ChaosEvent(String title, String subtitle) {
        this.title    = title;
        this.subtitle = subtitle;
    }

    public String title()    { return title; }
    public String subtitle() { return subtitle; }
}
