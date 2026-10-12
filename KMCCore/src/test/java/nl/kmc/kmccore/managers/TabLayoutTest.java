package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.presentation.ChatLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabLayoutTest {

    @Test
    void boldTeamRowIsAsWideAsPlainRow() {
        String plain = TabListManager.leftRight("§f1. §c◆ §cRode Ratten", "§e0 §6●");
        String bold = TabListManager.leftRight("§f1. §c◆ §c§lRode Ratten", "§e0 §6●");
        // the padding after a bold name is reset, so both rows end up (nearly) the same width
        assertTrue(Math.abs(ChatLayout.px(plain) - ChatLayout.px(bold)) <= 12,
                plain.length() + " vs " + bold.length());
        assertTrue(bold.contains("§r"));
    }

    @Test
    void oneNameIsJustThatName() {
        assertEquals(List.of("§cKrisico"), TabListManager.nameLines(List.of("§cKrisico")));
    }

    @Test
    void twoAndThreeNamesShareOneCentredLine() {
        assertEquals(1, TabListManager.nameLines(List.of("§cAnna", "§cBert")).size());
        assertEquals(1, TabListManager.nameLines(List.of("§cAnna", "§cBert", "§cCleo")).size());
    }

    @Test
    void manyLongNamesWrapAndAreCappedAtTwoLines() {
        List<String> names = List.of("§cAAAAAAAAAAAAAAAA", "§cBBBBBBBBBBBBBBBB", "§cCCCCCCCCCCCCCCCC",
                "§cDDDDDDDDDDDDDDDD", "§cEEEEEEEEEEEEEEEE", "§cFFFFFFFFFFFFFFFF", "§cGGGGGGGGGGGGGGGG", "§cHHHHHHHHHHHHHHHH");
        List<String> lines = TabListManager.nameLines(names);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("+"), lines.get(1));
    }

    @Test
    void noNamesNoLines() {
        assertTrue(TabListManager.nameLines(List.of()).isEmpty());
    }
}
