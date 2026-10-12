package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.director.PresetManager;
import nl.kmc.kmccore.director.TimeBudget;
import nl.kmc.kmccore.managers.AutomationManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The event director's panel ({@code /kmcdirector}): see where the tournament is and steer it live —
 * skip, pause, rerun, round, finale, emergency stop — without typing commands.
 */
public final class DirectorGui extends Gui {

    private static final int SLOT_GAMES = 29;

    private final KMCCore plugin;
    /** Two-step confirmation for destructive actions (rerun, stop). */
    private String confirming;

    public DirectorGui(KMCCore plugin) {
        super("&1&lKMC Regie — Director", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();

        var am = plugin.getAutomationManager();
        var tm = plugin.getTournamentManager();
        var dir = plugin.getDirector();
        boolean idle = am.getState() == AutomationManager.State.IDLE;

        // ── Status ──────────────────────────────────────────────────────────
        var game = plugin.getGameManager().getActiveGame();
        var est = TimeBudget.forCurrent(plugin, am.getTotalGames());
        set(4, item(Material.NETHER_STAR, "&6&lStatus: &e" + am.getState().name()
                        + (am.isFinalePhase() ? " &6(FINALE)" : ""),
                "&7Ronde: &e" + tm.getCurrentRound() + "&7/&e" + tm.getTotalRounds()
                        + " &7| Multiplier: &e×" + tm.getMultiplier(),
                "&7Games: &e" + am.getGamesPlayedTotal() + "&7/&e" + am.getTotalGames(),
                "&7Actieve game: &e" + (game != null ? game.getDisplayName() : "geen"),
                "&7Geschatte totale duur: &e" + est.format(),
                am.isPauseQueued() ? "&e⏸ Pauze staat klaar na deze game" : "",
                dir.isFrozen() ? "&c⏸ Noodpauze actief" : ""));

        // ── Flow ────────────────────────────────────────────────────────────
        button(10, item(Material.FEATHER, "&a&lSkip wachttijd",
                        "&7Slaat de huidige pauze, stemronde,",
                        "&7aftelling of ready-check over."),
                p -> {
                    String what = am.skipWait();
                    p.sendMessage(what != null ? "§a[KMC] Overgeslagen: " + what : "§7[KMC] Er is niets om over te slaan.");
                    refresh(p);
                });

        button(12, item(Material.REDSTONE, "&e&lSkip game (geen punten)",
                        "&7Beëindigt de huidige game zonder uitslag",
                        "&7en gaat door naar de volgende.",
                        confirming("skipgame")),
                p -> confirmThen(p, "skipgame", () -> {
                    if (!plugin.getGameManager().forceSkipCurrentGame())
                        p.sendMessage("§c[KMC] Er is geen actieve game.");
                }));

        String rerun = am.getRerunnableGameId();
        button(14, item(Material.CLOCK, "&b&lRerun laatste game",
                        "&7Draait punten en stand terug naar vlak",
                        "&7vóór de game en speelt hem opnieuw.",
                        rerun != null ? "&7Game: &e" + rerun : "&cNog geen game gestart",
                        confirming("rerun")),
                p -> confirmThen(p, "rerun", () -> {
                    String err = am.rerunLastGame();
                    if (err != null) p.sendMessage("§c[KMC] " + err);
                }));

        boolean paused = am.getState() == AutomationManager.State.PAUSED;
        button(16, item(paused ? Material.LIME_DYE : Material.ORANGE_DYE,
                        paused ? "&a&l▶ Hervat toernooi" : "&6&l⏸ Pauzeer toernooi",
                        paused ? "&7Het toernooi staat stil." : "&7Pauzeert nu (tussen games) of direct na deze game."),
                p -> {
                    if (paused) am.resume(); else am.requestPause();
                    refresh(p);
                });

        button(20, item(dir.isFrozen() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                        dir.isFrozen() ? "&a&l▶ Noodpauze opheffen" : "&c&l⏸ Noodpauze (iedereen stil)",
                        "&7Bevriest alle spelers ter plekke.",
                        "&8Let op: de timers van een lopende game lopen door."),
                p -> { dir.setFrozen(!dir.isFrozen()); refresh(p); });

        button(22, item(Material.EXPERIENCE_BOTTLE, "&d&lRonde: &e" + tm.getCurrentRound() + "&7/&e" + tm.getTotalRounds(),
                        "&7Multiplier nu: &e×" + tm.getMultiplier(),
                        "",
                        "&aLinks&7: ronde +1   &cRechts&7: ronde -1"),
                null);

        boolean finale = plugin.getConfig().getBoolean("automation.finale.enabled", true);
        button(24, item(finale ? Material.LIME_DYE : Material.GRAY_DYE,
                        "&6&lFinale: " + (finale ? "&aaan" : "&7uit"),
                        "&7De top 2 teams spelen na de laatste ronde",
                        "&7een beslissende game (&e"
                                + plugin.getConfig().getString("automation.finale.game", "dodgebolt") + "&7).",
                        "&eKlik om te wisselen"),
                p -> {
                    plugin.getConfig().set("automation.finale.enabled", !finale);
                    plugin.saveConfig();
                    refresh(p);
                });

        // ── Setup (only before the start) ────────────────────────────────────
        if (idle) {
            set(SLOT_GAMES, item(Material.PAPER, "&f&lAantal games: &e" + am.getTotalGames(),
                    "&7Rondes: &e" + PresetManager.roundsFor(am.getTotalGames(),
                            Math.max(1, plugin.getConfig().getInt("automation.games-per-round", 2))),
                    "&7Geschatte duur: &e" + est.format(),
                    "",
                    "&aLinks&7: +1   &cRechts&7: -1"));

            List<String> names = plugin.getDirector().presets().names();
            for (int i = 0; i < Math.min(9, names.size()); i++) {
                String name = names.get(i);
                List<String> lore = new ArrayList<>();
                for (String line : plugin.getDirector().presets().describe(name)) lore.add(line);
                lore.add("");
                lore.add("&eKlik om toe te passen");
                button(36 + i, item(Material.BOOK, "&b&lPreset: &f" + name, lore.toArray(new String[0])),
                        p -> {
                            List<String> report = new ArrayList<>();
                            plugin.getDirector().presets().apply(name, report);
                            report.forEach(l -> p.sendMessage(nl.kmc.kmccore.util.MessageUtil.color("&6[KMC] " + l)));
                            refresh(p);
                        });
            }
        } else {
            set(SLOT_GAMES, item(Material.PAPER, "&f&lAantal games: &e" + am.getTotalGames(),
                    "&8Alleen aan te passen vóór de start."));
        }

        // ── Checklist / stop ────────────────────────────────────────────────
        button(45, item(Material.WRITABLE_BOOK, "&a&lEvent-checklist",
                        "&7Is alles klaar? Arena's, teams,",
                        "&7finale en dry-runs."),
                p -> new ValidationCenterGui(plugin).open(p));

        button(49, item(Material.TNT, "&4&lNOODSTOP automatisering",
                        "&7Stopt de automatisering en de lopende game",
                        "&7en brengt iedereen terug naar de lobby.",
                        confirming("stop")),
                p -> confirmThen(p, "stop", () -> {
                    am.stop();
                    p.sendMessage("§c[KMC] Automatisering gestopt.");
                }));

        button(53, item(Material.BARRIER, "&c&lSluiten"), Player::closeInventory);

        fillEmpty();
    }

    private String confirming(String key) {
        return key.equals(confirming) ? "&c&lKlik nogmaals om te bevestigen!" : "&8(vraagt om bevestiging)";
    }

    private void confirmThen(Player p, String key, Runnable action) {
        if (key.equals(confirming)) {
            confirming = null;
            action.run();
            refresh(p);
        } else {
            confirming = key;
            refresh(p);
        }
    }

    private void refresh(Player p) {
        render();
        p.updateInventory();
    }

    @Override
    public void handleClick(Player p, int slot, boolean rightClick) {
        var tm = plugin.getTournamentManager();
        if (slot == 22) {
            int next = tm.getCurrentRound() + (rightClick ? -1 : 1);
            if (!tm.setRound(next)) p.sendMessage("§c[KMC] Ronde buiten bereik (1-" + tm.getTotalRounds() + ").");
            refresh(p);
            return;
        }
        if (slot == SLOT_GAMES && plugin.getAutomationManager().getState() == AutomationManager.State.IDLE) {
            int n = Math.max(1, Math.min(40, plugin.getAutomationManager().getTotalGames() + (rightClick ? -1 : 1)));
            plugin.getDirector().presets().applyGameCount(n);
            refresh(p);
            return;
        }
        super.handleClick(p, slot, rightClick);
    }
}
