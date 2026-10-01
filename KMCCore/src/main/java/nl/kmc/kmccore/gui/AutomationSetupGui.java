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

    private final KMCCore plugin;
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
        renderScheduleAndStart();

        fillEmpty();
    }

    // ── Game toggles ────────────────────────────────────────────────────────

    private boolean isEnabled(String gameId) {
        return plugin.getConfig().getBoolean("games.list." + gameId + ".enabled", true);
    }

    private void renderGameToggle(int slot, KMCGame game) {
        boolean on = isEnabled(game.getId());
        Material icon = on ? (game.getIcon() != null ? game.getIcon() : Material.PAPER) : Material.GRAY_DYE;
        button(slot, item(icon,
                        (on ? "&a&l✓ " : "&7&m") + game.getDisplayName(),
                        on ? "&7Zit in de rotatie dit toernooi." : "&7Overgeslagen dit toernooi.",
                        "",
                        "&eKlik om te wisselen"),
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
        if (slot == SLOT_INTERMISSION) { adjustIntermission(rightClick ? -5 : 5); render(); p.updateInventory(); return; }
        if (slot == SLOT_VOTE_DURATION) { adjustVoteDuration(rightClick ? -5 : 5); render(); p.updateInventory(); return; }
        super.handleClick(p, slot, rightClick);
    }
}
