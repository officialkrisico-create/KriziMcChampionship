package nl.kmc.kmccore.host;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Text helpers for the speech bubble: colour codes, word wrapping that keeps the colour across lines,
 * paginating long messages into short readable pages, and {@code {placeholder}} expansion.
 * No Bukkit dependency (so it is unit-tested).
 */
public final class HostText {

    private HostText() { }

    private static final char SECTION = '§';

    /** {@code &6} → {@code §6} (only before a real colour/format code, so "R&D" stays as it is). */
    public static String color(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefABCDEFklmnorKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                sb.append(SECTION);
            } else sb.append(c);
        }
        return sb.toString();
    }

    /** Removes colour codes (both § and &amp;). */
    public static String strip(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == SECTION || c == '&') && i + 1 < s.length()
                    && "0123456789abcdefABCDEFklmnorKLMNOR".indexOf(s.charAt(i + 1)) >= 0) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    public static int visibleLength(String s) { return strip(s).length(); }

    /**
     * Wraps {@code text} to lines of at most {@code width} visible characters. A colour or format that is
     * active at a break is repeated at the start of the next line, so a wrapped coloured sentence stays
     * coloured. A single word longer than the width is cut.
     */
    public static List<String> wrap(String text, int width) {
        String s = color(text == null ? "" : text);
        int w = Math.max(8, width);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int visible = 0;
        String active = "";              // codes in force at this point

        for (String word : s.split(" ", -1)) {
            while (visibleLength(word) > w) {      // a word that never fits: cut it
                if (visible > 0) { lines.add(line.toString()); line = new StringBuilder(active); visible = 0; }
                int cut = cutIndex(word, w);
                String head = word.substring(0, cut);
                lines.add(line + head);
                active = trackCodes(active, head);
                line = new StringBuilder(active);
                word = word.substring(cut);
            }
            int wl = visibleLength(word);
            if (visible > 0 && visible + 1 + wl > w) {
                lines.add(line.toString());
                line = new StringBuilder(active);
                visible = 0;
            }
            if (visible > 0) { line.append(' '); visible++; }
            line.append(word);
            visible += wl;
            active = trackCodes(active, word);
        }
        if (visible > 0 || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** Index in {@code word} after {@code visibleChars} visible characters (codes are not counted). */
    private static int cutIndex(String word, int visibleChars) {
        int seen = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == SECTION && i + 1 < word.length()) { i++; continue; }
            if (seen == visibleChars) return i;
            seen++;
        }
        return word.length();
    }

    /** The codes in force after {@code s}, given those before it. A colour resets formats; §r resets all. */
    private static String trackCodes(String active, String s) {
        String colour = "";
        StringBuilder formats = new StringBuilder();
        // parse the active string first
        for (int i = 0; i + 1 < active.length(); i += 2) {
            char code = Character.toLowerCase(active.charAt(i + 1));
            if (code == 'r') { colour = ""; formats.setLength(0); }
            else if ("klmno".indexOf(code) >= 0) { if (formats.indexOf(String.valueOf(code)) < 0) formats.append(code); }
            else { colour = String.valueOf(code); formats.setLength(0); }
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != SECTION) continue;
            char code = Character.toLowerCase(s.charAt(i + 1));
            i++;
            if (code == 'r') { colour = ""; formats.setLength(0); }
            else if ("klmno".indexOf(code) >= 0) { if (formats.indexOf(String.valueOf(code)) < 0) formats.append(code); }
            else if ("0123456789abcdef".indexOf(code) >= 0) { colour = String.valueOf(code); formats.setLength(0); }
        }
        StringBuilder out = new StringBuilder();
        if (!colour.isEmpty()) out.append(SECTION).append(colour);
        for (int i = 0; i < formats.length(); i++) out.append(SECTION).append(formats.charAt(i));
        return out.toString();
    }

    /**
     * Splits a message into pages of at most {@code maxLines} wrapped lines. A page ends at the end of a
     * sentence whenever it can: a sentence that does not fit on the page goes to a new page whole, and is only
     * cut (at a word) when it is longer than a page by itself. Colour codes carry over to the next page.
     * Each page is the lines joined by {@code \n}; a short message is a single page.
     */
    public static List<String> paginate(String text, int width, int maxLines) {
        int per = Math.max(1, maxLines);
        List<String> pages = new ArrayList<>();
        if (text == null || text.isBlank()) { pages.add(""); return pages; }

        String active = "";                      // codes in force at the start of the page being built
        String page = "";
        String pageStart = "";
        for (String sentence : sentences(text)) {
            String candidate = page.isEmpty() ? pageStart + sentence : page + " " + sentence;
            if (wrap(candidate, width).size() <= per) { page = candidate; active = trackCodes(active, sentence); continue; }
            if (!page.isEmpty()) {
                pages.add(String.join("\n", wrap(page, width)));
                page = "";
            }
            pageStart = active;
            String own = pageStart + sentence;
            List<String> lines = wrap(own, width);
            if (lines.size() <= per) {
                page = own;
                active = trackCodes(active, sentence);
            } else {                             // longer than a page by itself: cut at words, in page-sized chunks
                for (int i = 0; i < lines.size(); i += per) {
                    pages.add(String.join("\n", lines.subList(i, Math.min(lines.size(), i + per))));
                }
                active = trackCodes(active, sentence);
                pageStart = active;
            }
        }
        if (!page.isEmpty()) pages.add(String.join("\n", wrap(page, width)));
        if (pages.isEmpty()) pages.add("");
        return pages;
    }

    /** The sentences of {@code text} (a sentence ends at . ! ? or … followed by a space); the end mark stays. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < text.length()) { cur.append(c).append(text.charAt(++i)); continue; }
            cur.append(c);
            boolean end = c == '.' || c == '!' || c == '?' || c == '…';
            if (!end) continue;
            // swallow more end marks and closing quotes/colour codes, then a space ends the sentence
            int j = i + 1;
            while (j < text.length() && (".!?…\"')".indexOf(text.charAt(j)) >= 0)) cur.append(text.charAt(j++));
            while (j + 1 < text.length() && text.charAt(j) == SECTION) { cur.append(text.charAt(j)).append(text.charAt(j + 1)); j += 2; }
            if (j >= text.length() || text.charAt(j) == ' ') {
                out.add(cur.toString().trim());
                cur.setLength(0);
                i = j;                           // the space (if any) is skipped
            } else {
                i = j - 1;
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    // ── Placeholders ──────────────────────────────────────────────────────────

    public record Expanded(String text, Set<String> unknown, Set<String> empty) { }

    /** The names used in {@code {name}} placeholders of a template, in order of appearance. */
    public static Set<String> placeholders(String template) {
        Set<String> out = new LinkedHashSet<>();
        if (template == null) return out;
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) break;
            int close = template.indexOf('}', open + 1);
            if (close < 0) break;
            String name = template.substring(open + 1, close);
            if (isName(name)) out.add(name);
            i = close + 1;
        }
        return out;
    }

    private static boolean isName(String s) {
        if (s.isEmpty() || s.length() > 40) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /**
     * Replaces every {@code {name}} by its value. A name that is not in {@code known} is reported in
     * {@code unknown} (and replaced by nothing); a known name without a value right now (for example no
     * leader yet) is reported in {@code empty}.
     */
    public static Expanded expand(String template, Map<String, String> values, Set<String> known) {
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> empty = new LinkedHashSet<>();
        if (template == null) return new Expanded("", unknown, empty);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) { out.append(template, i, template.length()); break; }
            int close = template.indexOf('}', open + 1);
            if (close < 0) { out.append(template, i, template.length()); break; }
            String name = template.substring(open + 1, close);
            out.append(template, i, open);
            if (!isName(name)) {
                out.append(template, open, close + 1);        // not a placeholder: leave "{...}" alone
            } else if (!known.contains(name)) {
                unknown.add(name);
            } else {
                String v = values.get(name);
                if (v == null || v.isBlank()) empty.add(name);
                else out.append(v);
            }
            i = close + 1;
        }
        return new Expanded(out.toString(), unknown, empty);
    }
}
