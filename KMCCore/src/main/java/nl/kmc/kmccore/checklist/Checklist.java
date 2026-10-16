package nl.kmc.kmccore.checklist;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tournament checklist: everything an admin still has to do before (and during) an event, to tick off.
 * It starts with a list of standard preparations, the admin adds and removes items, and the state lives in
 * {@code checklist.yml}. No Bukkit server needed, so it is unit-testable.
 *
 * <p>A standard item that a later version adds appears in an existing list too; one the admin removed does
 * not come back (the file remembers which standard items it already knows).
 */
public final class Checklist {

    /** One thing to do. {@code key} is set for standard items only. */
    public static final class Item {
        private final String key;
        private String text;
        private boolean done;

        Item(String key, String text, boolean done) {
            this.key = key;
            this.text = text;
            this.done = done;
        }

        public String key()      { return key; }
        public String text()     { return text; }
        public boolean done()    { return done; }
        public boolean standard(){ return key != null && !key.isEmpty(); }
    }

    /** The standard preparations (key → text), in the order they are best done. */
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("restart", "Server herstart met alle nieuwe jars (niet /reload)");
        DEFAULTS.put("teams", "Teams gemaakt en alle spelers verdeeld (/kmcteam)");
        DEFAULTS.put("team-size", "Teamgrootte klopt (teams.max-players-per-team) en iedereen zit in een team");
        DEFAULTS.put("lobby", "Lobby-spawn ingesteld en getest");
        DEFAULTS.put("podium", "Podium gebouwd (/kmcpodium set, kijk naar de toeschouwers)");
        DEFAULTS.put("host", "Host geplaatst met skin (/kmchost setspawn, /kmchost test)");
        DEFAULTS.put("npcs", "Rank-NPC's en Hall of Fame geplaatst (/kmclobbynpc)");
        DEFAULTS.put("validate", "Alle games geconfigureerd (/kmcvalidate)");
        DEFAULTS.put("dryrun", "Dry-run van de rotatie gedaan (/kmcauto dryrun)");
        DEFAULTS.put("setup", "Aantal games, rondes, pauzes en finale ingesteld (/kmcauto start-menu)");
        DEFAULTS.put("finale", "Finale-arena (Dodgebolt) klaar en getest");
        DEFAULTS.put("hud", "Tablist en HUD gecontroleerd (/kmchud test)");
        DEFAULTS.put("ceremony", "Opening en ceremonie bekeken (/kmcauto start, /kmcauto skip)");
        DEFAULTS.put("stream", "Stream: OBS-overlay en clip-markers getest");
        DEFAULTS.put("tutorial", "Spelers weten hoe het werkt (/tutorial) en hebben hun taal gekozen");
        DEFAULTS.put("bots", "Testbots verwijderd (/kmcbot remove) en testdata gewist");
        DEFAULTS.put("backup", "Backup van de wereld en de plugins gemaakt");
        DEFAULTS.put("perms", "Admin-rechten gecontroleerd (wie is OP?)");
    }

    private final File file;
    private final List<Item> items = new ArrayList<>();
    private final List<String> knownDefaults = new ArrayList<>();

    public Checklist(File file) {
        this.file = file;
        load();
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    public List<Item> items() { return List.copyOf(items); }

    public int size() { return items.size(); }

    public int doneCount() {
        int n = 0;
        for (Item i : items) if (i.done) n++;
        return n;
    }

    public boolean complete() { return !items.isEmpty() && doneCount() == items.size(); }

    // ── Changing ─────────────────────────────────────────────────────────────

    /** Ticks item {@code index} (0-based) on or off; returns its new state, or null for a bad index. */
    public Boolean toggle(int index) {
        if (index < 0 || index >= items.size()) return null;
        Item i = items.get(index);
        i.done = !i.done;
        save();
        return i.done;
    }

    public void add(String text) {
        if (text == null || text.isBlank()) return;
        items.add(new Item(null, text.trim(), false));
        save();
    }

    /** Removes item {@code index}; a standard item stays removed. Returns the removed text, or null. */
    public String remove(int index) {
        if (index < 0 || index >= items.size()) return null;
        String text = items.remove(index).text;
        save();
        return text;
    }

    /** Unticks everything (a new event). */
    public void reset() {
        for (Item i : items) i.done = false;
        save();
    }

    /** Puts the standard items back (removed ones included) and keeps the admin's own. */
    public void restoreDefaults() {
        for (var e : DEFAULTS.entrySet()) {
            boolean has = false;
            for (Item i : items) if (e.getKey().equals(i.key)) { has = true; break; }
            if (!has) items.add(new Item(e.getKey(), e.getValue(), false));
        }
        for (String k : DEFAULTS.keySet()) if (!knownDefaults.contains(k)) knownDefaults.add(k);
        save();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void load() {
        items.clear();
        knownDefaults.clear();
        if (file != null && file.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            knownDefaults.addAll(y.getStringList("known-defaults"));
            for (Map<?, ?> m : y.getMapList("items")) {
                Object text = m.get("text");
                if (text == null) continue;
                Object key = m.get("key");
                Object done = m.get("done");
                items.add(new Item(key == null ? null : String.valueOf(key), String.valueOf(text),
                        done instanceof Boolean b && b));
            }
        }
        // standard items this file has never seen (a first start, or a newer version): added at the end
        boolean changed = false;
        for (var e : DEFAULTS.entrySet()) {
            if (knownDefaults.contains(e.getKey())) continue;
            items.add(new Item(e.getKey(), e.getValue(), false));
            knownDefaults.add(e.getKey());
            changed = true;
        }
        if (changed) save();
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("known-defaults", knownDefaults);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (i.standard()) m.put("key", i.key);
            m.put("text", i.text);
            m.put("done", i.done);
            out.add(m);
        }
        y.set("items", out);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            y.save(file);
        } catch (IOException ignored) { }
    }
}
