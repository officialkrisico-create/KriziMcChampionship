package nl.kmc.kmccore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.kmccore.models.KMCGame;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Builds the chat prompt that opens the vote GUI.
 * (The old per-option buttons are gone — too many games to fit in chat.)
 */
public final class ClickableVoteMessage {

    private ClickableVoteMessage() {}

    /**
     * Sends a clickable chat banner that opens the vote GUI when clicked.
     */
    public static void sendGuiPrompt(int totalOptions, int seconds) {
        String line = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        Component msg = Component.text()
                .append(Component.text(line + "\n", NamedTextColor.GOLD))
                .append(Component.text("  🗳  ", NamedTextColor.WHITE))
                .append(Component.text("Stem op de volgende game!\n",
                        NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(line + "\n", NamedTextColor.GOLD))
                .append(Component.text("  ", NamedTextColor.WHITE))
                .append(Component.text("[ KLIK OM TE STEMMEN ]",
                        NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/kmcvote open"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Klik om het stem-menu te openen",
                                NamedTextColor.YELLOW))))
                .append(Component.text("\n  " + totalOptions + " games beschikbaar  •  "
                                + seconds + " seconden",
                        NamedTextColor.GRAY))
                .append(Component.text("\n" + line, NamedTextColor.GOLD))
                .build();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(Component.empty());
            p.sendMessage(msg);
            p.sendMessage(Component.empty());
        }
    }

    /** Vote result — shown after voting closes. */
    public static void sendResult(KMCGame game, int voteCount) {
        Component msg = Component.text()
                .append(Component.text("✔ ", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("Volgende game: ", NamedTextColor.YELLOW))
                .append(Component.text(game.getDisplayName(),
                        NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" (" + voteCount + " stemmen)", NamedTextColor.GRAY))
                .build();

        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
    }
}
