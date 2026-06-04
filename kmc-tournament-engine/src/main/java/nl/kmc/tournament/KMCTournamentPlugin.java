package nl.kmc.tournament;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.tournament.command.TournamentCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Thin compatibility shim for the {@code /kmc} command.
 *
 * <p>The tournament flow, automation, ceremonies, cinematics, voting and
 * repetitions all live in the single V1 engine inside <b>KMCCore</b>
 * ({@code AutomationManager}, driven by {@code /kmcauto}). This plugin no
 * longer contains a parallel engine — it simply forwards {@code /kmc} to that
 * one engine so existing muscle-memory / macros keep working.
 *
 * <p>Everything that used to live here (TournamentEngine, AutomationEngine,
 * VotingEngine, recovery, timeline, templates, reconnect) was removed during
 * the V1/V2 consolidation: it duplicated KMCCore's automation, voting and
 * {@code SnapshotManager}/{@code /event} recovery.
 */
public final class KMCTournamentPlugin extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger(KMCTournamentPlugin.class.getName());

    @Override
    public void onEnable() {
        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            LOG.severe("[KMC/Engine] KMCCore not found — /kmc command unavailable.");
            return;
        }

        TournamentCommand cmd = new TournamentCommand(core);
        var kmc = getCommand("kmc");
        if (kmc != null) {
            kmc.setExecutor(cmd);
            kmc.setTabCompleter(cmd);
        }

        LOG.info("[KMC/Engine] Shim enabled — /kmc forwards to KMCCore automation (/kmcauto).");
    }
}
