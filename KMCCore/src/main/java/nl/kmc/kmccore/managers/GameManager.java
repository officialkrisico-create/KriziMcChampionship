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
            Material icon;
            try { icon = Material.valueOf(g.getString("icon", "PAPER").toUpperCase()); }
            catch (IllegalArgumentException e) { icon = Material.PAPER; }
            games.put(key, new KMCGame(key, g.getString("display-name", key),
                    icon, g.getInt("min-players", 2)));
        }
        plugin.getLogger().info("Loaded " + games.size() + " games.");
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
        return true;
    }

    public boolean stopGame(String winnerName) {
        if (activeGame == null) return false;
        String gameId = activeGame.getId();
        String gameName = activeGame.getDisplayName();
        activeGame = null;
        save();

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

    public List<KMCGame> getAvailableGames() {
        return games.values().stream()
                .filter(g -> !playedGamesThisTournament.contains(g.getId()))
                .collect(Collectors.toList());
    }

    public KMCGame randomNextGame() {
        List<KMCGame> pool = getAvailableGames();
        if (pool.isEmpty()) pool = new ArrayList<>(games.values());
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

        // ALL unplayed games this tournament — not limited to 3
        voteOptions = new ArrayList<>(getAvailableGames());
        if (voteOptions.isEmpty()) voteOptions = new ArrayList<>(games.values());
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
        player.sendMessage(MessageUtil.get("vote.submit")
                .replace("{game}", voteOptions.get(option - 1).getDisplayName()));
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
            ClickableVoteMessage.sendResult(winner, tally.getOrDefault(winnerId, 0));
        }

        // Close any open vote GUIs
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTitle().contains("Stem")) p.closeInventory();
        }
    }

    public void resetPlayedGames() {
        playedGamesThisTournament.clear();
        save();
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
