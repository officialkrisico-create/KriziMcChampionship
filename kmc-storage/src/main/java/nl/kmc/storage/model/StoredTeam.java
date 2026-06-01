package nl.kmc.storage.model;

/** Flat persistence record for a KMC team. */
public final class StoredTeam {

    public String id;
    public String displayName;
    public String color;       // ChatColor name, e.g. "RED"
    public String tagColor;    // Team tag color name
    public int points;
    public int wins;

    public StoredTeam() {}

    public StoredTeam(String id, String displayName, String color, String tagColor) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.tagColor = tagColor;
    }
}
