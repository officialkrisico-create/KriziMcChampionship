package nl.kmc.kmccore.validation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Compares a config file with the defaults that ship in the jar and reports what is probably wrong:
 * keys that do not exist (with a "did you mean ...?"), values of the wrong type, and values that make no
 * sense for what their name says (a negative number of seconds, a chance above 1, min above max).
 *
 * <p>It is deliberately careful with free-form sections — maps keyed by an id the admin chooses (teams,
 * games, rounds, dialogues): new keys there are not typos. It never changes a file. No server needed.
 */
public final class ConfigDoctor {

    private ConfigDoctor() { }

    public enum Severity { ERROR, WARN, INFO }

    public record Finding(String file, String path, Severity severity, String message, String hint) { }

    /**
     * @param file          label for the report (for example {@code CraftingCountdown/config.yml})
     * @param defaults      the config as it ships in the jar
     * @param actual        the file on disk, loaded without defaults
     * @param freeForm      paths whose children are free-form (in addition to what is detected)
     * @param materialExists true when the name is a real material (null = do not check names)
     */
    public static List<Finding> compare(String file, ConfigurationSection defaults, ConfigurationSection actual,
                                        Set<String> freeForm, Predicate<String> materialExists) {
        List<Finding> out = new ArrayList<>();
        if (actual == null) return out;
        walk(file, "", defaults, actual, freeForm == null ? Set.of() : freeForm, materialExists, out);
        return out;
    }

    /** Sections that setup commands and the director write themselves (arenas, courses): never "unknown". */
    private static boolean writtenByCommands(String path) {
        for (String root : new String[]{"arena", "course", "director"})
            if (path.equals(root) || path.startsWith(root + ".")) return true;
        return false;
    }

    private static void walk(String file, String path, ConfigurationSection def, ConfigurationSection act,
                             Set<String> freeForm, Predicate<String> materialExists, List<Finding> out) {
        boolean free = isFree(path, def, freeForm);
        List<String> defKeys = def == null ? List.of() : new ArrayList<>(def.getKeys(false));

        for (String key : act.getKeys(false)) {
            String p = path.isEmpty() ? key : path + "." + key;
            Object a = act.get(key);
            if (!defKeys.contains(key)) {
                if (!free && def != null && !writtenByCommands(p)) {
                    String near = suggest(key, defKeys, act.getKeys(false));
                    out.add(new Finding(file, p, Severity.WARN, "onbekende sleutel (wordt genegeerd)",
                            near != null ? "bedoelde je '" + near + "'?" : "verwijderen of controleren op een typefout"));
                }
                checkSubtree(file, p, key, a, materialExists, out);
                continue;
            }
            Object d = def.get(key);
            if (d instanceof ConfigurationSection ds) {
                if (a instanceof ConfigurationSection as) walk(file, p, ds, as, freeForm, materialExists, out);
                else if (a != null) out.add(mismatch(file, p, "een sectie", a));
            } else if (d instanceof Boolean && a != null && !(a instanceof Boolean)) {
                out.add(mismatch(file, p, "true of false", a));
            } else if (d instanceof Number && a != null && !(a instanceof Number)) {
                out.add(mismatch(file, p, "een getal", a));
            } else if (d instanceof List && a != null && !(a instanceof List)) {
                out.add(mismatch(file, p, "een lijst", a));
            } else if (d instanceof String && (a instanceof ConfigurationSection || a instanceof List)) {
                out.add(mismatch(file, p, "tekst", a));
            } else {
                checkValue(file, p, key, a, materialExists, out);
            }
        }
        checkMinMax(file, path, act, out);
    }

    /** A section whose children are keyed by something the admin chooses: new keys are not typos. */
    static boolean isFree(String path, ConfigurationSection def, Set<String> freeForm) {
        if (path.isEmpty()) return false;
        if (freeForm.contains(path)) return true;
        if (def == null) return true;
        Set<String> keys = def.getKeys(false);
        if (keys.isEmpty()) return true;
        boolean allNumeric = true, allSections = true;
        for (String k : keys) {
            if (!k.matches("-?\\d+")) allNumeric = false;
            if (!def.isConfigurationSection(k)) allSections = false;
        }
        return allNumeric || allSections;
    }

    private static void checkSubtree(String file, String path, String key, Object value,
                                     Predicate<String> materialExists, List<Finding> out) {
        if (value instanceof ConfigurationSection s) {
            for (String k : s.getKeys(false)) {
                checkSubtree(file, path + "." + k, k, s.get(k), materialExists, out);
            }
            checkMinMax(file, path, s, out);
        } else {
            checkValue(file, path, key, value, materialExists, out);
        }
    }

    private static Finding mismatch(String file, String path, String expected, Object actual) {
        String got = actual instanceof ConfigurationSection ? "een sectie"
                : actual instanceof List ? "een lijst" : "'" + actual + "'";
        return new Finding(file, path, Severity.ERROR, "verkeerd type: verwacht " + expected + ", gevonden " + got,
                "corrigeer de waarde (de standaardwaarde wordt anders gebruikt of het gedrag is onvoorspelbaar)");
    }

    // ── Value rules (by what the name says) ───────────────────────────────────

    private static void checkValue(String file, String path, String key, Object a,
                                   Predicate<String> materialExists, List<Finding> out) {
        String k = key.toLowerCase(Locale.ROOT);
        if (a instanceof Number n) {
            double v = n.doubleValue();
            if (k.endsWith("chance") || k.endsWith("probability")) {
                if (v < 0 || v > 1)
                    out.add(new Finding(file, path, Severity.ERROR, "een kans moet tussen 0 en 1 liggen (nu " + fmt(v) + ")", "bijvoorbeeld 0.25 voor 25%"));
            } else if (k.endsWith("multiplier")) {
                if (v <= 0)
                    out.add(new Finding(file, path, Severity.ERROR, "een multiplier moet groter zijn dan 0 (nu " + fmt(v) + ")", "1.0 = geen effect"));
            } else if (k.contains("percent")) {
                if (v < 0 || v > 100)
                    out.add(new Finding(file, path, Severity.WARN, "een percentage hoort tussen 0 en 100 te liggen (nu " + fmt(v) + ")", ""));
            } else if (v < 0 && (k.endsWith("seconds") || k.endsWith("ticks") || k.endsWith("minutes")
                    || k.endsWith("count") || k.endsWith("cost") || k.endsWith("size") || k.endsWith("radius"))) {
                out.add(new Finding(file, path, Severity.WARN, "negatieve waarde (" + fmt(v) + ") voor een duur, aantal of afstand",
                        "bedoeld? 0 betekent meestal 'uit'"));
            }
        } else if (a instanceof String s && materialExists != null && !s.isBlank()
                && (k.equals("icon") || k.equals("material") || k.endsWith("-material"))) {
            if (!materialExists.test(s))
                out.add(new Finding(file, path, Severity.WARN, "'" + s + "' is geen bekend materiaal", "gebruik een naam zoals WHITE_CONCRETE"));
        }
    }

    /** In one section: {@code min-x} must not exceed {@code max-x}, nor {@code min} exceed {@code max}. */
    private static void checkMinMax(String file, String path, ConfigurationSection s, List<Finding> out) {
        for (String key : s.getKeys(false)) {
            String min = key.toLowerCase(Locale.ROOT);
            String max;
            if (min.equals("min")) max = "max";
            else if (min.startsWith("min-")) max = "max-" + min.substring(4);
            else if (min.endsWith("-min")) max = min.substring(0, min.length() - 4) + "-max";
            else continue;
            if (!(s.get(key) instanceof Number lo)) continue;
            for (String other : s.getKeys(false)) {
                if (other.equalsIgnoreCase(max) && s.get(other) instanceof Number hi && lo.doubleValue() > hi.doubleValue()) {
                    String p = path.isEmpty() ? key : path + "." + key;
                    out.add(new Finding(file, p, Severity.ERROR,
                            key + " (" + fmt(lo.doubleValue()) + ") is groter dan " + other + " (" + fmt(hi.doubleValue()) + ")",
                            "wissel de twee waarden om"));
                }
            }
        }
    }

    // ── Typo suggestions ──────────────────────────────────────────────────────

    /** The closest default key (not already set in the file), when it is close enough to be a typo. */
    public static String suggest(String key, List<String> candidates, Set<String> alreadyPresent) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        String k = key.toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            if (alreadyPresent != null && alreadyPresent.contains(c)) continue;
            int d = distance(k, c.toLowerCase(Locale.ROOT));
            if (d < bestD) { bestD = d; best = c; }
        }
        if (best == null) return null;
        int limit = Math.max(2, Math.min(k.length(), best.length()) / 4);
        return bestD <= limit ? best : null;
    }

    /** Levenshtein distance. */
    public static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
