package nl.kmc.kmccore.presentation;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat layout helpers for the tournament presentation: pixel-accurate centering and
 * word-wrapping (Minecraft's chat font is proportional, so character counts lie), plus the
 * frames/headers/cards the ceremonies are built from.
 *
 * <p>All input is already colour-translated (uses {@code §}). Widths assume the default
 * 320px chat width; wrapping stays a little under that so lines never get cut mid-word.
 */
public final class ChatLayout {

    /** Wrap width in px — a bit under the default 320px chat width. */
    public static final int WRAP_PX = 296;
    private static final int CENTER_PX = 154;
    private static final int SPACE_PX  = 4;      // 3px glyph + 1px spacing
    private static final int RULE_SPACES = 73;   // 73 * 4px = 292px strike-through rule

    private ChatLayout() {}

    // ── Measuring ─────────────────────────────────────────────────────────────

    private static int glyphPx(char c) {
        if (c > 255) return 7;               // symbols (★ ✦ ▬ …) fall back to the wider unicode font
        return switch (c) {
            case 'i', 'l', '!', ':', ';', '|', '\'', '.', ',' -> 1;
            case '`' -> 2;
            case 'I', 't', ' ', '"', '*', '[', ']' -> 3;
            case 'f', 'k', '(', ')', '{', '}', '<', '>' -> 4;
            case '@', '~' -> 6;
            default -> 5;
        };
    }

    /** Visible pixel width of a colour-coded string (bold is one pixel wider per glyph). */
    public static int px(String s) {
        int total = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (code == 'l') bold = true;
                else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) bold = false;
                continue;
            }
            total += glyphPx(c) + 1 + (bold ? 1 : 0);
        }
        return total;
    }

    /** Number of visible characters (colour codes excluded). */
    public static int visibleLength(String s) { return ChatColor.stripColor(s).length(); }

    // ── Centering / wrapping ──────────────────────────────────────────────────

    public static String spaces(int px) { return " ".repeat(Math.max(0, Math.round(px / (float) SPACE_PX))); }

    public static String center(String s) {
        int pad = (CENTER_PX - px(s) / 2) / SPACE_PX;
        return pad > 0 ? " ".repeat(pad) + s : s;
    }

    /** Colour/format codes in effect after {@code word}, carried on from {@code active}. */
    private static String updateActive(String active, String word) {
        for (int i = 0; i + 1 < word.length(); i++) {
            if (word.charAt(i) != '§') continue;
            char code = Character.toLowerCase(word.charAt(++i));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) active = "§" + code;
            else if (code == 'r') active = "";
            else if ("lmnok".indexOf(code) >= 0 && !active.contains("§" + code)) active += "§" + code;
        }
        return active;
    }

    /** Word-wraps {@code text}; continuation lines get {@code hangIndent} and keep the active colour. */
    public static List<String> wrap(String text, int maxPx, String firstIndent, String hangIndent) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder(firstIndent);
        int linePx = px(firstIndent);
        boolean hasWord = false;
        String active = "";

        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            int wordPx = px(word);
            if (hasWord && linePx + SPACE_PX + wordPx > maxPx) {
                out.add(line.toString());
                line = new StringBuilder(hangIndent).append(active);
                linePx = px(hangIndent);
                hasWord = false;
            }
            if (hasWord) { line.append(' '); linePx += SPACE_PX; }
            line.append(word);
            linePx += wordPx;
            hasWord = true;
            active = updateActive(active, word);
        }
        if (hasWord || out.isEmpty()) out.add(line.toString());
        return out;
    }

    /**
     * Lays out one message from ceremonies.yml. A leading {@code <c>} centres it; otherwise it is
     * left-aligned with its own indent, and wrapped lines hang under the text (aligned past a
     * short bullet/number such as "1." or "•").
     */
    public static List<String> body(String message) {
        if (message == null || message.isBlank()) return List.of("");

        String trimmed = message.stripLeading();
        if (trimmed.startsWith("<c>")) {
            List<String> out = new ArrayList<>();
            for (String l : wrap(trimmed.substring(3).stripLeading(), WRAP_PX - 20, "", ""))
                out.add(center(l));
            return out;
        }

        int lead = message.length() - trimmed.length();
        String indent = " ".repeat(Math.max(1, Math.min(lead, 6)));
        String[] parts = trimmed.split(" ", 2);
        String hang = indent + "  ";
        if (parts.length == 2 && visibleLength(parts[0]) <= 3)
            hang = indent + spaces(px(parts[0]) + SPACE_PX);
        return wrap(trimmed, WRAP_PX, indent, hang);
    }

    // ── Frames ────────────────────────────────────────────────────────────────

    /** A full-width strike-through rule in the given colour code ('6', 'b', …). */
    public static String rule(char color) { return "§" + color + "§m" + " ".repeat(RULE_SPACES) + "§r"; }

    /** Framed, centred stage header: rule, ✦ TITLE ✦, subtitle, rule. */
    public static List<String> header(char color, String title, String subtitle) {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add(rule(color));
        out.add(center("§" + color + "§l✦ §f§l" + title.toUpperCase() + " §" + color + "§l✦"));
        if (subtitle != null && !subtitle.isBlank()) out.add(center("§7§o" + subtitle));
        out.add(rule(color));
        out.add("");
        return out;
    }

    /** One game in the line-up: numbered, centred name, description, goal and scoring. */
    public static List<String> gameCard(int n, int total, String name, String description,
                                        String objective, List<String> scoring) {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add(rule('a'));
        out.add(center("§a§lGAME §f§l" + n + " §8§l/ §7§l" + total));
        out.add(center("§e§l✦ §6§l" + name.toUpperCase() + " §e§l✦"));
        out.add("");
        if (description != null && !description.isBlank())
            for (String l : wrap("§7§o" + description, WRAP_PX - 30, "", "")) out.add(center(l));
        if (objective != null && !objective.isBlank()) {
            out.add("");
            out.addAll(wrap("§6§lDOEL §8» §f" + objective, WRAP_PX, " ", spaces(px(" §6§lDOEL §8» "))));
        }
        if (scoring != null && !scoring.isEmpty()) {
            out.add("");
            out.add(" §b§lPUNTEN §8»");
            int shown = 0;
            for (String s : scoring) {
                if (shown++ >= 4) break;
                int dash = s.indexOf(" — ");
                String pretty = dash > 0
                        ? "§e" + s.substring(0, dash) + " §8— §7" + s.substring(dash + 3)
                        : "§f" + s;
                out.addAll(wrap("§8• " + pretty, WRAP_PX, "   ", "     "));
            }
        }
        out.add(rule('a'));
        return out;
    }
}
