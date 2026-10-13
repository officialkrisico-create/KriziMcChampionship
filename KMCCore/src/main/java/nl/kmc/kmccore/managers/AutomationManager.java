package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import nl.kmc.kmccore.presentation.ChatLayout;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tournament automation engine.
 *
 * <p>FIX — auto-skip unconfigured games: {@link #enterPreStart(KMCGame)}
 * now checks arena readiness. If the next game has no arena configured,
 * it's skipped and the engine picks another. Prevents teleporting
 * players to null locations or pasting missing schematics.
 */
public class AutomationManager {

    public enum State { IDLE, GAME_ACTIVE, INTERMISSION, READY_CHECK, VOTING, PRE_START, PAUSED }

    private final KMCCore plugin;

    private State      state            = State.IDLE;
    private State      stateBeforePause = State.IDLE;
    private int        gamesThisRound   = 0;
    private int        countdownSeconds = 0;

    /** Regular games finished this tournament (the finale does not count). */
    private int        gamesPlayedTotal = 0;

    // Finale: after the last regular game the top teams play one decisive game.
    private boolean      finalePhase;
    private List<String> finaleTeamIds = List.of();
    private String       finaleWinnerTeamId;

    /** /pause during a game: the automation holds still as soon as that game is over. */
    private boolean    pauseAfterGame;
    /** True while a rerun is tearing the running game down, so its end isn't treated as a result. */
    private boolean    rerunInProgress;

    /** Everything needed to roll the tournament back to the moment just before the last game launched. */
    private record PreGameState(String gameId, String snapshotLabel, String tournamentData,
                                int gamesThisRound, int gamesPlayedTotal, int round,
                                String repGameId, int repetition, int maxRepetitions,
                                boolean goldenActive, double goldenMultiplier,
                                boolean finale, List<String> finaleTeams,
                                int ledgerEntries, int ledgerHighlights, int ledgerGameSeq) {}
    private PreGameState preGame;

    /** Standings just before the running game started — for the rise/fall animation afterwards. */
    private Map<String, Integer> ranksBefore = new HashMap<>();
    private Map<String, Integer> pointsBefore = new HashMap<>();

    private BukkitTask tickTask;
    private BossBar    bossBar;

    /** Games already attempted this cycle (avoid infinite skip loops). */
    private final Set<String> attemptedThisCycle = new HashSet<>();

    // Repetition tracking — how many times the current game plays back-to-back.
    private String currentRepGameId  = null;
    private int    currentRepetition = 0;
    private int    maxRepetitions    = 1;

    // Scheduled auto-start (clock-time start of the whole ceremony flow).
    private BukkitTask scheduledStartTask;
    private long       scheduledStartAtMs;

    public AutomationManager(KMCCore plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Control
    // ----------------------------------------------------------------

    /** Stages of the opening presentation, in order (each is a section of ceremonies.yml). */
    private static final List<String> OPENING_STAGES = List.of(
            "opening", "how-it-works", "tournament-overview", "team-showcase", "game-lineup");

    private static final Map<String, String> STAGE_LABELS = Map.of(
            "opening",             "Opening",
            "how-it-works",        "Spelregels",
            "tournament-overview", "Rondes & punten",
            "team-showcase",       "De teams",
            "game-lineup",         "De games");

    private static final BarColor[] STAGE_COLORS = {
            BarColor.YELLOW, BarColor.BLUE, BarColor.PINK, BarColor.GREEN, BarColor.PURPLE };

    /** Chat accent colour per stage: gold, aqua, light purple, dark aqua, green. */
    private static final char[] STAGE_CHAT_COLORS = { '6', 'b', 'd', '3', 'a' };

    // Opening-presentation bookkeeping: every scheduled step carries the generation it was
    // scheduled in, so stop()/skip simply bump the counter and anything still pending is a no-op.
    private int                  ceremonyGeneration;
    private boolean              ceremonyActive;
    private final List<BukkitTask> ceremonyTasks = new ArrayList<>();
    private BukkitTask           stageBarTask;

    // ── Locator bar (the waypoint bar showing other players) is off for the whole event ──

    private final java.util.Map<java.util.UUID, Boolean> previousLocatorBar = new java.util.HashMap<>();

    private static org.bukkit.GameRule<Boolean> locatorBarRule() {
        return org.bukkit.GameRules.LOCATOR_BAR;
    }

    private void disableLocatorBar() {
        var rule = locatorBarRule();
        if (rule == null) return;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            try {
                previousLocatorBar.putIfAbsent(w.getUID(), w.getGameRuleValue(rule));
                w.setGameRule(rule, false);
            } catch (Throwable ignored) { }
        }
    }

    private void restoreLocatorBar() {
        var rule = locatorBarRule();
        if (rule == null) { previousLocatorBar.clear(); return; }
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            Boolean prev = previousLocatorBar.get(w.getUID());
            if (prev == null) continue;
            try { w.setGameRule(rule, prev); } catch (Throwable ignored) { }
        }
        previousLocatorBar.clear();
    }

    public void start() {
        if (state != State.IDLE || ceremonyActive) return;
        disableLocatorBar();
        gamesThisRound = 0;
        gamesPlayedTotal = 0;
        finalePhase = false;
        finaleTeamIds = List.of();
        finaleWinnerTeamId = null;
        pauseAfterGame = false;
        preGame = null;
        plugin.getTournamentManager().setChampion(null, null);
        attemptedThisCycle.clear();
        createBossBar();
        // Opening presentation: opening → how it works → rounds & points → teams → games.
        // Each stage shows its title, reveals its chat lines one at a time (with a soft chime),
        // keeps a progress bar, and always leaves a read-pause before the next stage.
        ceremonyActive = true;
        plugin.getChatGate().mute("ceremony:opening");
        final int gen = ++ceremonyGeneration;
        runCeremonyStage(gen, 0, () -> {
            ceremonyActive = false;
            plugin.getChatGate().unmute("ceremony:opening");
            cancelStageBar();
            enterIntermission();
        });
    }

    /** True while the opening presentation is still playing. */
    public boolean isCeremonyActive() { return ceremonyActive; }

    /** Skips the rest of the opening presentation and goes straight to the first intermission. */
    public boolean skipCeremony() {
        if (!ceremonyActive) return false;
        cancelCeremony();
        for (Player p : Bukkit.getOnlinePlayers()) p.clearTitle();
        broadcast("&6[KMC] &7Presentatie overgeslagen door een admin.");
        enterIntermission();
        return true;
    }

    private nl.kmc.kmccore.presentation.TeamShowcase teamShowcase;

    private void cancelCeremony() {
        if (teamShowcase != null) { teamShowcase.cancel(); teamShowcase = null; }
        try { plugin.getCeremonyFireworks().cancelAll(); } catch (Throwable ignored) { }
        boolean wasActive = ceremonyActive;
        ceremonyGeneration++;
        ceremonyActive = false;
        plugin.getChatGate().unmute("ceremony:opening");
        ceremonyTasks.forEach(BukkitTask::cancel);
        ceremonyTasks.clear();
        cancelStageBar();
        if (wasActive) {
            var cm = plugin.getCinematicManager();
            if (cm != null) cm.stopAll();
        }
    }

    private void ceremonyLater(int gen, long delayTicks, Runnable r) {
        ceremonyTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gen == ceremonyGeneration) r.run();
        }, Math.max(0L, delayTicks)));
    }

    /**
     * Runs one stage of the opening presentation: plays its camera route (if any), shows its
     * title, reveals its lines spaced out, then waits at least the configured duration AND a
     * read buffer after the last line before moving to the next stage.
     */
    private void runCeremonyStage(int gen, int idx, Runnable done) {
        if (gen != ceremonyGeneration) return;
        if (idx >= OPENING_STAGES.size()) {
            try { plugin.getCeremonyFireworks().play(nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.FINALE); }
            catch (Throwable ignored) { }
            done.run();
            return;
        }

        String phase = OPENING_STAGES.get(idx);
        ceremonyTasks.clear();
        Runnable next = () -> runCeremonyStage(gen, idx + 1, done);

        playCinematic(phase, () -> {
            if (gen != ceremonyGeneration) return;
            var cm = plugin.getCeremonyManager();
            if (cm == null) { next.run(); return; }

            Map<String, String> ph = basePlaceholders();
            String titleText = cm.getTitle(phase, ph);
            String subText   = cm.getSubtitle(phase, ph);
            showCeremonyTitle(titleText, subText);
            playStageSound(idx);
            stageBurst(idx);
            fireworksForStage(phase);

            // Chat script for this stage: a framed header, then one block per message (laid out
            // with centring / wrapping), then stage-specific content, then a closing rule.
            char color = STAGE_CHAT_COLORS[idx % STAGE_CHAT_COLORS.length];
            List<List<String>> blocks = new ArrayList<>();
            boolean interactiveShowcase = false;
            blocks.add(ChatLayout.header(color, ChatColor.stripColor(titleText), ChatColor.stripColor(subText)));
            for (String message : cm.getMessages(phase, ph)) {
                if (isSeparatorLine(message)) continue;   // the header/footer frame replaces old divider lines
                if (message.isBlank()) {                   // a blank line is spacing, not a new beat
                    if (blocks.size() > 1) blocks.get(blocks.size() - 1).add("");
                    continue;
                }
                // The "not all games" note must say what is actually true for this tournament.
                if (phase.equals("game-lineup")
                        && ChatColor.stripColor(MessageUtil.color(message)).toLowerCase().contains("niet alle games"))
                    message = "<c>" + lineupNote();
                blocks.add(new ArrayList<>(ChatLayout.body(message)));
            }
            switch (phase) {
                case "tournament-overview" -> buildMultiplierLadderBlock().ifPresent(blocks::add);
                case "team-showcase"       -> {
                    if (plugin.getTeamManager().getTeamsSortedByPoints().isEmpty())
                        blocks.addAll(buildTeamShowcaseBlocks());
                    else interactiveShowcase = true;   // staged per team below
                }
                case "game-lineup"         -> {
                    // Always warn, even if an older ceremonies.yml on disk lacks the disclaimer line.
                    boolean has = cm.getMessages(phase, ph).stream()
                            .anyMatch(m -> ChatColor.stripColor(MessageUtil.color(m)).toLowerCase().contains("niet alle games"));
                    if (!has) blocks.add(new ArrayList<>(ChatLayout.body("<c>" + lineupNote())));
                }
                default -> { }
            }

            boolean isLineup = phase.equals("game-lineup");
            if (!isLineup && !interactiveShowcase) blocks.add(List.of(ChatLayout.rule(color), ""));
            long langHold = phase.equals("opening") ? announceLanguage(gen, 50L) : 0L;
            long end = revealBlocks(gen, blocks, langHold, cm);
            if (interactiveShowcase) {
                var teams = plugin.getTeamManager().getTeamsSortedByPoints();
                teamShowcase = new nl.kmc.kmccore.presentation.TeamShowcase(plugin);
                end = teamShowcase.schedule(teams, buildTeamShowcaseBlocks(), end,
                        (delay, task) -> ceremonyLater(gen, delay, task));
                ceremonyLater(gen, end, () -> {
                    Bukkit.broadcastMessage(ChatLayout.rule(color));
                    Bukkit.broadcastMessage("");
                });
            }
            if (isLineup) end = Math.max(end, scheduleGameLineup(gen, cm, end));

            long total = Math.max(cm.getDuration(phase, 8) * 20L, end + cm.getReadBufferTicks());
            startStageBar(gen, idx, phase, total);
            ceremonyLater(gen, total, next);
        });
    }

    /** One sentence for the game line-up: how many of the enabled games are really going to be played. */
    private String lineupNote() {
        int total = getTotalGames();
        int count = Math.max(1, plugin.getGameManager().getEnabledGames().size());
        int perRound = gamesPerRound();
        int rounds = Math.max(1, plugin.getTournamentManager().getTotalRounds());
        if (total >= count)
            return "&a✔ &7Alle &f" + count + " &7games komen aan bod: &f" + perRound + " &7per ronde, &f" + rounds + " &7rondes.";
        return "&e⚠ &7Er worden &f" + total + " &7van de &f" + count + " &7games gespeeld (&f" + perRound
                + " &7per ronde): jullie stemmen steeds mee.";
    }

    /** True for the old "&8§m      " divider lines in ceremonies.yml — the stage frame draws its own. */
    private static boolean isSeparatorLine(String message) {
        return message != null && message.contains("§m") && ChatColor.stripColor(message).isBlank();
    }

    /**
     * Reveals each block at once (with one chime) starting at {@code startTick}. How long a block
     * stays alone on screen scales with how much there is to read. Returns the tick at which the
     * last block has been on screen long enough to read.
     */
    private long revealBlocks(int gen, List<List<String>> blocks, long startTick, CeremonyManager cm) {
        long tick = startTick;
        for (List<String> block : blocks) {
            ceremonyLater(gen, tick, () -> {
                block.forEach(Bukkit::broadcastMessage);
                if (block.stream().anyMatch(l -> !l.isBlank() && !l.contains("§m"))) chime();
            });
            int chars = block.stream().mapToInt(ChatLayout::visibleLength).sum();
            tick += Math.min(cm.getMaxBlockTicks(),
                    Math.max(cm.getLineDelayTicks(), Math.round(chars * cm.getTicksPerChar())));
        }
        return tick;
    }

    /** Spotlights each enabled game in turn as a framed card, plus a title and a particle burst. */
    private long scheduleGameLineup(int gen, CeremonyManager cm, long startTick) {
        List<KMCGame> games = plugin.getGameManager().getEnabledGames();
        long tick = startTick;
        for (int i = 0; i < games.size(); i++) {
            KMCGame g = games.get(i);
            boolean secret = plugin.getGameManager().isSurpriseHidden(g.getId());
            String objective   = secret ? "" : lookupObjective(g.getId());
            String description = secret ? "Een verborgen game — jullie horen pas wat het is als hij gekozen wordt!"
                    : lookupDescription(g.getId());
            List<String> scoring = secret ? List.of() : lookupScoring(g.getId());
            int n = i + 1, total = games.size();

            String shownName = secret ? "??? Verrassingsgame" : g.getDisplayName();
            List<String> card = ChatLayout.gameCard(n, total, shownName, description, objective, scoring);
            int chars = card.stream().mapToInt(ChatLayout::visibleLength).sum();
            // Each card stays up long enough to actually read: at least seconds-per-game, more for wordy ones.
            long hold = Math.min(cm.getMaxSecondsPerGame() * 20L,
                    Math.max(cm.getSecondsPerGame() * 20L, Math.round(chars * cm.getTicksPerChar())));

            String sub = !objective.isBlank() ? objective : description;
            float pitch = 0.8f + 0.7f * n / total;
            long holdFinal = hold;
            ceremonyLater(gen, tick, () -> {
                card.forEach(Bukkit::broadcastMessage);
                try { plugin.getCeremonyFireworks().play(nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.GAME_CARD); }
                catch (Throwable ignored) { }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(MessageUtil.color("&e&l" + shownName), MessageUtil.color("&7" + shorten(sub, 60)),
                            5, (int) Math.max(20, holdFinal - 15), 10);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, pitch);
                }
                stageBurst(2);
            });
            tick += hold;
        }
        return tick;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }

    private void chime() {
        for (Player p : Bukkit.getOnlinePlayers())
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 1.5f);
    }

    private void playStageSound(int idx) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (idx == 0) {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1f);
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1f);
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 0.8f + 0.1f * idx);
            }
        }
    }

    /** Boss bar shows which stage of the presentation this is and drains over the stage's length. */
    private void startStageBar(int gen, int idx, String phase, long totalTicks) {
        cancelStageBar();
        setBossBar("&6&lKMC &8| &e" + STAGE_LABELS.getOrDefault(phase, phase)
                        + " &8(&7" + (idx + 1) + "/" + OPENING_STAGES.size() + "&8)",
                STAGE_COLORS[idx % STAGE_COLORS.length], 1.0);
        final long[] elapsed = {0};
        stageBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (gen != ceremonyGeneration || bossBar == null) { cancelStageBar(); return; }
            elapsed[0] += 10;
            setBossBarProgress(1.0 - (double) elapsed[0] / Math.max(1, totalTicks));
        }, 10L, 10L);
    }

    private void cancelStageBar() {
        if (stageBarTask != null) { stageBarTask.cancel(); stageBarTask = null; }
    }

    /** Multiplier per round, from tournament.multipliers — a centred "ladder", four rounds per row. */
    private java.util.Optional<List<String>> buildMultiplierLadderBlock() {
        var sec = plugin.getConfig().getConfigurationSection("tournament.multipliers");
        if (sec == null) return java.util.Optional.empty();
        int rounds = plugin.getTournamentManager().getTotalRounds();
        List<String> parts = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            String key = String.valueOf(r);
            if (!sec.isSet(key)) continue;
            double m = sec.getDouble(key);
            String shown = m == Math.rint(m) ? String.valueOf((int) m) : String.valueOf(m);
            parts.add("§8[§7RONDE §f" + r + "§8] §e§l" + shown + "x");
        }
        if (parts.isEmpty()) return java.util.Optional.empty();
        List<String> out = new ArrayList<>();
        out.add(ChatLayout.center("§d§lMULTIPLIER PER RONDE"));
        out.add("");
        for (int i = 0; i < parts.size(); i += 2) {
            out.add(ChatLayout.center(String.join("   §8|   ", parts.subList(i, Math.min(parts.size(), i + 2)))));
        }
        return java.util.Optional.of(out);
    }

    /** "How you score" lines of a game (from its registration), or an empty list. */
    private List<String> lookupScoring(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            var reg = v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class).get(gameId);
            if (reg.isPresent()) return reg.get().getScoringLines();
        }
        return List.of();
    }

    /** Particle burst around every player — opening gets the big totem-style one. */
    /** Fires the admin-configured fireworks for a ceremony stage (see /kmcopeningsceremony). */
    private void fireworksForStage(String phase) {
        var moment = switch (phase) {
            case "opening"             -> nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.OPENING;
            case "how-it-works"        -> nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.HOW_IT_WORKS;
            case "tournament-overview" -> nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.OVERVIEW;
            case "team-showcase"       -> nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.TEAM_SHOWCASE;
            case "game-lineup"         -> nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.GAME_LINEUP;
            default -> null;
        };
        if (moment == null) return;
        try { plugin.getCeremonyFireworks().play(moment); } catch (Throwable ignored) { }
    }

    /**
     * Makes the language choice impossible to miss at the very start: a big three-language title, a clickable
     * chat card, and the language menu opens by itself for everyone who has not picked a language yet.
     * Returns how many ticks the rest of the opening should be held back.
     */
    private long announceLanguage(int gen, long startTick) {
        final String cmd = "/kmclanguage";
        ceremonyLater(gen, startTick, () -> {
            var lm = plugin.getLanguageManager();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§e§l🌍 " + cmd, "§fKies je taal §8• §fChoose your language", 5, 110, 15);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
                String rule = "§6§m" + " ".repeat(73);
                p.sendMessage("");
                p.sendMessage(rule);
                p.sendMessage(nl.kmc.kmccore.presentation.ChatLayout.center("§e§l🌍 TAAL §8• §e§lLANGUAGE"));
                p.sendMessage(nl.kmc.kmccore.presentation.ChatLayout.center("§7Kies je taal §8• §7Choose your language"));
                p.sendMessage(languageLine("  &f&lNL &8» &7Klik hier of typ &e&l" + cmd));
                p.sendMessage(languageLine("  &f&lEN &8» &7Click here or type &e&l" + cmd));
                p.sendMessage(rule);
                p.sendMessage("");
            }
        });
        // The menu opens for everyone who never picked a language (they can close it at any time).
        ceremonyLater(gen, startTick + 25L, () -> {
            var lm = plugin.getLanguageManager();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (lm.hasChosen(p.getUniqueId())) continue;
                try { new nl.kmc.kmccore.gui.LanguageGui(plugin, p).open(p); } catch (Throwable ignored) { }
            }
        });
        return startTick + 150L;
    }

    /** A clickable chat line that runs /kmclanguage. */
    private static net.kyori.adventure.text.Component languageLine(String legacy) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(MessageUtil.color(legacy))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/kmclanguage"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text("Klik / Click / Tıkla")));
    }

    private void stageBurst(int idx) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            var at = p.getLocation().add(0, 1, 0);
            p.spawnParticle(Particle.FIREWORK, at, 40, 1.5, 1.0, 1.5, 0.05);
            if (idx == 0) p.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 60, 1.2, 1.0, 1.2, 0.4);
        }
    }

    /** Broadcasts each line spaced out (used by the shorter mid-tournament ceremonies). */
    private long broadcastSpaced(List<String> lines) {
        var cm = plugin.getCeremonyManager();
        long delay = cm != null ? cm.getLineDelayTicks() : 50L;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Bukkit.broadcastMessage(line);
                if (!line.contains("§m")) chime();
            }, i * delay);
        }
        return (long) Math.max(0, lines.size() - 1) * delay;
    }

    public void stop() {
        plugin.getChatGate().clear();
        cancelCeremony();
        restoreLocatorBar();
        cancelTick();
        hideBossBar();
        try { plugin.getReadyUpManager().shutdown(); } catch (Throwable ignored) { }
        state = State.IDLE;
        finalePhase = false;
        pauseAfterGame = false;
        // A game that is still running must not be left behind as a ghost (it would keep its scoreboard,
        // timers and listeners and could kick players out of the next game later).
        try {
            if (plugin.getGameManager().getActiveGame() != null) plugin.getGameManager().forceSkipCurrentGame();
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Could not stop the running game on /kmcauto stop: " + t);
        }
    }

    // ----------------------------------------------------------------
    // Scheduled auto-start (the whole ceremony flow at a clock time)
    // ----------------------------------------------------------------

    /**
     * Schedules the full tournament (ceremonies + automation) to begin after
     * {@code delayTicks}. Replaces any existing schedule. Broadcasts reminders
     * at 5 min / 1 min / 10 s before start.
     */
    public void scheduleStart(long delayTicks) {
        cancelScheduledStart();
        if (delayTicks <= 0) { fireScheduledStart(); return; }
        scheduledStartAtMs = System.currentTimeMillis() + delayTicks * 50L;

        // Reminder broadcasts before the start.
        long[] remindersBeforeTicks = { 6000L, 1200L, 200L }; // 5 min, 1 min, 10 s
        for (long before : remindersBeforeTicks) {
            long at = delayTicks - before;
            if (at <= 0) continue;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                long secsLeft = Math.max(1, (scheduledStartAtMs - System.currentTimeMillis()) / 1000);
                broadcast("&6[KMC] &eToernooi start over &6" + formatDuration(secsLeft) + "&e!");
            }, at);
        }

        scheduledStartTask = Bukkit.getScheduler().runTaskLater(plugin, this::fireScheduledStart, delayTicks);
    }

    private void fireScheduledStart() {
        scheduledStartTask = null;
        scheduledStartAtMs = 0;
        if (state != State.IDLE) return; // already running
        if (!plugin.getTournamentManager().isActive()) plugin.getTournamentManager().start();
        start();
    }

    public void cancelScheduledStart() {
        if (scheduledStartTask != null) { scheduledStartTask.cancel(); scheduledStartTask = null; }
        scheduledStartAtMs = 0;
    }

    public boolean hasScheduledStart()    { return scheduledStartTask != null; }
    public long    scheduledStartInMs()   { return scheduledStartTask != null ? Math.max(0, scheduledStartAtMs - System.currentTimeMillis()) : -1; }

    private static String formatDuration(long seconds) {
        if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

    private long lastGameEndMs = 0;

    public void onGameEnd(String winnerName) {
        if (rerunInProgress) return;   // the game was torn down for a rerun — not a result
        if (state != State.GAME_ACTIVE && state != State.PAUSED) {
            if (state == State.IDLE) return;
        }
        // De-dupe: a game-end can be signalled from several paths in the same
        // moment (result-event bridge, stopGame, health monitor). Only handle
        // the first within a short window so the end ceremony/leaderboards
        // don't get broadcast twice.
        long now = System.currentTimeMillis();
        if (now - lastGameEndMs < 3000) return;
        lastGameEndMs = now;
        // Winner ceremony cinematic for the game that just finished, then continue.
        // (Active game is already cleared by stopGame, so use the tracked rep id.)
        String winnerRoute = currentRepGameId != null ? "winner-" + currentRepGameId : "winner";
        playCinematic(winnerRoute, () -> continueGameEnd(winnerName));
    }

    /** Runs the post-game flow after the winner cinematic finishes. */
    private void continueGameEnd(String winnerName) {
        ceremony("game-end");

        // ── Repetitions: replay the same game until maxRepetitions reached ──
        if (currentRepGameId != null && currentRepetition < maxRepetitions) {
            currentRepetition++;
            KMCGame same = plugin.getGameManager().getGame(currentRepGameId);
            int done = currentRepetition - 1;
            broadcast("&6[KMC] &e" + (same != null ? same.getDisplayName() : currentRepGameId)
                    + " &7— spel &e" + done + "&7/&e" + maxRepetitions
                    + " &7klaar. Volgende start zo...");
            plugin.getArenaManager().teleportAllToLobby();

            int interSec = plugin.getConfig().getInt("automation.repetition-intermission-seconds", 20);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (same != null) enterPreStart(same);   // re-runs intro + arena + countdown + launch
                else proceedAfterGame(winnerName);
            }, interSec * 20L);
            return;
        }

        // All repetitions done — clear tracking and advance the rotation.
        currentRepGameId  = null;
        currentRepetition = 0;
        maxRepetitions    = 1;
        proceedAfterGame(winnerName);
    }

    /** Advances the round counter and moves to the next game / round / finale. */
    private void proceedAfterGame(String winnerName) {
        // The finale was the last game: whoever won it is the champion.
        if (finalePhase) {
            finishFinale(winnerName);
            return;
        }

        gamesThisRound++;
        gamesPlayedTotal++;
        attemptedThisCycle.clear();

        int gamesPerRound = gamesPerRound();
        int totalGames = getTotalGames();
        boolean lastGame = totalGames > 0 && gamesPlayedTotal >= totalGames;
        boolean roundComplete = false;
        boolean midBreakNow = false;
        if (gamesThisRound >= gamesPerRound || lastGame) {
            gamesThisRound = 0;
            roundComplete  = true;
            int completedRound = plugin.getTournamentManager().getCurrentRound();
            // The number of games decides when the regular part is over. A schedule with more games than
            // rounds simply stays on the last round (and its multiplier) — nextRound() just refuses.
            boolean regularDone = lastGame;
            if (!regularDone) plugin.getTournamentManager().nextRound();
            if (regularDone) {
                ceremony("round-end");
                if (shouldPlayFinale()) startFinale();
                else endTournament(winnerName);
                return;
            }
            // Mid-tournament break (default: after half of the rounds, 5 minutes, skippable).
            var cfg = plugin.getConfig();
            int total = plugin.getTournamentManager().getTotalRounds();
            int breakAfter = cfg.getInt("automation.mid-break.after-round", Math.max(1, total / 2));
            midBreakNow = cfg.getBoolean("automation.mid-break.enabled", true)
                    && total > 1 && completedRound == breakAfter;
        }

        plugin.getArenaManager().teleportAllToLobby();
        // The round-end words, the half-way notice and the intermission (with its timer) all wait until the
        // score report and the leaderboard animation have been shown, so they do not talk over them.
        final boolean roundDoneNow = roundComplete, midBreak = midBreakNow;
        postGameLeaderboardChain(() -> {
            if (state == State.PAUSED) { stateBeforePause = State.INTERMISSION; return; }
            if (state != State.GAME_ACTIVE) return;          // stopped or skipped in the meantime
            if (roundDoneNow) ceremony("round-end");
            createBossBar();
            if (pauseAfterGame) {
                // /pause was used during the game: hold still here until /resume.
                pauseAfterGame = false;
                state = State.INTERMISSION;
                pause();
                return;
            }
            if (midBreak) {
                int secs = plugin.getConfig().getInt("automation.mid-break.seconds", 300);
                broadcast("&6&l[KMC] &eHalverwege het toernooi! &7Pauze van &f" + formatDuration(secs)
                        + "&7 — een admin kan hem overslaan met &e/kmcauto skip&7.");
                enterIntermission(secs, true);
            } else {
                enterIntermission();
            }
        });
    }

    // ----------------------------------------------------------------
    // Total games / finale
    // ----------------------------------------------------------------

    /**
     * Regular games in this tournament: {@code automation.total-games} when set, otherwise
     * rounds × games-per-round (the classic behaviour).
     */
    public int getTotalGames() {
        int cfg = plugin.getConfig().getInt("automation.total-games", 0);
        if (cfg > 0) return cfg;
        return Math.max(1, plugin.getTournamentManager().getTotalRounds()) * gamesPerRound();
    }

    public int getGamesPlayedTotal() { return gamesPlayedTotal; }
    public boolean isFinalePhase()   { return finalePhase; }
    public List<String> getFinaleTeamIds() { return List.copyOf(finaleTeamIds); }

    /** The finale game calls this with the winning team just before it reports its result. */
    public void reportFinaleWinner(String teamId) { this.finaleWinnerTeamId = teamId; }

    private boolean shouldPlayFinale() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("automation.finale.enabled", true)) return false;
        String gameId = cfg.getString("automation.finale.game", "dodgebolt");
        KMCGame game = plugin.getGameManager().getGame(gameId);
        if (game == null) {
            plugin.getLogger().warning("[KMC] Finale staat aan maar game '" + gameId + "' bestaat niet — geen finale.");
            return false;
        }
        if (plugin.getTeamManager().getTeamsSortedByPoints().size() < 2) return false;
        if (!isGameReadyUnified(gameId)) {
            broadcast("&e[KMC] &7De finale-game &e" + game.getDisplayName() + " &7is niet geconfigureerd ("
                    + readinessReasonUnified(gameId) + ") — het toernooi eindigt op punten.");
            return false;
        }
        return true;
    }

    /** The top two teams meet in the finale game; everybody else watches. */
    private void startFinale() {
        var standings = plugin.getTeamManager().getTeamsSortedByPoints();
        List<KMCTeam> finalists = new ArrayList<>(standings.subList(0, Math.min(2, standings.size())));
        finaleTeamIds = finalists.stream().map(KMCTeam::getId).toList();
        finaleWinnerTeamId = null;
        finalePhase = true;

        plugin.getArenaManager().teleportAllToLobby();
        postGameLeaderboardChain();
        createBossBar();

        int intro = Math.max(10, plugin.getConfig().getInt("automation.finale.intro-seconds", 30));
        // The leaderboard chain takes ~22 s; the finale announcement follows it.
        Bukkit.getScheduler().runTaskLater(plugin, this::announceFinale, 450L);
        enterIntermission(intro + 22, true);
    }

    private void announceFinale() {
        if (!finalePhase || state == State.IDLE) return;
        List<KMCTeam> teams = new ArrayList<>();
        for (String id : finaleTeamIds) {
            KMCTeam t = plugin.getTeamManager().getTeam(id);
            if (t != null) teams.add(t);
        }
        if (teams.size() < 2) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            var lm = plugin.getLanguageManager();
            String a = teams.get(0).getColor() + teams.get(0).getDisplayName();
            String b = teams.get(1).getColor() + teams.get(1).getDisplayName();
            p.sendMessage("");
            p.sendMessage(ChatLayout.rule('6'));
            p.sendMessage(ChatLayout.center(lm.tr(p, "director.finale.header")));
            p.sendMessage(ChatLayout.center(lm.tr(p, "director.finale.versus", a, b)));
            p.sendMessage(ChatLayout.center(lm.tr(p, "director.finale.points", teams.get(0).getPoints(), teams.get(1).getPoints())));
            p.sendMessage(ChatLayout.rule('6'));
            p.sendMessage("");
            p.sendTitle(lm.tr(p, "director.finale.title"), a + " §7vs " + b, 10, 80, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
        }
        try { plugin.getCeremonyFireworks().play(nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.FINALE); }
        catch (Throwable ignored) { }
        try {
            plugin.getPresenter().say("presenter.finale", 10,
                    teams.get(0).getColor() + teams.get(0).getDisplayName() + "§f",
                    teams.get(1).getColor() + teams.get(1).getDisplayName() + "§f");
        } catch (Throwable ignored) { }
    }

    /** The finale is over: set the champion, then run the closing ceremony. */
    private void finishFinale(String winnerName) {
        var tm = plugin.getTournamentManager();
        String champId = finaleWinnerTeamId;
        if (champId == null || !finaleTeamIds.contains(champId)) {
            // The game did not report a winner: the finalist with the most points takes it.
            champId = finaleTeamIds.stream()
                    .map(id -> plugin.getTeamManager().getTeam(id)).filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.comparingInt(KMCTeam::getPoints))
                    .map(KMCTeam::getId).orElse(null);
        }
        String runnerUp = null;
        for (String id : finaleTeamIds) if (!id.equals(champId)) runnerUp = id;
        if (champId != null) {
            tm.setChampion(champId, runnerUp);
            int bonus = plugin.getConfig().getInt("automation.finale.winner-bonus", 0);
            if (bonus > 0) plugin.getPointsManager().addTeamPoints(champId, bonus);
        }
        finalePhase = false;
        finaleTeamIds = List.of();
        endTournament(winnerName);
    }

    /** Games per round: the configured value, or enabled games / total rounds when it is 0. */
    private int gamesPerRound() {
        int cfg = plugin.getConfig().getInt("automation.games-per-round", 2);
        if (cfg > 0) return cfg;
        int total = Math.max(1, plugin.getTournamentManager().getTotalRounds());
        int games = Math.max(1, plugin.getGameManager().getEnabledGames().size());
        return Math.max(1, (int) Math.ceil(games / (double) total));
    }

    private boolean midBreakActive;

    // ----------------------------------------------------------------
    // Rerun (rollback to just before the last game + play it again)
    // ----------------------------------------------------------------

    /** Remembers the whole tournament state just before a game launches, so it can be replayed safely. */
    private void capturePreGame(KMCGame game) {
        try {
            ranksBefore = nl.kmc.kmccore.presentation.LeaderboardAnimation.currentRanks(plugin);
            pointsBefore = nl.kmc.kmccore.presentation.LeaderboardAnimation.currentPoints(plugin);
        } catch (Throwable ignored) { }
        try {
            var pm = plugin.getPointsManager();
            String label = "auto-pre-" + game.getId() + "-" + (gamesPlayedTotal + 1)
                    + (finalePhase ? "-finale" : "");
            plugin.getSnapshotManager().snapshot(label);
            preGame = new PreGameState(game.getId(), label,
                    plugin.getTournamentDataStore().exportState(),
                    gamesThisRound, gamesPlayedTotal, plugin.getTournamentManager().getCurrentRound(),
                    currentRepGameId, currentRepetition, maxRepetitions,
                    pm.isGoldenHourActive(), pm.getCurrentMultiplier(),
                    finalePhase, List.copyOf(finaleTeamIds),
                    plugin.getPointsLedger().size(), plugin.getPointsLedger().highlightCount(),
                    plugin.getPointsLedger().lastGameSeq());
        } catch (Throwable t) {
            preGame = null;
            plugin.getLogger().warning("[KMC] Could not capture the pre-game state (rerun unavailable): " + t);
        }
    }

    /** The game a rerun would replay, or {@code null} when none is available. */
    public String getRerunnableGameId() { return preGame != null ? preGame.gameId() : null; }

    /**
     * Rolls points, rounds and counters back to just before the last launched game and plays that game
     * again. A running game is stopped without a result; a finished one is simply undone. Because the
     * state is restored (not patched), nothing is rewarded twice.
     *
     * @return null on success, otherwise a Dutch error message
     */
    public String rerunLastGame() {
        if (state == State.IDLE) return "De automatisering draait niet.";
        if (preGame == null) return "Er is nog geen game gestart — niets om opnieuw te spelen.";
        KMCGame game = plugin.getGameManager().getGame(preGame.gameId());
        if (game == null) return "Game " + preGame.gameId() + " bestaat niet meer.";
        var snap = plugin.getSnapshotManager().findSnapshot(preGame.snapshotLabel());
        if (snap == null) return "De snapshot van voor de game is niet meer beschikbaar.";

        PreGameState pg = preGame;
        rerunInProgress = true;
        try {
            cancelCeremony();
            cancelTick();
            plugin.getReadyUpManager().shutdown();
            pauseAfterGame = false;
            if (plugin.getGameManager().getActiveGame() != null) plugin.getGameManager().forceSkipCurrentGame();
            else {
                plugin.getArenaManager().resetArenaForGame(pg.gameId());
                plugin.getArenaManager().teleportAllToLobby();
            }

            plugin.getSnapshotManager().restore(pg.snapshotLabel());
            plugin.getTournamentDataStore().importState(pg.tournamentData());
            plugin.getPointsLedger().truncate(pg.ledgerEntries(), pg.ledgerHighlights(), pg.ledgerGameSeq());
            plugin.getTournamentManager().setRound(pg.round());
            plugin.getPointsManager().deactivateGoldenHour();
            if (pg.goldenActive()) plugin.getPointsManager().activateGoldenHour(pg.goldenMultiplier());

            gamesThisRound   = pg.gamesThisRound();
            gamesPlayedTotal = pg.gamesPlayedTotal();
            currentRepGameId  = pg.repGameId();
            currentRepetition = pg.repetition();
            maxRepetitions    = pg.maxRepetitions();
            finalePhase       = pg.finale();
            finaleTeamIds     = pg.finaleTeams();
            finaleWinnerTeamId = null;
            plugin.getTournamentManager().setChampion(null, null);
            attemptedThisCycle.clear();
        } finally {
            rerunInProgress = false;
        }

        broadcast("&6&l[KMC] &e" + game.getDisplayName() + " &7wordt opnieuw gespeeld — punten van deze game zijn teruggedraaid.");
        createBossBar();
        enterPreStart(game);
        return null;
    }

    /** Ends the mid-tournament break early (/kmcauto skip). */
    public boolean skipMidBreak() {
        if (!midBreakActive || state != State.INTERMISSION) return false;
        countdownSeconds = 1;   // the next tick moves on to voting / the next game
        broadcast("&6[KMC] &7Pauze overgeslagen door een admin.");
        return true;
    }

    /**
     * Pauses the tournament. Between games it holds the clocks at once; during a game it is a request:
     * the automation pauses as soon as that game is over (a running game can't be frozen safely).
     *
     * @return {@code true} if paused now, {@code false} if it was queued for after the game
     */
    public boolean requestPause() {
        if (state == State.GAME_ACTIVE) {
            pauseAfterGame = true;
            broadcast("&6[KMC] &ePauze aangevraagd — het toernooi pauzeert na deze game.");
            return false;
        }
        pause();
        return true;
    }

    public boolean isPauseQueued() { return pauseAfterGame; }

    public void cancelQueuedPause() { pauseAfterGame = false; }

    public void pause() {
        if (state == State.PAUSED || state == State.IDLE) return;
        if (state == State.GAME_ACTIVE) { requestPause(); return; }
        stateBeforePause = state;
        if (state == State.READY_CHECK) plugin.getReadyUpManager().shutdown();
        state = State.PAUSED;
        cancelTick();
        hideBossBar();
        broadcast("&6[KMC] &eHet toernooi is gepauzeerd door een admin. &7(hervatten: &e/resume&7)");
    }

    public void resume() {
        pauseAfterGame = false;
        if (state != State.PAUSED) return;
        state = stateBeforePause;
        createBossBar();
        switch (state) {
            case INTERMISSION -> enterIntermission();
            case READY_CHECK  -> beginReadyStep();
            case VOTING       -> enterVoting();
            case PRE_START    -> enterPreStart(finalePhase
                    ? plugin.getGameManager().getGame(plugin.getConfig().getString("automation.finale.game", "dodgebolt"))
                    : plugin.getGameManager().getNextGame());
            default           -> {}
        }
        broadcast("&6[KMC] &aHet toernooi gaat verder.");
    }

    // ----------------------------------------------------------------
    // State machine
    // ----------------------------------------------------------------

    private void enterIntermission() {
        enterIntermission(plugin.getConfig().getInt("automation.intermission-seconds", 30), false);
    }

    private void enterIntermission(int totalSeconds, boolean longBreak) {
        state            = State.INTERMISSION;
        midBreakActive   = longBreak;
        countdownSeconds = totalSeconds;
        final int intermissionTotal = Math.max(1, totalSeconds);

        boolean votingEnabled = plugin.getConfig().getBoolean("games.voting-enabled", true);
        String label = finalePhase ? "De FINALE begint over"
                : votingEnabled
                ? "Volgende game wordt gekozen over"
                : "Volgende game start over";

        setBossBar(label + " " + countdownSeconds + "s", BarColor.YELLOW, 1.0);
        broadcast("&6[KMC] &eTussenpauze! Volgende game start over &6"
                + countdownSeconds + " &eseconden.");

        startTick(() -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds / intermissionTotal;
            setBossBarProgress(progress);
            if (!longBreak || countdownSeconds <= 10) playTickSound(countdownSeconds);
            bossBar.setTitle(MessageUtil.color(longBreak
                    ? (finalePhase ? "&6&l🏆 FINALE begint over: &e" : "&6&lHalverwege-pauze: &e")
                            + formatDuration(countdownSeconds) + " &8(/kmcauto skip)"
                    : "&eTussenpauze: &6" + countdownSeconds + "s"));

            if (countdownSeconds <= 0) {
                cancelTick();
                boolean wasLongBreak = midBreakActive;
                midBreakActive = false;
                afterIntermission(wasLongBreak);
            }
        });
    }

    /** What follows an intermission: an optional team ready-check, then the finale / voting / next game. */
    private void afterIntermission(boolean wasLongBreak) {
        String mode = plugin.getConfig().getString("automation.ready-check.mode", "mid-break").toLowerCase();
        boolean ready = !mode.equals("off")
                && (finalePhase || mode.equals("every-break") || (mode.equals("mid-break") && wasLongBreak));
        if (ready) beginReadyStep();
        else continueAfterReady();
    }

    private void beginReadyStep() {
        state = State.READY_CHECK;
        setBossBar("&a&lTeams: is jullie team ready?", BarColor.GREEN, 1.0);
        int timeout = plugin.getConfig().getInt("automation.ready-check.timeout-seconds", 90);
        var only = finalePhase ? finaleTeamIds : null;
        String purpose = finalePhase ? "director.ready.purpose.finale" : "director.ready.purpose.next";
        plugin.getReadyUpManager().beginReadyCheck(timeout, only, purpose, () -> {
            if (state != State.READY_CHECK) return;      // stopped / paused meanwhile
            continueAfterReady();
        });
    }

    private void continueAfterReady() {
        if (finalePhase) {
            enterPreStart(plugin.getGameManager().getGame(
                    plugin.getConfig().getString("automation.finale.game", "dodgebolt")));
        } else if (plugin.getConfig().getBoolean("games.voting-enabled", true)) {
            enterVoting();
        } else {
            enterPreStart(plugin.getGameManager().randomNextGame());
        }
    }

    /**
     * Skips whatever the automation is waiting on: the ceremony, an intermission/break, the vote or the
     * pre-start countdown, or a ready-check. @return a short Dutch description, or null if nothing to skip.
     */
    public String skipWait() {
        if (skipCeremony()) return "openingspresentatie";
        if (state == State.INTERMISSION) { countdownSeconds = 1; return "pauze"; }
        if (state == State.VOTING)       { countdownSeconds = 1; return "stemronde"; }
        if (state == State.PRE_START)    { countdownSeconds = 1; return "aftelling"; }
        if (skipReadyCheck()) return "ready-check";
        return null;
    }

    /** Ends a running ready-check at once (admin skip). */
    public boolean skipReadyCheck() {
        if (state != State.READY_CHECK || !plugin.getReadyUpManager().isActive()) return false;
        plugin.getReadyUpManager().forceSkip();
        return true;
    }

    private void enterVoting() {
        state            = State.VOTING;
        countdownSeconds = plugin.getConfig().getInt("games.voting-duration", 30);

        setBossBar("Stem voor de volgende game!", BarColor.BLUE, 1.0);
        plugin.getGameManager().startVote();

        startTick(() -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("games.voting-duration", 30);
            setBossBarProgress(progress);
            bossBar.setTitle(MessageUtil.color("&bStemmen sluiten over: &e" + countdownSeconds + "s"));
            playTickSound(countdownSeconds);

            if (countdownSeconds <= 0) {
                cancelTick();
                plugin.getGameManager().endVote();
                KMCGame next = plugin.getGameManager().getNextGame();
                if (next == null) next = plugin.getGameManager().randomNextGame();
                enterPreStart(next);
            }
        });
    }

    /**
     * Pre-start countdown. If the picked game isn't configured (no arena),
     * auto-skip and pick another.
     */
    private void enterPreStart(KMCGame game) {
        if (game == null) {
            broadcast("&c[KMC] Geen game beschikbaar! Automatisering en toernooi gestopt.");
            stop();
            stopTournamentCleanly();
            return;
        }

        // AUTO-SKIP check — game must be ready
        if (!isGameReadyUnified(game.getId())) {
            attemptedThisCycle.add(game.getId());
            String reason = readinessReasonUnified(game.getId());
            boolean secretGame = plugin.getGameManager().isSurpriseHidden(game.getId());
            broadcast("&e[KMC] &7Game &e" + (secretGame ? "???" : game.getDisplayName())
                    + " &7is niet geconfigureerd" + (secretGame ? "" : " (" + reason + ")") + ". Overslaan...");

            // Try another game — avoid already-attempted ones
            KMCGame fallback = pickNextReadyGame();
            if (fallback == null) {
                broadcast("&c[KMC] Geen enkele game is correct geconfigureerd! Automatisering en toernooi gestopt.");
                broadcast("&7Controleer de setup via &e/kmcsetup &7of &e/kmcvalidate &7en probeer opnieuw.");
                stop();
                stopTournamentCleanly();
                return;
            }
            enterPreStart(fallback);  // recurse with a ready game
            return;
        }

        // A surprise game is revealed now, with a little fanfare, before its countdown starts.
        if (plugin.getGameManager().isSurpriseHidden(game.getId())) {
            plugin.getGameManager().revealSurprise(game.getId());
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle("§d§l✦ VERRASSING! ✦", "§f" + game.getDisplayName(), 10, 70, 20);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1f);
            }
            broadcast("&d&l✦ DE VERRASSINGSGAME IS: &f&l" + game.getDisplayName() + " &d&l✦");
            try { plugin.getCeremonyFireworks().play(nl.kmc.kmccore.presentation.CeremonyFireworks.Moment.GAME_CARD); }
            catch (Throwable ignored) { }
        }

        state            = State.PRE_START;
        countdownSeconds = plugin.getConfig().getInt("automation.prestart-seconds", 10);

        setBossBar("&a" + game.getDisplayName() + " start over " + countdownSeconds + "s",
                BarColor.GREEN, 1.0);
        broadcast("&6[KMC] &a" + game.getDisplayName() + " &estart over &6"
                + countdownSeconds + " &eseconden!");
        try {
            plugin.getPresenter().say("presenter.next-game", Math.max(6, countdownSeconds),
                    game.getDisplayName(), lookupObjective(game.getId()));
        } catch (Throwable ignored) { }

        startTick(() -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    plugin.getConfig().getInt("automation.prestart-seconds", 10);
            setBossBarProgress(progress);
            bossBar.setTitle(MessageUtil.color(
                    "&a" + game.getDisplayName() + " &estart over &6" + countdownSeconds + "s"));

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(MessageUtil.color("&a" + game.getDisplayName()),
                            MessageUtil.color("&eStart over &6" + countdownSeconds + "s"),
                            0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f,
                            0.5f + (5 - countdownSeconds) * 0.1f);
                }
            }

            if (countdownSeconds <= 0) {
                cancelTick();
                // Game showcase ceremony text, then game-intro + arena flyover
                // cinematics, then launch. Cinematics/ceremony are no-ops if
                // unconfigured, so the game launches immediately in that case.
                ceremonyGame("game-intro", game);
                playCinematicChain(
                        List.of("game-intro-" + game.getId(), "arena-" + game.getId()),
                        () -> launchGame(game));
            }
        });
    }

    /**
     * Picks the next playable game, excluding ones we've already tried
     * this cycle and ones not configured.
     */
    private KMCGame pickNextReadyGame() {
        for (KMCGame candidate : plugin.getGameManager().getAvailableGames()) {
            if (attemptedThisCycle.contains(candidate.getId())) continue;
            if (isGameReadyUnified(candidate.getId())) {
                return candidate;
            }
            attemptedThisCycle.add(candidate.getId());
        }
        return null;
    }

    /**
     * Unified game-readiness check — the SINGLE source of truth, shared with
     * {@code /kmcsetup} and {@code /kmcvalidate}. Prefers the game's own
     * registered {@link nl.kmc.core.setup.GameSetup} (so games that manage
     * their own arena, like QuakeCraft via {@code /qc}, report correctly) and
     * only falls back to the KMCCore {@code /kmcarena} check for games without
     * a registered setup.
     */
    private boolean isGameReadyUnified(String gameId) {
        var setup = lookupSetup(gameId);
        if (setup != null) {
            try { return setup.isReady(); } catch (Throwable ignored) {}
        }
        return plugin.getArenaManager().isGameReady(gameId);
    }

    private String readinessReasonUnified(String gameId) {
        var setup = lookupSetup(gameId);
        if (setup != null) {
            try {
                var issues = setup.issues();
                return (issues == null || issues.isEmpty()) ? "klaar" : String.join(", ", issues);
            } catch (Throwable ignored) {}
        }
        return plugin.getArenaManager().getReadinessReason(gameId);
    }

    private nl.kmc.core.setup.GameSetup lookupSetup(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            try {
                var svc = v2.getContainer().get(nl.kmc.core.setup.SetupService.class);
                if (svc != null) return svc.get(gameId).orElse(null);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void launchGame(KMCGame game) {
        // Initialise repetition tracking the first time a NEW game launches.
        // Re-launches of the same game (for repetitions) keep the counter.
        if (!game.getId().equals(currentRepGameId)) {
            currentRepGameId  = game.getId();
            currentRepetition = 1;
            maxRepetitions    = Math.max(1, plugin.getConfig().getInt(
                    "games.list." + game.getId() + ".repetitions", 1));
        }

        state = State.GAME_ACTIVE;
        attemptedThisCycle.clear();
        // Reveal Golden Hour (if this is the secretly-picked round) right as
        // the real game launches — the whole ceremony sequence (opening, team
        // showcase, intermission, voting, countdown) has already played by
        // now, so this can't collide with it.
        plugin.getTournamentManager().revealGoldenHourIfDue();
        capturePreGame(game);
        // Hide the automation bossbar entirely while the game runs — the game
        // shows its OWN bossbar + scoreboard, so we don't stack a second one.
        hideBossBar();
        plugin.getGameManager().startGame(game.getId());

        if (maxRepetitions > 1) {
            broadcast("&6[KMC] &e" + game.getDisplayName() + " &7— spel &e"
                    + currentRepetition + "&7/&e" + maxRepetitions);
        }
    }

    private void endTournament(String lastWinner) {
        stop();

        // Closing cinematic (no-op if unconfigured) and ceremony text, then the full end sequence:
        // podium → awards → credits → fan favorite → feedback → statsbooks → reset.
        playCinematic("closing", () -> {
            ceremony("closing");
            plugin.getTournamentManager().endTournament();
        });
    }

    // ----------------------------------------------------------------
    // Post-game leaderboard chain (unchanged)
    // ----------------------------------------------------------------

    private void postGameLeaderboardChain() { postGameLeaderboardChain(() -> { }); }

    private void postGameLeaderboardChain(Runnable afterAll) {
        String gameName = "Laatste Game";
        KMCGame game = plugin.getGameManager().getActiveGame();
        if (game == null) {
            var played = plugin.getGameManager().getPlayedGamesThisTournament();
            if (!played.isEmpty()) {
                String lastId = played.stream().reduce((a, b) -> b).orElse(null);
                if (lastId != null && plugin.getGameManager().getGame(lastId) != null) {
                    gameName = plugin.getGameManager().getGame(lastId).getDisplayName();
                }
            }
        } else {
            gameName = game.getDisplayName();
        }

        final String finalGameName = gameName;

        int seq = plugin.getPointsLedger().lastGameSeq();
        try {
            var byTeam = nl.kmc.kmccore.scoring.PointsLedger.byTeam(plugin.getPointsLedger().forGame(seq));
            if (!byTeam.isEmpty()) {
                KMCTeam top = plugin.getTeamManager().getTeam(byTeam.keySet().iterator().next());
                if (top != null) plugin.getPresenter().say("presenter.game-result", 8, finalGameName,
                        top.getColor() + top.getDisplayName());
            }
        } catch (Throwable ignored) { }
        if (nl.kmc.kmccore.scoring.ScoreReport.hasData(plugin, seq)) {
            // Where did THIS game's points come from: top players, per-team breakdown, "your game".
            nl.kmc.kmccore.scoring.ScoreReport.schedule(plugin, seq, finalGameName);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> broadcastTopPlayers(finalGameName), 20L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> broadcastGameTeamLeaderboard(finalGameName), 200L);
        }
        // The GG button, right after the score report has started.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { plugin.getGgManager().open(); } catch (Throwable ignored) { }
        }, 300L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getConfig().getBoolean("presentation.leaderboard-animation", true)) {
                // a short breather after the last line, then on with the intermission
                nl.kmc.kmccore.presentation.LeaderboardAnimation.run(plugin, ranksBefore, pointsBefore,
                        () -> Bukkit.getScheduler().runTaskLater(plugin, afterAll, 40L));
            } else {
                broadcastOverallTeamLeaderboard();
                Bukkit.getScheduler().runTaskLater(plugin, afterAll, 60L);
            }
        }, 400L);
    }

    private void broadcastTopPlayers(String gameName) {
        broadcast("&6═══════════════════════════════════");
        broadcast("&e&lTop Spelers &7— " + gameName);
        broadcast("&6═══════════════════════════════════");
        List<PlayerData> top = plugin.getPlayerDataManager().getLeaderboard().stream().limit(5).toList();
        if (top.isEmpty()) broadcast("&7Geen data beschikbaar.");
        else for (int i = 0; i < top.size(); i++) {
            PlayerData pd = top.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " &f" + pd.getName() + " &8- &e" + pd.getPoints() + " punten");
        }
        broadcast("&6═══════════════════════════════════");
    }

    private void broadcastGameTeamLeaderboard(String gameName) {
        broadcast("&6═══════════════════════════════════");
        broadcast("&e&lTop Teams &7— " + gameName);
        broadcast("&6═══════════════════════════════════");
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints().stream().limit(5).toList();
        if (teams.isEmpty()) broadcast("&7Geen teams actief.");
        else for (int i = 0; i < teams.size(); i++) {
            KMCTeam t = teams.get(i);
            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints() + " punten");
        }
        broadcast("&6═══════════════════════════════════");
    }

    private void broadcastOverallTeamLeaderboard() {
        broadcast("&6═══════════════════════════════════");
        broadcast("&d&l🏆 TOTAAL TOERNOOI KLASSEMENT 🏆");
        broadcast("&6═══════════════════════════════════");
        List<KMCTeam> all = plugin.getTeamManager().getTeamsSortedByPoints();
        if (all.isEmpty()) broadcast("&7Geen teams geregistreerd.");
        else for (int i = 0; i < all.size(); i++) {
            KMCTeam t = all.get(i);
            String medal = i == 0 ? "&6#1" : i == 1 ? "&7#2" : i == 2 ? "&c#3" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints());
        }
        // Individual ranking next to the team one.
        List<PlayerData> top = plugin.getPlayerDataManager().getLeaderboard().stream().limit(5).toList();
        if (!top.isEmpty()) {
            broadcast("&d&lTOP SPELERS (totaal)");
            for (int i = 0; i < top.size(); i++) {
                PlayerData pd = top.get(i);
                KMCTeam pt = plugin.getTeamManager().getTeamByPlayer(pd.getUuid());
                broadcast("  &7#" + (i + 1) + " &f" + pd.getName()
                        + (pt != null ? " &8(" + pt.getColor() + pt.getDisplayName() + "&8)" : "")
                        + " &8- &e" + pd.getPoints());
            }
        }
        broadcast("&6═══════════════════════════════════");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void startTick(Runnable onTick) {
        cancelTick();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, onTick, 20L, 20L);
    }
    private void cancelTick() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
    }

    private void createBossBar() {
        if (bossBar != null) hideBossBar();
        bossBar = Bukkit.createBossBar("KMC", BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        bossBar.setVisible(true);
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        bossBar.setVisible(false);
        bossBar.removeAll();
        bossBar = null;
    }

    private void setBossBar(String title, BarColor color, double progress) {
        if (bossBar == null) return;
        bossBar.setTitle(nl.kmc.kmccore.platform.HudScale.fit(MessageUtil.color(title), 330, 120));
        bossBar.setColor(color);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    private void setBossBarProgress(double progress) {
        if (bossBar == null) return;
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    public void addPlayerToBossBar(Player player) {
        if (bossBar != null) bossBar.addPlayer(player);
    }

    private void playTickSound(int secondsLeft) {
        if (secondsLeft != 10 && secondsLeft != 5 && secondsLeft > 3) return;
        if (secondsLeft <= 0) return;
        Sound s = secondsLeft <= 3 ? Sound.BLOCK_NOTE_BLOCK_BASS : Sound.BLOCK_NOTE_BLOCK_HAT;
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), s, 0.8f, 1f);
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(MessageUtil.color(msg));
    }

    // ----------------------------------------------------------------
    // Cinematic presentation hooks
    // ----------------------------------------------------------------

    /**
     * Plays a single camera route for all players, then runs {@code after}.
     * If the route is missing/empty (or CinematicManager unavailable), runs
     * {@code after} immediately — the tournament continues safely.
     */
    private void playCinematic(String routeId, Runnable after) {
        var cm = plugin.getCinematicManager();
        if (cm != null && cm.routeExists(routeId)) {
            cm.playRouteForAll(routeId, after);
        } else {
            after.run();
        }
    }

    /**
     * Plays a list of camera routes back-to-back, then runs {@code after}.
     * Any route not configured is skipped. Used for multi-stage sequences
     * like the opening ceremony (opening → team-showcase → tournament-overview).
     */
    private void playCinematicChain(List<String> routeIds, Runnable after) {
        if (routeIds == null || routeIds.isEmpty()) { after.run(); return; }
        playCinematic(routeIds.get(0),
                () -> playCinematicChain(routeIds.subList(1, routeIds.size()), after));
    }

    /**
     * Broadcasts the ceremony text (chat lines + title/subtitle) for a phase,
     * driven by {@code ceremonies.yml}. No-op if CeremonyManager is unavailable.
     */
    private void ceremony(String phase) {
        var cm = plugin.getCeremonyManager();
        if (cm == null) return;
        Map<String, String> ph = basePlaceholders();
        showCeremonyTitle(cm.getTitle(phase, ph), cm.getSubtitle(phase, ph));
        broadcastSpaced(cm.getMessages(phase, ph)); // reveal lines one at a time
    }

    /** Like {@link #ceremony(String)} but adds game_name / game_objective placeholders. */
    private void ceremonyGame(String phase, KMCGame game) {
        var cm = plugin.getCeremonyManager();
        if (cm == null) return;
        Map<String, String> ph = basePlaceholders();
        ph.put("game_name", game.getDisplayName());
        ph.put("game_objective", lookupObjective(game.getId()));
        cm.getMessages(phase, ph).forEach(Bukkit::broadcastMessage);
        showCeremonyTitle(cm.getTitle(phase, ph), cm.getSubtitle(phase, ph));
    }

    /**
     * Auto-generates the "competing teams" roster for the team-showcase
     * ceremony: each team in standings order with its players (online players
     * highlighted). Pulled live from the TeamManager so it always matches the
     * actual teams + members assigned for the event.
     */
    private List<List<String>> buildTeamShowcaseBlocks() {
        List<List<String>> out = new ArrayList<>();
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        if (teams.isEmpty()) {
            out.add(List.of(MessageUtil.color("  &7Geen teams ingesteld — gebruik &e/kmcrandomteams&7.")));
            return out;
        }
        for (var team : teams) {
            List<String> block = new ArrayList<>();
            var members = team.getMembers();
            block.add(MessageUtil.color(" " + team.getColor() + "&l▌ " + team.getDisplayName().toUpperCase()
                    + " &8» &7" + members.size() + (members.size() == 1 ? " speler" : " spelers")));
            if (members.isEmpty()) {
                block.add(MessageUtil.color("   &8(nog geen spelers)"));
            } else {
                List<String> names = new ArrayList<>();
                for (java.util.UUID id : members) {
                    var op = Bukkit.getOfflinePlayer(id);
                    String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
                    names.add((op.isOnline() ? "&a" : "&7") + name);
                }
                // Wrapped so a full team never runs off the edge of the chat window.
                block.addAll(ChatLayout.wrap(MessageUtil.color(String.join("&8, ", names)),
                        ChatLayout.WRAP_PX, "   ", "   "));
            }
            block.add("");
            out.add(block);
        }
        return out;
    }

    private Map<String, String> basePlaceholders() {
        Map<String, String> m = new HashMap<>();
        m.put("tournament_name", plugin.getConfig().getString("tournament.name", "KMC Tournament"));
        m.put("round",       String.valueOf(plugin.getTournamentManager().getCurrentRound()));
        m.put("multiplier",  String.valueOf(plugin.getTournamentManager().getMultiplier()));
        m.put("team_count",  String.valueOf(plugin.getTeamManager().getTeamsSortedByPoints().size()));
        m.put("total_rounds",    String.valueOf(plugin.getTournamentManager().getTotalRounds()));
        m.put("games_per_round", String.valueOf(gamesPerRound()));
        m.put("game_count",      String.valueOf(plugin.getGameManager().getEnabledGames().size()));
        return m;
    }

    private void showCeremonyTitle(String title, String subtitle) {
        boolean noTitle = (title == null || title.isBlank());
        boolean noSub   = (subtitle == null || subtitle.isBlank());
        if (noTitle && noSub) return;
        String t  = noTitle ? "" : title;
        String st = noSub   ? "" : subtitle;
        for (Player p : Bukkit.getOnlinePlayers()) p.sendTitle(t, st, 10, 70, 15);
    }

    /** Looks up a game's short description from the V2 registry, or "" if unavailable. */
    private String lookupDescription(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            var reg = v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class).get(gameId);
            if (reg.isPresent() && reg.get().getDescription() != null) return reg.get().getDescription();
        }
        return "";
    }

    /** Looks up a game's objective text from the V2 registry, or "" if unavailable. */
    private String lookupObjective(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 instanceof nl.kmc.core.KMCCorePlugin v2) {
            var reg = v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class).get(gameId);
            if (reg.isPresent()) return reg.get().getObjective();
        }
        return "";
    }

    /**
     * Cleanly ends the tournament with a full closing ceremony — picks a
     * winner (whoever has the most points, even if zero), resets all
     * stats, and broadcasts the standard "TOERNOOI AFGELOPEN" announcement.
     * Used when automation halts due to misconfigured games.
     */
    private void stopTournamentCleanly() {
        try {
            if (plugin.getTournamentManager() != null
                    && plugin.getTournamentManager().isActive()) {
                plugin.getTournamentManager().endTournament();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to end tournament cleanly: " + t.getMessage());
        }
    }

    /** Whether a game's arena/setup is complete (same check the automation uses before launching it). */
    public boolean isGameReady(String gameId) { return isGameReadyUnified(gameId); }

    /** Why a game is not ready (or "klaar"). */
    public String  getGameReadinessReason(String gameId) { return readinessReasonUnified(gameId); }

    public State   getState()            { return state; }
    public boolean isRunning()           { return state != State.IDLE && state != State.PAUSED; }
    public int     getCountdownSeconds() { return countdownSeconds; }
}