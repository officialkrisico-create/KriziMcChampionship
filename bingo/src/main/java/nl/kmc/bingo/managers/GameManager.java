package nl.kmc.bingo.managers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.models.BingoCard;
import nl.kmc.bingo.models.TeamCardState;
import nl.kmc.bingo.objectives.CollectObjective;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Bingo game lifecycle.
 *
 * <p>States: IDLE → PREPARING_WORLD → COUNTDOWN → ACTIVE → ENDED → IDLE
 *
 * <p>Win conditions:
 * <ul>
 *   <li>15-min timer expires → team with most squares wins (tiebreak: most lines)</li>
 *   <li>Any team completes the full card → instant win</li>
 * </ul>
 */
public class GameManager {

    public enum State { IDLE, PREPARING_WORLD, COUNTDOWN, ACTIVE, ENDED }

    public static final String GAME_ID = "bingo_teams";

    private final BingoPlugin plugin;
    private State state = State.IDLE;

    private BingoCard                  currentCard;
    private final Map<String, TeamCardState> teamStates = new LinkedHashMap<>();

    /**
     * Tracks the order in which TEAMS first complete each square.
     * Key: square index. Value: list of teamIds in order completed.
     * Each team appears at most once per square.
     */
    private final Map<Integer, List<String>> craftRace = new HashMap<>();
    private final Set<UUID>            participants = new HashSet<>();

    private BukkitTask countdownTask;
    private BukkitTask gameTimerTask;
    private BossBar    bossBar;

    private int  countdownSeconds;
    private int  remainingSeconds;
    private long gameStartMs;

    public GameManager(BingoPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private PotionEffectType slow() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("slowness")); }
        catch (Exception e) { return null; }
    }
    private PotionEffectType jumpBoost() {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft("jump_boost")); }
        catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------
    // API
    // ----------------------------------------------------------------

    /**
     * Begins game prep: clones the world, then runs countdown, then
     * launches active phase.
     *
     * @return error message if startup failed, or null on success
     */
    public String startGame() {
        if (state != State.IDLE) return "Er is al een game bezig.";
        if (!plugin.getWorldManager().templateExists()) {
            return "Template world '" + plugin.getWorldManager().getTemplateWorldName()
                 + "' niet gevonden! Maak er een met /bingo settemplate.";
        }
        var teams = plugin.getKmcCore().getTeamManager().getAllTeams();
        if (teams.size() < 2) return "Minimaal 2 teams nodig.";

        state = State.PREPARING_WORLD;
        broadcast("&6[Bingo] &eDe wereld wordt voorbereid...");

        plugin.getWorldManager().createGameWorldAsync(world -> {
            if (world == null) {
                broadcast("&c[Bingo] Kon de wereld niet voorbereiden! Game afgebroken.");
                state = State.IDLE;
                return;
            }
            // World is loaded — proceed to countdown
            beginCountdown();
        });

        return null;
    }

    private void beginCountdown() {
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        // Build the card (same seed → same card for everyone)
        long seed = System.currentTimeMillis();
        currentCard = plugin.getCardGenerator().generate(seed);
        plugin.getLogger().info("Bingo card generated (seed " + seed + ").");

        // Init per-team state
        teamStates.clear();
        craftRace.clear();
        participants.clear();
        for (KMCTeam team : plugin.getKmcCore().getTeamManager().getAllTeams()) {
            if (team.getMembers().isEmpty()) continue;
            teamStates.put(team.getId(), new TeamCardState(team.getId(), currentCard));
            participants.addAll(team.getMembers());
        }

        // Acquire scoreboard lock from KMCCore
        plugin.getKmcCore().getApi().acquireScoreboard("bingo");

        // Teleport, freeze, kit
        // Each team gets an anchor 20 blocks from other teams; teammates
        // spawn 4+ blocks apart from each other near their anchor.
        Location baseSpawn = plugin.getWorldManager().getDefaultSpawn();

        List<KMCTeam> activeTeams = new ArrayList<>();
        for (KMCTeam team : plugin.getKmcCore().getTeamManager().getAllTeams()) {
            if (team.getMembers().isEmpty()) continue;
            // Skip teams with no online members
            boolean anyOnline = team.getMembers().stream()
                    .anyMatch(uuid -> Bukkit.getPlayer(uuid) != null);
            if (anyOnline) activeTeams.add(team);
        }

        List<Location> teamAnchors = nl.kmc.bingo.util.SafeSpawnHelper
                .findTeamSpawns(baseSpawn, activeTeams.size());

        Map<UUID, Location> playerSpawns = new HashMap<>();
        for (int i = 0; i < activeTeams.size(); i++) {
            KMCTeam team = activeTeams.get(i);
            Location anchor = (i < teamAnchors.size()) ? teamAnchors.get(i) : baseSpawn;
            // Online member count
            List<UUID> onlineMembers = new ArrayList<>();
            for (UUID uuid : team.getMembers()) {
                if (Bukkit.getPlayer(uuid) != null) onlineMembers.add(uuid);
            }
            List<Location> memberSpawns = nl.kmc.bingo.util.SafeSpawnHelper
                    .findPlayerSpawnsNearAnchor(anchor, onlineMembers.size());
            for (int m = 0; m < onlineMembers.size(); m++) {
                Location spot = (m < memberSpawns.size()) ? memberSpawns.get(m) : anchor;
                playerSpawns.put(onlineMembers.get(m), spot);
            }
        }

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            Location spot = playerSpawns.getOrDefault(uuid, baseSpawn);
            p.teleport(spot);
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            int ticks = countdownSeconds * 20;
            if (slowType != null) p.addPotionEffect(new PotionEffect(slowType, ticks, 255, true, false, false));
            if (jumpType != null) p.addPotionEffect(new PotionEffect(jumpType, ticks, 128, true, false, false));
        }

        // BossBar
        bossBar = Bukkit.createBossBar(
                ChatColor.YELLOW + "" + ChatColor.BOLD + "Bingo start over " + countdownSeconds + "s",
                BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        broadcast("&6[Bingo] &eGame start over &6" + countdownSeconds + " &eseconden!");

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            countdownSeconds--;
            double progress = (double) countdownSeconds /
                    Math.max(1, plugin.getConfig().getInt("game.countdown-seconds", 15));
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.setTitle(ChatColor.YELLOW + "" + ChatColor.BOLD
                    + "Bingo start over " + countdownSeconds + "s");

            if (countdownSeconds <= 5 && countdownSeconds > 0) {
                bossBar.setColor(BarColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "" + countdownSeconds,
                            ChatColor.YELLOW + "Maak je klaar!", 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                }
            }

            if (countdownSeconds <= 0) {
                countdownTask.cancel();
                launch();
            }
        }, 20L, 20L);
    }

    private void launch() {
        state = State.ACTIVE;
        gameStartMs = System.currentTimeMillis();
        remainingSeconds = plugin.getConfig().getInt("game.max-duration-seconds", 900);

        bossBar.setColor(BarColor.GREEN);
        updateBossBar();

        PotionEffectType slowType = slow();
        PotionEffectType jumpType = jumpBoost();

        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            if (slowType != null) p.removePotionEffect(slowType);
            if (jumpType != null) p.removePotionEffect(jumpType);
            giveStarterKit(p);
            p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "GO!",
                    ChatColor.YELLOW + "Vul de bingo card!", 0, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.5f);
        }

        broadcast("&a&l[Bingo] &eGO! &7Verzamel de items op de bingokaart!");
        broadcast("&7Open je card met &6/bingo card");

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remainingSeconds--;
            updateBossBar();

            // Check win conditions
            if (remainingSeconds <= 0) {
                endGame("time_limit");
                return;
            }
            // Has any team completed the full card?
            for (TeamCardState s : teamStates.values()) {
                if (s.isFullCardCompleted()) {
                    endGame("full_card");
                    return;
                }
            }
        }, 20L, 20L);
    }

    private void giveStarterKit(Player p) {
        PlayerInventory inv = p.getInventory();
        for (String entry : plugin.getConfig().getStringList("game.starter-kit")) {
            String[] parts = entry.split(":");
            try {
                Material m = Material.valueOf(parts[0].toUpperCase());
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                inv.addItem(new ItemStack(m, amount));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad starter-kit entry: " + entry);
            }
        }
    }

    // ----------------------------------------------------------------
    // Inventory recount — called by listener when items move
    // ----------------------------------------------------------------

    /**
     * Recounts a team's progress on all CollectObjective squares by
     * tallying items across all team-member inventories. Idempotent —
     * safe to call frequently.
     *
     * @param triggeredBy the player whose inventory just changed (used
     *                    to credit per-craft points). May be null if
     *                    unknown — credit will go to the team captain in
     *                    that case (or first member, fallback).
     */
    public void recountTeam(String teamId, UUID triggeredBy) {
        if (state != State.ACTIVE) return;
        TeamCardState ts = teamStates.get(teamId);
        if (ts == null) return;
        KMCTeam team = plugin.getKmcCore().getTeamManager().getTeam(teamId);
        if (team == null) return;

        // Aggregate item counts across all online team members
        Map<Material, Integer> totals = new EnumMap<>(Material.class);
        for (UUID memberId : team.getMembers()) {
            Player p = Bukkit.getPlayer(memberId);
            if (p == null) continue;
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack == null || stack.getType() == Material.AIR) continue;
                totals.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }

        // Walk the card; for each CollectObjective, update progress
        BingoCard card = ts.getCard();
        for (int idx = 0; idx < BingoCard.TOTAL; idx++) {
            var obj = card.get(idx);
            if (!(obj instanceof CollectObjective co)) continue;
            int have = totals.getOrDefault(co.getTarget(), 0);
            boolean wasComplete = ts.isCompleted(idx);
            ts.setProgress(idx, have);
            if (!wasComplete && ts.isCompleted(idx)) {
                onSquareCompleted(team, idx, triggeredBy);
            }
        }
    }

    /** Backwards-compat overload — calls the full version with no triggerer. */
    public void recountTeam(String teamId) {
        recountTeam(teamId, null);
    }

    private void onSquareCompleted(KMCTeam team, int squareIdx, UUID triggeredBy) {
        var obj = currentCard.get(squareIdx);
        broadcast("&a✔ " + team.getColor() + team.getDisplayName()
                + " &7heeft &f" + plainName(obj.getDisplayName())
                + " &7verzameld!");

        // Per-craft race: which team-rank is this completion?
        List<String> race = craftRace.computeIfAbsent(squareIdx, k -> new ArrayList<>());
        if (!race.contains(team.getId())) {
            race.add(team.getId());
            int teamRank = race.size();   // 1, 2, 3, 4, 5, ...
            int pts = readPlacement("points.per-craft-by-team-rank", teamRank);
            if (pts > 0) {
                // Credit goes to the player who triggered (crafted/collected) it,
                // OR to a fallback team member if we don't know who.
                UUID creditTo = triggeredBy;
                if (creditTo == null && !team.getMembers().isEmpty()) {
                    creditTo = team.getMembers().iterator().next();
                }
                if (creditTo != null) {
                    plugin.getKmcCore().getApi().givePoints(creditTo, pts);
                    Player crafter = Bukkit.getPlayer(creditTo);
                    if (crafter != null) {
                        crafter.sendActionBar(net.kyori.adventure.text.Component.text(
                                ChatColor.GOLD + "+" + pts + " bingo craft (#" + teamRank + ")"));
                        crafter.playSound(crafter.getLocation(),
                                Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                    }
                }
            }
        }

        // Did this square complete a line?
        TeamCardState ts = teamStates.get(team.getId());
        if (ts == null) return;
        for (int line = 0; line < BingoCard.LINE_COUNT; line++) {
            int[] indices = BingoCard.lineIndices(line);
            // Was this square part of the line + is the line now done?
            boolean inLine = false;
            for (int i : indices) if (i == squareIdx) { inLine = true; break; }
            if (!inLine) continue;
            if (!ts.isLineCompleted(line)) continue;
            broadcast("&6&l✦ BINGO! " + team.getColor() + team.getDisplayName()
                    + " &7heeft " + BingoCard.lineDescription(line) + " voltooid!");
            for (UUID m : team.getMembers()) {
                Player p = Bukkit.getPlayer(m);
                if (p != null) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }

        updateBossBar();
    }

    private static String plainName(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }

    // ----------------------------------------------------------------
    // End game + scoring
    // ----------------------------------------------------------------

    private void endGame(String reason) {
        if (state == State.ENDED || state == State.IDLE) return;
        state = State.ENDED;

        cancelTasks();

        // Compute scores per team
        record TeamScore(String teamId, int squares, int lines, boolean fullCard, long fullCardAt) {}
        List<TeamScore> ranked = new ArrayList<>();
        for (TeamCardState ts : teamStates.values()) {
            ranked.add(new TeamScore(
                    ts.getTeamId(),
                    ts.getCompletedSquareCount(),
                    ts.getCompletedLineCount(),
                    ts.isFullCardCompleted(),
                    ts.getFullCardCompletedAt()));
        }
        // Sort: full-card first (earliest wins), then squares, then lines
        ranked.sort((a, b) -> {
            if (a.fullCard != b.fullCard) return a.fullCard ? -1 : 1;
            if (a.fullCard && b.fullCard) return Long.compare(a.fullCardAt, b.fullCardAt);
            if (a.squares != b.squares) return Integer.compare(b.squares, a.squares);
            return Integer.compare(b.lines, a.lines);
        });

        broadcast("&6═══════════════════════════════════");
        broadcast("&6&lBingo — Uitslag");
        broadcast("&7Reden: " + (reason.equals("full_card") ? "&aTeam heeft de volledige card!" : "&eTijd op"));
        broadcast("&6═══════════════════════════════════");

        KMCApi api = plugin.getKmcCore().getApi();
        String winnerName = "Niemand";

        // Completion bonus: team with the MOST squares gets a 100-point pool
        // split equally among its members. (4-member team → each gets 25;
        // 2-member team → each gets 50.)
        int completionPool = plugin.getConfig().getInt("points.completion-bonus-pool", 100);
        if (completionPool > 0 && !ranked.isEmpty()) {
            TeamScore winner = ranked.get(0);
            KMCTeam winnerTeam = plugin.getKmcCore().getTeamManager().getTeam(winner.teamId);
            if (winnerTeam != null && !winnerTeam.getMembers().isEmpty()) {
                int perMember = completionPool / winnerTeam.getMembers().size();
                if (perMember > 0) {
                    for (UUID memberId : winnerTeam.getMembers()) {
                        api.givePoints(memberId, perMember);
                    }
                    broadcast("&6&l🏆 Completion bonus: " + winnerTeam.getColor()
                            + winnerTeam.getDisplayName() + " &7(+" + perMember + " elk)");
                }
            }
        }

        for (int i = 0; i < ranked.size(); i++) {
            TeamScore ts = ranked.get(i);
            KMCTeam team = plugin.getKmcCore().getTeamManager().getTeam(ts.teamId);
            if (team == null) continue;

            String medal = i == 0 ? "&6🥇" : i == 1 ? "&7🥈" : i == 2 ? "&c🥉" : "&7#" + (i + 1);
            broadcast("  " + medal + " " + team.getColor() + team.getDisplayName()
                    + " &8- &e" + ts.squares + " vakjes &7(" + ts.lines + " lijnen"
                    + (ts.fullCard ? " &6&l+VOLLE CARD" : "") + "&7)");

            // Final tournament placement bonus (kept for compatibility)
            String[] placePts = {"team-first-place", "team-second-place", "team-third-place"};
            int placePts2 = 0;
            if (i < placePts.length)
                placePts2 = plugin.getConfig().getInt("points." + placePts[i], 0);
            else
                placePts2 = plugin.getConfig().getInt("points.team-participation", 0);

            for (UUID memberId : team.getMembers()) {
                if (placePts2 > 0) api.givePoints(memberId, placePts2);

                // Record per-player tournament stats
                Player member = Bukkit.getPlayer(memberId);
                String memberName = member != null ? member.getName() : memberId.toString();
                api.recordGameParticipation(memberId, memberName, GAME_ID, i == 0);
            }

            if (i == 0) winnerName = team.getColor() + team.getDisplayName();
        }
        broadcast("&6═══════════════════════════════════");

        // Title screen for everyone
        final String finalWinnerName = winnerName;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.translateAlternateColorCodes('&', "&6&l🏆 " + finalWinnerName),
                    ChatColor.translateAlternateColorCodes('&', "&7wint Bingo!"), 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(finalWinnerName), 100L);
    }

    private void cleanup(String winnerName) {
        plugin.getKmcCore().getApi().releaseScoreboard("bingo");
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        // Teleport players back to lobby
        var lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            for (var eff : p.getActivePotionEffects()) p.removePotionEffect(eff.getType());
            if (lobby != null) p.teleport(lobby);
        }

        // Dispose the cloned world
        plugin.getWorldManager().disposeGameWorld();

        teamStates.clear();
        participants.clear();
        currentCard = null;
        state = State.IDLE;

        if (plugin.getKmcCore().getAutomationManager().isRunning()) {
            plugin.getKmcCore().getAutomationManager().onGameEnd(winnerName);
        }
    }

    public void forceStop() {
        if (state != State.IDLE) endGame("force_stop");
    }

    private void cancelTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (gameTimerTask != null) { gameTimerTask.cancel(); gameTimerTask = null; }
    }

    // ----------------------------------------------------------------
    // BossBar
    // ----------------------------------------------------------------

    private void updateBossBar() {
        if (bossBar == null) return;
        // Show leading team's progress
        TeamCardState top = null;
        for (TeamCardState s : teamStates.values()) {
            if (top == null) { top = s; continue; }
            if (s.getCompletedSquareCount() > top.getCompletedSquareCount()) top = s;
        }
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        String leader = "";
        if (top != null) {
            KMCTeam team = plugin.getKmcCore().getTeamManager().getTeam(top.getTeamId());
            if (team != null) {
                leader = team.getColor() + team.getDisplayName() + ChatColor.RESET
                       + ChatColor.GRAY + ": " + top.getCompletedSquareCount() + "/25  ";
            }
        }
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                leader + "&8| &b" + String.format("%02d:%02d", min, sec)));
        if (top != null) {
            bossBar.setProgress(Math.max(0, Math.min(1,
                    (double) top.getCompletedSquareCount() / BingoCard.TOTAL)));
        }
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    // ----------------------------------------------------------------

    public State                       getState()             { return state; }
    public boolean                     isActive()             { return state == State.ACTIVE; }
    public BingoCard                   getCurrentCard()       { return currentCard; }
    public TeamCardState               getTeamState(String t) { return teamStates.get(t); }
    public Map<String, TeamCardState>  getAllTeamStates()     { return Collections.unmodifiableMap(teamStates); }
    public Set<UUID>                   getParticipants()      { return Collections.unmodifiableSet(participants); }

    /**
     * Reads a tiered placement value from config. Falls back to
     * "{section}.default" if the specific placement key is absent.
     */
    private int readPlacement(String section, int placement) {
        int explicit = plugin.getConfig().getInt(section + "." + placement, -1);
        if (explicit >= 0) return explicit;
        return plugin.getConfig().getInt(section + ".default", 0);
    }
}
