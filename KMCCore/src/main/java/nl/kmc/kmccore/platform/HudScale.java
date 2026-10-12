package nl.kmc.kmccore.platform;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-player HUD density for the sidebar: {@code normal} (everything), {@code compact} (no blank lines, at
 * most 10 lines) and {@code minimal} (at most 5 lines). For small windows and busy streams. Stored in the
 * player preferences ({@code core.hud-scale}); {@code /kmchud} changes it.
 */
public final class HudScale {

    public static final List<String> MODES = List.of("normal", "compact", "minimal");

    private static final String GAME = "core";
    private static final String KEY = "hud-scale";

    private HudScale() { }

    public static String get(KMCCore plugin, Player p) {
        try {
            String m = plugin.getPlayerPreferences().getString(p.getUniqueId(), GAME, KEY, "normal");
            return MODES.contains(m) ? m : "normal";
        } catch (Throwable t) { return "normal"; }
    }

    public static void set(KMCCore plugin, Player p, String mode) {
        plugin.getPlayerPreferences().set(p.getUniqueId(), GAME, KEY, mode.toLowerCase(Locale.ROOT));
    }

    /** Next mode in the cycle normal → compact → minimal → normal. */
    public static String next(String mode) {
        return MODES.get((MODES.indexOf(mode) + 1) % MODES.size());
    }

    // ── Readable width ────────────────────────────────────────────────────────

    /** Widest sidebar line (in pixels) that still fits comfortably for this mode. */
    public static int maxPx(KMCCore plugin, Player p) {
        String mode = get(plugin, p);
        int def = switch (mode) { case "minimal" -> 110; case "compact" -> 135; default -> 165; };
        return Math.max(60, plugin.getConfig().getInt("hud.max-px." + mode, def));
    }

    /** Longest allowed entry in characters: a sidebar entry is at most 40 and the unique prefix takes 4. */
    public static final int MAX_ENTRY_CHARS = 36;

    public static List<String> fitAll(KMCCore plugin, Player p, List<String> lines) {
        int px = maxPx(plugin, p);
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(fit(l, px, MAX_ENTRY_CHARS));
        return out;
    }

    /**
     * Shortens a colour-coded string to at most {@code maxPx} pixels and {@code maxChars} characters (colour
     * codes included), ending with an ellipsis. A colour code is never cut in half, which a plain
     * {@code substring} would do.
     */
    public static String fit(String s, int maxPx, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars && nl.kmc.kmccore.presentation.ChatLayout.px(s) <= maxPx) return s;
        final String ellipsis = "…";
        int ellPx = nl.kmc.kmccore.presentation.ChatLayout.px(ellipsis);
        StringBuilder out = new StringBuilder();
        int px = 0;
        boolean bold = false;
        int budgetChars = maxChars - ellipsis.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                if (out.length() + 2 > budgetChars) break;
                if (code == 'l') bold = true;
                else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) bold = false;
                out.append(c).append(s.charAt(i + 1));
                i++;
                continue;
            }
            int w = nl.kmc.kmccore.presentation.ChatLayout.px((bold ? "§l" : "") + c);
            if (px + w > maxPx - ellPx || out.length() + 1 > budgetChars) break;
            out.append(c);
            px += w;
        }
        return out + ellipsis;
    }

    /** Applies the player's mode to the sidebar lines. */
    public static List<String> apply(KMCCore plugin, Player p, List<String> lines) {
        return apply(get(plugin, p), lines);
    }

    public static List<String> apply(String mode, List<String> lines) {
        if (lines == null || mode == null || mode.equals("normal")) return lines;
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l == null) continue;
            if (org.bukkit.ChatColor.stripColor(l).isBlank()) continue;
            out.add(l);
        }
        int max = mode.equals("minimal") ? 5 : 10;
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
