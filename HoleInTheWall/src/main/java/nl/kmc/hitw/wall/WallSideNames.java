package nl.kmc.hitw.wall;

import java.util.List;
import java.util.Locale;

/**
 * Names of the sides walls come from, built from the arena's colours: Rood (west), Blauw (zuid), Geel (oost),
 * Groen (noord). A wall coming from a corner gets both colours, e.g. "RoodBlauw" for the south-west.
 *
 * <p>Index = compass slot in 45° steps starting at east: 0 = O, 1 = ZO, 2 = Z, 3 = ZW, 4 = W, 5 = NW, 6 = N,
 * 7 = NO. The names can be changed in {@code walls.side-names}.
 */
public final class WallSideNames {

    private WallSideNames() { }

    public static final List<String> DEFAULT = List.of(
            "Geel", "BlauwGeel", "Blauw", "RoodBlauw", "Rood", "GroenRood", "Groen", "GeelGroen");

    /** 0..7 for the side a wall with this normal angle comes from (0 = east, π/2 = south). */
    public static int slot(double angleRad) {
        double deg = ((Math.toDegrees(angleRad) % 360) + 360) % 360;
        return (int) Math.round(deg / 45.0) % 8;
    }

    /** The configured name for that side, falling back to the defaults. */
    public static String name(double angleRad, List<String> configured) {
        int s = slot(angleRad);
        List<String> src = configured != null && configured.size() == 8 ? configured : DEFAULT;
        String n = src.get(s);
        return n == null || n.isBlank() ? DEFAULT.get(s) : n;
    }

    /**
     * Colours every colour word of a name in its own chat colour ("RoodBlauw" → "§cRood§9Blauw").
     * Unknown words stay white.
     */
    public static String colored(String name) {
        StringBuilder out = new StringBuilder();
        int start = 0;
        for (int i = 1; i <= name.length(); i++) {
            boolean boundary = i == name.length() || Character.isUpperCase(name.charAt(i));
            if (!boundary) continue;
            String word = name.substring(start, i);
            out.append(code(word)).append(word);
            start = i;
        }
        return out.toString();
    }

    private static String code(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "rood", "red" -> "§c";
            case "blauw", "blue" -> "§9";
            case "geel", "yellow" -> "§e";
            case "groen", "green" -> "§a";
            case "oranje", "orange" -> "§6";
            case "paars", "purple" -> "§5";
            case "wit", "white" -> "§f";
            case "zwart", "black" -> "§8";
            default -> "§f";
        };
    }
}
