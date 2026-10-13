package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.util.AnnouncementUtil;
import nl.kmc.kmccore.util.ClickableVoteMessage;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the game pool, lifecycle, and voting.
 *
 * <p>VOTING CHANGE: The vote now contains EVERY game that hasn't been
 * played yet this tournament — not a random 3. Players pick via
 * a chest GUI opened by clicking the chat prompt or running /kmcvote.
 */
public class GameManager {

    private final KMCCore plugin;
    private final Map<String, KMCGame> games = new LinkedHashMap<>();

    private KMCGame activeGame;
    private KMCGame nextGame;

    /** Games played this tournament — excluded from voting. */
    private final Set<String> playedGamesThisTournament = new LinkedHashSet<>();

    // Vote state
    private boolean          votingActive;
    private List<KMCGame>    voteOptions;
    private Map<UUID, String> votes;

    private final Random random = new Random();

    public GameManager(KMCCore plugin) {
        this.plugin = plugin;
        loadGamesFromConfig();
        loadState();
    }

    private void loadGamesFromConfig() {
        ConfigurationSection gs = plugin.getConfig().getConfigurationSection("games.list");
        if (gs == null) return;
        for (String key : gs.getKeys(false)) {
            ConfigurationSection g = gs.getConfigurationSection(key);
            if (g == null) continue;
            Material icon = Material.matchMaterial(g.getString("icon", "PAPER"));
            if (icon == null) icon = Material.PAPER;
            games.put(key, new KMCGame(key, g.getString("display-name", key),
                    icon, g.getInt("min-players", 2)));
        }
        plugin.getLogger().info("Loaded " + games.size() + " games.");
    }

    /**
     * Makes sure a game that registered itself (any AbstractGamePlugin) is part of the rotation even when the
     * server's config.yml predates it — config files are never overwritten, so a newly added game would
     * otherwise exist as a plugin but be unknown to /kmcauto, voting and the setup GUI.
     * The entry is also written to config.yml so admins can edit/disable it like any other game.
     */
    public void ensureRegistered(String id, String displayName, Material icon, int minPlayers) {
        KMCGame known = games.get(id);
        if (known != null) {
            // The plugin knows best which icon belongs to its game; a config written long ago may show the
            // same icon for two games (in memory only: the config file is not touched).
            if (icon != null && known.getIcon() != icon) known.setIcon(icon);
            return;
        }
        games.put(id, new KMCGame(id, displayName, icon != null ? icon : Material.PAPER, Math.max(1, minPlayers)));
        String base = "games.list." + id + ".";
        var cfg = plugin.getConfig();
        if (!cfg.isSet(base + "display-name")) {
            cfg.set(base + "display-name", displayName);
            cfg.set(base + "icon", (icon != null ? icon : Material.PAPER).name());
            cfg.set(base + "min-players", Math.max(1, minPlayers));
            cfg.set(base + "repetitions", 1);
            plugin.saveConfig();
        }
        plugin.getLogger().info("[KMC] Game '" + id + "' added to the rotation automatically (not in config.yml yet).");
    }

    private void loadState() {
        String activeId = plugin.getDatabaseManager().getTournamentValue("active_game", null);
        if (activeId != null && !activeId.isEmpty()) activeGame = games.get(activeId);

        String played = plugin.getDatabaseManager().getTournamentValue("played_games", "");
        if (!played.isEmpty()) {
            for (String id : played.split(",")) {
                if (!id.isBlank()) playedGamesThisTournament.add(id);
            }
        }
    }

    public void save() {
        plugin.getDatabaseManager().setTournamentValue(
                "active_game", activeGame != null ? activeGame.getId() : "");
        plugin.getDatabaseManager().setTournamentValue(
                "played_games", String.join(",", playedGamesThisTournament));
    }

    // ----------------------------------------------------------------
    // Game lifecycle
    // ----------------------------------------------------------------

    public boolean startGame(String gameId) {
        KMCGame game = games.get(gameId);
        if (game == null) return false;

        activeGame = game;
        nextGame   = null;
        playedGamesThisTournament.add(gameId);
        save();
        try {
            plugin.getPointsLedger().beginGame(gameId, plugin.getTournamentManager().getCurrentRound());
        } catch (Throwable t) { plugin.getLogger().warning("[KMC] ledger.beginGame failed: " + t); }

        final String gId = gameId;
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.getArenaManager().loadArenaForGame(gId), 10L);

        AnnouncementUtil.broadcastTitle(plugin, "announcements.game-start",
                new String[]{"{game_name}", "{round}", "{multiplier}"},
                new String[]{game.getDisplayName(),
                        String.valueOf(plugin.getTournamentManager().getCurrentRound()),
                        String.valueOf(plugin.getTournamentManager().getMultiplier())});

        Bukkit.broadcastMessage(MessageUtil.get("broadcast.game-start")
                .replace("{game_name}", game.getDisplayName())
                .replace("{round}", String.valueOf(plugin.getTournamentManager().getCurrentRound()))
                .replace("{multiplier}", String.valueOf(plugin.getTournamentManager().getMultiplier())));

        plugin.getScoreboardManager().refreshAll();

        // Start the game. Migrated (V2) games listen for the V2 GameStartEvent;
        // any legacy game uses the V1 KMCApi hook path. fireV2GameStart returns
        // true when it found a V2 registration and dispatched the event.
        if (!fireV2GameStart(gameId)) {
            // Legacy V1 path — PreGameAnnouncer countdown + V1 onGameStart hooks.
            plugin.getApi().fireGameStart(gameId);
        }

        return true;
    }

    /**
     * Bridges {@code /kmcauto} to the V2 game framework.
     *
     * <p>All current minigames extend {@code AbstractGamePlugin} and listen for
     * {@code nl.kmc.core.event.GameStartEvent} (fired with a {@code GameRegistration}).
     * This dispatches that event so the V2 game manager resets its arena and
     * starts — exactly as if the V2 tournament engine had launched it.
     *
     * @return true if a V2 registration was found and the event was fired.
     */
    private boolean fireV2GameStart(String gameId) {
        var coreV2 = Bukkit.getPluginManager().getPlugin(nl.kmc.core.KMCConstants.CORE_V2_PLUGIN_NAME);
        if (!(coreV2 instanceof nl.kmc.core.KMCCorePlugin v2)) return false;

        var registry = v2.getContainer().get(nl.kmc.core.service.GameRegistryService.class);
        var regOpt   = registry.get(gameId);
        if (regOpt.isEmpty()) return false;

        registry.setActive(gameId);
        registry.markPlayed(gameId);
        int round = plugin.getTournamentManager().getCurrentRound();
        Bukkit.getPluginManager().callEvent(
                new nl.kmc.core.event.GameStartEvent(regOpt.get(), round));
        plugin.getLogger().info("[KMC] Dispatched V2 GameStartEvent for '" + gameId + "'.");
        return true;
    }

    public boolean stopGame(String winnerName) {
        if (activeGame == null) return false;
        String gameId = activeGame.getId();
        String gameName = activeGame.getDisplayName();
        activeGame = null;
        save();

        // If the game is still running (admin force-end), actually stop it. A game that ended by itself is
        // already ENDED here, so this is a no-op for the normal path.
        Bukkit.getPluginManager().callEvent(new nl.kmc.core.event.GameStopRequestEvent(gameId));

        AnnouncementUtil.broadcastTitle(plugin, "announcements.game-end",
                new String[]{"{winner}"},
                new String[]{winnerName != null ? winnerName : "?"});
        Bukkit.broadcastMessage(MessageUtil.get("broadcast.game-end")
                .replace("{winner}", winnerName != null ? winnerName : "?"));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getArenaManager().resetArenaForGame(gameId);
            plugin.getArenaManager().teleportAllToLobby();
        }, 60L);

        plugin.getScoreboardManager().refreshAll();
        plugin.getApi().fireGameEnd(gameName, winnerName);

        // Momentum: snapshot team standings after this game's points settled (never for a dry-run).
        if (!nl.kmc.core.DryRun.active()) try {
            java.util.List<String> ranked = plugin.getTeamManager().getTeamsSortedByPoints()
                    .stream().map(nl.kmc.core.domain.KMCTeam::getId).collect(java.util.stream.Collectors.toList());
            plugin.getTournamentDataStore().recordGameStandings(ranked);
            plugin.getTournamentDataStore().updateElo(ranked);
        } catch (Exception ignored) {}

        if (plugin.getAutomationManager().isRunning()) {
            plugin.getAutomationManager().onGameEnd(winnerName);
        }
        return true;
    }

    public boolean skipGame() {
        if (activeGame == null) return false;
        activeGame = null;
        save();
        plugin.getScoreboardManager().refreshAll();
        return true;
    }

    public boolean forceSkipCurrentGame() {
        if (activeGame == null) return false;
        String skipped = activeGame.getDisplayName();
        String gameId = activeGame.getId();
        activeGame = null;
        save();

        Bukkit.getPluginManager().callEvent(new nl.kmc.core.event.GameStopRequestEvent(gameId));
        Bukkit.broadcastMessage(MessageUtil.color(
                "&6[KMC] &cGame &e" + skipped + " &cis overgeslagen door een admin!"));
        plugin.getArenaManager().resetArenaForGame(gameId);
        plugin.getArenaManager().teleportAllToLobby();
        plugin.getScoreboardManager().refreshAll();

        if (plugin.getAutomationManager().isRunning()) {
            plugin.getAutomationManager().onGameEnd(null);
        }
        return true;
    }

    // ----------------------------------------------------------------
    // Randomizer / force
    // ----------------------------------------------------------------

    /** Games toggled on for this tournament's rotation (see /kmcauto's setup menu). */
    public List<KMCGame> getEnabledGames() {
        return games.values().stream()
                .filter(g -> plugin.getConfig().getBoolean("games.list." + g.getId() + ".enabled", true))
                // finale-only games (Dodgebolt) are never part of the voting rotation
                .filter(g -> !plugin.getConfig().getBoolean("games.list." + g.getId() + ".finale-only", false))
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    // Surprise game: a game flagged "surprise" stays hidden (as ???) until it is actually chosen
    // ----------------------------------------------------------------

    private final Set<String> revealedSurprises = new HashSet<>();
    /** Games drawn at random for this tournament ({@code games.surprise-count}); redrawn on every start. */
    private final Set<String> rolledSurprises = new HashSet<>();

    /** True when the game is a surprise (flagged in the config or drawn at random) and not revealed yet. */
    public boolean isSurpriseHidden(String gameId) {
        return gameId != null && isSurprise(gameId) && !revealedSurprises.contains(gameId);
    }

    public boolean isSurprise(String gameId) {
        return gameId != null && rolledSurprises.contains(gameId);
    }

    /** Draws {@code games.surprise-count} games at random from the rotation to be this tournament's surprises. */
    public void rollSurprises() {
        rolledSurprises.clear();
        // Old per-game flags (the right-click toggle) are gone: a surprise has to be a surprise.
        var list = plugin.getConfig().getConfigurationSection("games.list");
        if (list != null) for (String id : list.getKeys(false))
            if (plugin.getConfig().getBoolean("games.list." + id + ".surprise", false))
                plugin.getConfig().set("games.list." + id + ".surprise", null);
        int n = Math.max(0, plugin.getConfig().getInt("games.surprise-count", 0));
        if (n == 0) return;
        List<String> pool = new ArrayList<>();
        for (KMCGame g : getEnabledGames()) pool.add(g.getId());
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(n, pool.size()); i++) rolledSurprises.add(pool.get(i));
    }

    public void setSurprise(String gameId, boolean on) {
        if (on) rolledSurprises.add(gameId);
        else { rolledSurprises.remove(gameId); revealedSurprises.remove(gameId); }
    }

    /** Marks the surprise as revealed (it is being played now). */
    public void revealSurprise(String gameId) { revealedSurprises.add(gameId); }

    /** A new tournament: every surprise is a surprise again. */
    public void resetSurprises() { revealedSurprises.clear(); }

    /** Takes a game back out of the "played" set (a dry-run must not use up a game). */
    public void unmarkPlayed(String gameId) {
        if (gameId == null) return;
        playedGamesThisTournament.remove(gameId);
        save();
    }

    public List<KMCGame> getAvailableGames() {
        return getEnabledGames().stream()
                .filter(g -> !playedGamesThisTournament.contains(g.getId()))
                .collect(Collectors.toList());
    }

    public KMCGame randomNextGame() {
        List<KMCGame> pool = getAvailableGames();
        if (pool.isEmpty()) pool = getEnabledGames();
        if (pool.isEmpty()) return null;
        KMCGame chosen = pool.get(random.nextInt(pool.size()));
        nextGame = chosen;
        return chosen;
    }

    public boolean forceNextGame(String gameId) {
        KMCGame game = games.get(gameId);
        if (game == null) return false;
        nextGame = game;
        return true;
    }

    // ----------------------------------------------------------------
    // VOTING — now includes ALL unplayed games
    // ----------------------------------------------------------------

    /**
     * Opens a vote with EVERY game that hasn't been played yet this tournament.
     * Sends a clickable chat prompt so players can open the GUI.
     */
    public void startVote() {
        if (votingActive) return;

        int durationSecs = plugin.getConfig().getInt("games.voting-duration", 30);

        // ALL unplayed, enabled games this tournament — not limited to 3
        voteOptions = new ArrayList<>(getAvailableGames());
        if (voteOptions.isEmpty()) voteOptions = getEnabledGames();
        // Shuffle so the order in the GUI varies
        Collections.shuffle(voteOptions, random);

        votes        = new HashMap<>();
        votingActive = true;

        // Send clickable chat prompt — clicking opens the GUI via /kmcvote
        ClickableVoteMessage.sendGuiPrompt(voteOptions.size(), durationSecs);

        // Auto-open the GUI for everyone online
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.getVoteGuiListener().openVoteGui(p);
        }
    }

    public boolean castVote(Player player, int option) {
        if (!votingActive) { player.sendMessage(MessageUtil.get("vote.not-active")); return false; }
        if (option < 1 || option > voteOptions.size()) {
            player.sendMessage(MessageUtil.color("&cOngeldige optie.")); return false;
        }
        if (votes.containsKey(player.getUniqueId())) {
            player.sendMessage(MessageUtil.get("vote.already-voted")); return false;
        }
        String gameId = voteOptions.get(option - 1).getId();
        votes.put(player.getUniqueId(), gameId);
        // Per-player language: confirmation is shown in the voter's chosen language.
        player.sendMessage(plugin.getLanguageManager().tr(player, "vote.confirm",
                isSurpriseHidden(gameId) ? "§d???" : voteOptions.get(option - 1).getDisplayName()));
        return true;
    }

    public void endVote() {
        if (!votingActive) return;
        votingActive = false;

        Map<String, Integer> tally = new HashMap<>();
        for (String gameId : votes.values()) tally.merge(gameId, 1, Integer::sum);

        String winnerId = tally.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (winnerId == null) {
            KMCGame rng = randomNextGame();
            if (rng != null) winnerId = rng.getId();
        }
        if (winnerId != null) {
            KMCGame winner = games.get(winnerId);
            nextGame = winner;
            ClickableVoteMessage.sendResult(winner, tally.getOrDefault(winnerId, 0), isSurpriseHidden(winner.getId()));
        }

        // Close any open vote GUIs
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTitle().contains("Stem")) p.closeInventory();
        }
    }

    public void resetPlayedGames() {
        playedGamesThisTournament.clear();
        revealedSurprises.clear();
        rollSurprises();
        save();
    }

    /**
     * Marks a game as played this tournament WITHOUT actually starting it —
     * no arena load, no broadcasts, no plugin start events. Used by
     * {@code /event simulate} so the dry-run's game rotation is exactly as
     * realistic as a live tournament (no repeats until every game has had
     * a turn) without touching the live server.
     */
    public void markGamePlayedForSimulation(String gameId) {
        if (gameId == null || !games.containsKey(gameId)) return;
        playedGamesThisTournament.add(gameId);
        save();
    }

    /** Current number of votes cast for a game (0 if no vote active). */
    public int getVoteCount(String gameId) {
        if (votes == null || gameId == null) return 0;
        return (int) votes.values().stream().filter(gameId::equals).count();
    }

    // ---- Getters ---------------------------------------------------
    public KMCGame getActiveGame()                      { return activeGame; }
    public KMCGame getNextGame()                        { return nextGame; }
    public boolean isVotingActive()                     { return votingActive; }
    public List<KMCGame> getVoteOptions()               { return voteOptions != null ? voteOptions : new ArrayList<>(); }
    public Collection<KMCGame> getAllGames()            { return Collections.unmodifiableCollection(games.values()); }
    public KMCGame getGame(String id)                   { return games.get(id); }
    public boolean isGameActive()                       { return activeGame != null; }
    public Set<String> getPlayedGamesThisTournament()   { return Collections.unmodifiableSet(playedGamesThisTournament); }
}
