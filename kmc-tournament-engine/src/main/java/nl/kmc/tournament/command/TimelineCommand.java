package nl.kmc.tournament.command;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.TournamentService;
import nl.kmc.tournament.timeline.TimelineDisplayManager;
import nl.kmc.tournament.timeline.TournamentTimeline;
import nl.kmc.tournament.timeline.TournamentTimeline.GameStatus;
import nl.kmc.tournament.timeline.TournamentTimeline.TimelineEntry;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /kmctimeline — opens the timeline chest GUI for a player. */
public final class TimelineCommand implements CommandExecutor {

    private static final String PREFIX = ChatColor.GOLD + "[KMC] " + ChatColor.RESET;

    private final TournamentService      tournament;
    private final GameRegistryService    registry;
    private final TimelineDisplayManager display;

    public TimelineCommand(TournamentService tournament,
                           GameRegistryService registry,
                           TimelineDisplayManager display) {
        this.tournament = tournament;
        this.registry   = registry;
        this.display    = display;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(PREFIX + "Only players can use this command.");
            return true;
        }

        TournamentTimeline timeline = buildTimeline();
        display.open(p, timeline);
        return true;
    }

    private TournamentTimeline buildTimeline() {
        TournamentTimeline tl = new TournamentTimeline(
                tournament.getCurrentRound(),
                tournament.getTotalRounds(),
                tournament.getPhase());

        List<String> played = registry.getPlayedIds();
        String activeId = registry.getActive().map(GameRegistration::getId).orElse(null);

        for (GameRegistration reg : registry.getAll()) {
            String id = reg.getId();
            GameStatus status;
            if (played.contains(id)) {
                status = GameStatus.COMPLETED;
            } else if (id.equals(activeId)) {
                status = GameStatus.ACTIVE;
            } else {
                status = GameStatus.UPCOMING;
            }
            tl.addEntry(new TimelineEntry(reg, tournament.getCurrentRound(), status, null, 0));
        }

        return tl;
    }
}
