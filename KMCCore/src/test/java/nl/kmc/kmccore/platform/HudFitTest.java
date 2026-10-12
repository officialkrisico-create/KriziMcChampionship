package nl.kmc.kmccore.platform;

import nl.kmc.kmccore.presentation.ChatLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudFitTest {

    @Test
    void shortLinesAreUntouched() {
        assertEquals("§7Tijd §f01:30", HudScale.fit("§7Tijd §f01:30", 165, 36));
    }

    @Test
    void longLinesFitTheWidthAndCharacterBudget() {
        String in = "§aDit is een heel erg lange regel die nooit op het scherm past zonder afkappen";
        String out = HudScale.fit(in, 120, 36);
        assertTrue(out.length() <= 36, out);
        assertTrue(ChatLayout.px(out) <= 120 + 2, "px=" + ChatLayout.px(out));
        assertTrue(out.endsWith("…"));
    }

    @Test
    void neverEndsInTheMiddleOfAColourCode() {
        String in = "§a§l§n§o§c§d§e§f§b§9§8§7§6§5§4 tekst tekst tekst tekst tekst tekst";
        String out = HudScale.fit(in, 200, 20);
        String body = out.substring(0, out.length() - 1);
        assertFalse(body.endsWith("§"), out);
        assertTrue(out.length() <= 20);
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", HudScale.fit(null, 100, 30));
    }
}
