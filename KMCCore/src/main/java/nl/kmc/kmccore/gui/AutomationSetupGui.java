package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pre-start menu opened by {@code /kmcauto start}: toggle which games are in
 * this tournament's rotation, tune the intermission/voting timers, optionally
 * schedule the start for later, and finally launch — all before the ceremony
 * actually begins.
 */
public final class AutomationSetupGui extends Gui {

    private static final int SLOT_INTERMISSION  = 38;
    private static final int SLOT_VOTE_DURATION = 40;
    private static final int SLOT_GAMES         = 36;
    private static final int SLOT_FINALE        = 37;
    private static final int SLOT_MIDBREAK      = 42;
    private static final int SLOT_READY         = 43;
    private static final int SLOT_PRESETS       = 44;

    private final KMCCore plugin;
    /** Which game sits in which grid slot (for the right-click "surprise" toggle). */
    private final java.util.Map<Integer, String> slotGames = new java.util.HashMap<>();
    /** Called (with the inventory already closed) when "Start Tournament Now" is clicked. */
    private final Consumer<Player> onStartNow;

    public AutomationSetupGui(KMCCore plugin, Consumer<Player> onStartNow) {
        super("&1&lToernooi — Setup", 6);
        this.plugin     = plugin;
        this.onStartNow = onStartNow;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();
        slotGames.clear();

        List<KMCGame> games = new ArrayList<>(plugin.getGameManager().getAllGames());
        games.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        long enabledCount = games.stream().filter(g -> isEnabled(g.getId())).count();

        set(4, item(Material.NETHER_STAR, "&6&lKMC Automatisering — Setup",
                "&7Pas hieronder aan wat je wilt vóór je start.",
                "&7Games in rotatie: &e" + enabledCount + "&7/&e" + games.size()));

        // Game toggle grid — 7-wide, rows 1-3 (same layout as GameRepetitionsGui).
        int idx = 0;
        for (KMCGame game : games) {
            int slot = (1 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 36) break;
            renderGameToggle(slot, game);
            idx++;
        }

        renderTimers();
        renderEventOptions();
        renderScheduleAndStart();

        fillEmpty();
    }

    // ── Games count, finale, breaks, presets ───────────────────────────────────

    private void renderEventOptions() {
        var am = plugin.getAutomationManager();
        var cfg = plugin.getConfig();
        int games = am.getTotalGames();
        int gpr = Math.max(1, cfg.getInt("automation.games-per-round", 2));
        var est = nl.kmc.kmccore.director.TimeBudget.forCurrent(plugin, games);
        long enabled = plugin.getGameManager().getEnabledGames().size();

        set(SLOT_GAMES, item(Material.PAPER, "&f&lAantal games dit toernooi: &e" + games,
                "&7Rondes: &e" + nl.kmc.kmccore.director.PresetManager.roundsFor(games, gpr)
                        + " &7(" + gpr + " games per ronde)",
                "&7In de rotatie: &e" + enabled + " &7games" + (games > enabled ? " &c(te weinig!)" : ""),
                "&7Geschatte duur: &e" + est.format(),
                "",
                "&aLinks&7: +1   &cRechts&7: -1"));

        boolean finale = cfg.getBoolean("automation.finale.enabled", true);
        button(SLOT_FINALE, item(finale ? Material.LIME_DYE : Material.GRAY_DYE,
                        "&6&lFinale: " + (finale ? "&aaan" : "&7uit"),
                        "&7De top 2 teams spelen na de laatste ronde",
                        "&7een beslissende game.",
                        "&eKlik om te wisselen"),
                p -> {
                    cfg.set("automation.finale.enabled", !finale);
                    plugin.saveConfig();
                    render();
                    p.updateInventory();
                });

        boolean mid = cfg.getBoolean("automation.mid-break.enabled", true);
        button(SLOT_MIDBREAK, item(mid ? Material.CLOCK : Material.GRAY_DYE,
                        "&6&lHalverwege-pauze: " + (mid ? "&a" + cfg.getInt("automation.mid-break.seconds", 300) + "s" : "&7uit"),
                        "&7Eén lange pauze halverwege het toernooi.",
                        "&eKlik om te wisselen"),
                p -> {
                    cfg.set("automation.mid-break.enabled", !mid);
                    plugin.saveConfig();
                    render();
                    p.updateInventory();
                });

        String mode = cfg.getString("automation.ready-check.mode", "mid-break").toLowerCase();
        button(SLOT_READY, item(mode.equals("off") ? Material.GRAY_DYE : Material.LIME_CONCRETE,
                        "&6&lReady-check: &e" + mode,
                        "&7Na een pauze moet elk team bevestigen",
                        "&7dat het klaar is (klikbaar bericht).",
                        "&8off → mid-break → every-break",
                        "&eKlik om te wisselen"),
                p -> {
                    String next = switch (mode) {
                        case "off" -> "mid-break";
                        case "mid-break" -> "every-break";
                        default -> "off";
                    };
                    cfg.set("automation.ready-check.mode", next);
                    plugin.saveConfig();
                    render();
                    p.updateInventory();
                });

        button(SLOT_PRESETS, item(Material.BOOK, "&b&lPresets",
                        "&7Standaard, kort, speciaal of test:",
                        "&7zet alles in één keer.",
                        "&eKlik om te kiezen"),
                p -> new PresetPickerGui(plugin, onStartNow).open(p));
    }

    // ── Game toggles ────────────────────────────────────────────────────────

    private boolean isEnabled(String gameId) {
        return plugin.getConfig().getBoolean("games.list." + gameId + ".enabled", true);
    }

    private void renderGameToggle(int slot, KMCGame game) {
        boolean on = isEnabled(game.getId());
        boolean surprise = plugin.getGameManager().isSurprise(game.getId());
        slotGames.put(slot, game.getId());
        Material icon = on ? (game.getIcon() != null ? game.getIcon() : Material.PAPER) : Material.GRAY_DYE;
        button(slot, item(icon,
                        (on ? "&a&l✓ " : "&7&m") + game.getDisplayName(),
                        on ? "&7Zit in de rotatie dit toernooi." : "&7Overgeslagen dit toernooi.",
                        surprise ? "&d✦ Verrassingsgame — verborgen tot hij gekozen wordt" : "&8Geen verrassing",
                        "",
                        "&eLinks: &7aan/uit  &dRechts: &7verrassing aan/uit"),
                p -> {
                    plugin.getConfig().set("games.list." + game.getId() + ".enabled", !on);
                    plugin.saveConfig();
                    render();
                    p.updateInventory();
                });
    }

    // ── Timers ────────────────────────────────────────────────────────────────

    private void renderTimers() {
        int intermission = plugin.getConfig().getInt("automation.intermission-seconds", 30);
        set(SLOT_INTERMISSION, item(Material.CLOCK, "&f&lTussenpauze",
                "&7Tijd tussen games, voor het stemmen begint.",
                "&7Nu: &e" + intermission + "s",
                "",
                "&aLinks&7: +5s   &cRechts&7: -5s"));

        boolean votingOn = plugin.getConfig().getBoolean("games.voting-enabled", true);
        button(41, item(votingOn ? Material.LIME_DYE : Material.GRAY_DYE,
                        "&f&lStemmen op volgende game",
                        votingOn ? "&aAAN &7— spelers stemmen elke ronde." : "&7UIT &7— willekeurige game, geen stemvenster.",
                        "",
                        "&eKlik om te wisselen"),
                p -> {
                    plugin.getConfig().set("games.voting-enabled", !votingOn);
                    plugin.saveConfig();
                    render();
                    p.updateInventory();
                });

        int voteDuration = plugin.getConfig().getInt("games.voting-duration", 30);
        set(SLOT_VOTE_DURATION, item(Material.CLOCK, "&f&lStemduur",
                "&7Hoe lang de stem-GUI open staat.",
                "&7Nu: &e" + voteDuration + "s",
                "",
                "&aLinks&7: +5s   &cRechts&7: -5s"));
    }

    private void adjustIntermission(int delta) {
        int cur  = plugin.getConfig().getInt("automation.intermission-seconds", 30);
        int next = Math.max(5, Math.min(300, cur + delta));
        plugin.getConfig().set("automation.intermission-seconds", next);
        plugin.saveConfig();
    }

    private void adjustVoteDuration(int delta) {
        int cur  = plugin.getConfig().getInt("games.voting-duration", 30);
        int next = Math.max(5, Math.min(300, cur + delta));
        plugin.getConfig().set("games.voting-duration", next);
        plugin.saveConfig();
    }

    // ── Schedule + start ────────────────────────────────────────────────────

    private void renderScheduleAndStart() {
        boolean scheduled = plugin.getAutomationManager().hasScheduledStart();
        String scheduleLore = scheduled
                ? "&7Gepland over &e" + formatMs(plugin.getAutomationManager().scheduledStartInMs())
                : "&7Nog niet gepland — start meteen als je op start klikt.";

        button(47, item(Material.RECOVERY_COMPASS, "&f&lStart inplannen",
                        scheduleLore,
                        "",
                        "&eKlik: typ '20:00' of 'in 15' (minuten), of 'cancel'"),
                p -> plugin.getChatInput().await(p,
                        "Typ een kloktijd (bv. 20:00), 'in <minuten>' (bv. 'in 15'), of 'cancel':",
                        in -> { applySchedule(p, in); new AutomationSetupGui(plugin, onStartNow).open(p); }));

        button(49, item(Material.LIME_CONCRETE, "&a&l▶ START TOERNOOI NU",
                        "&7Begint direct met de instellingen hierboven."),
                p -> { p.closeInventory(); onStartNow.accept(p); });

        button(51, item(Material.BARRIER, "&c&lSluiten",
                        "&7Start niks — instellingen zijn al opgeslagen."),
                Player::closeInventory);
    }

    private void applySchedule(Player p, String input) {
        input = input.trim();
        if (input.equalsIgnoreCase("cancel")) {
            plugin.getAutomationManager().cancelScheduledStart();
            p.sendMessage("§a[KMC] Geplande start geannuleerd.");
            return;
        }
        if (input.toLowerCase().startsWith("in ")) {
            try {
                double minutes = Double.parseDouble(input.substring(3).trim());
                if (minutes <= 0) { p.sendMessage("§cMoet groter dan 0 zijn."); return; }
                plugin.getAutomationManager().scheduleStart((long) (minutes * 60 * 20));
                p.sendMessage("§a[KMC] Toernooi gepland over §e" + minutes + " minuten§a.");
            } catch (NumberFormatException e) {
                p.sendMessage("§cOngeldig getal.");
            }
            return;
        }
        try {
            String[] hm = input.split(":");
            java.time.LocalTime target = java.time.LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime when = now.toLocalDate().atTime(target);
            if (!when.isAfter(now)) when = when.plusDays(1); // already passed today -> tomorrow
            long delayMs = java.time.Duration.between(now, when).toMillis();
            plugin.getAutomationManager().scheduleStart(delayMs / 50L);
            p.sendMessage("§a[KMC] Toernooi gepland om §e" + when.toLocalTime() + "§a.");
        } catch (Exception e) {
            p.sendMessage("§cOngeldige invoer. Gebruik '20:00' of 'in 15'.");
        }
    }

    private static String formatMs(long ms) {
        long secs = Math.max(0, ms / 1000);
        return (secs / 60) + "m " + (secs % 60) + "s";
    }

    // ── Click routing (+/- on the two timer tiles, everything else is a plain button) ──

    @Override
    public void handleClick(Player p, int slot, boolean rightClick) {
        if (rightClick && slotGames.containsKey(slot)) {
            String id = slotGames.get(slot);
            plugin.getGameManager().setSurprise(id, !plugin.getGameManager().isSurprise(id));
            render();
            p.updateInventory();
            return;
        }
        if (slot == SLOT_INTERMISSION) { adjustIntermission(rightClick ? -5 : 5); render(); p.updateInventory(); return; }
        if (slot == SLOT_VOTE_DURATION) { adjustVoteDuration(rightClick ? -5 : 5); render(); p.updateInventory(); return; }
        if (slot == SLOT_GAMES) {
            int n = Math.max(1, Math.min(40, plugin.getAutomationManager().getTotalGames() + (rightClick ? -1 : 1)));
            plugin.getDirector().presets().applyGameCount(n);
            render();
            p.updateInventory();
            return;
        }
        super.handleClick(p, slot, rightClick);
    }
}
