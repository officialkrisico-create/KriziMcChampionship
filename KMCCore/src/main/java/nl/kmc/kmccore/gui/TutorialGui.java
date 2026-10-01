package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * /tutorial — the KMC tutorial hub. A wegwijzer (signpost) over the whole
 * tournament: it explains every meta-system in plain language and links
 * straight into the real GUI for each one, rather than duplicating their
 * data. Per-game rules stay in {@link HelpGui} — this menu is about how the
 * tournament as a whole works (teams, points, votes, achievements, records).
 */
public final class TutorialGui extends Gui {

    private final KMCCore plugin;

    private record Topic(Material icon, String name, String[] lore, Consumer<Player> onClick) {}

    public TutorialGui(KMCCore plugin) {
        super("&1&lKMC Tutorial", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        set(4, item(Material.WRITABLE_BOOK, "&6&lWelkom bij KMC!",
                "&7Dit is je startpunt: hier leer je hoe",
                "&7het toernooi, de punten en alle",
                "&7bijzondere systemen werken.",
                "",
                "&eKlik op een onderwerp voor uitleg."));

        List<Topic> topics = new ArrayList<>();
        topics.add(new Topic(Material.WHITE_BANNER, "&e&lBasis & Teams",
                new String[]{"&7Hoe het toernooi werkt", "&7en hoe teams werken."},
                this::openBasics));
        topics.add(new Topic(Material.GOLD_INGOT, "&6&lPuntensysteem",
                new String[]{"&7Hoe je punten scoort", "&7voor jezelf én je team."},
                this::openPoints));
        topics.add(new Topic(Material.NETHER_STAR, "&d&lSpeciale momenten",
                new String[]{"&7Golden Hour, comeback-bonus", "&7en Blood Moon uitgelegd."},
                this::openSpecials));
        topics.add(new Topic(Material.PAPER, "&b&lSpellen",
                new String[]{"&7Elke mini-game los uitgelegd:", "&7doel, spelregels en tips."},
                p -> new HelpGui(plugin).open(p)));
        topics.add(new Topic(Material.CLOCK, "&a&lStemmen & rondes",
                new String[]{"&7Hoe de volgende game", "&7gekozen wordt."},
                this::openVoting));
        topics.add(new Topic(Material.EXPERIENCE_BOTTLE, "&a&lAchievements & Records",
                new String[]{"&7Verborgen prestaties en", "&7eeuwige records."},
                this::openAchievements));
        topics.add(new Topic(Material.PLAYER_HEAD, "&b&lJouw voortgang",
                new String[]{"&7Profiel, MVP en momentum", "&7— jouw eigen statistieken."},
                this::openProgress));
        topics.add(new Topic(Material.BOOK, "&f&lTaal / Language",
                new String[]{"&7Kies je eigen taal voor", "&7alle berichten en menu's."},
                this::openLanguage));

        // Same centred 7-wide grid convention as HelpGui.
        int idx = 0;
        for (Topic t : topics) {
            int slot = (2 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 45) break;
            List<String> lore = new ArrayList<>(List.of(t.lore()));
            lore.add("");
            lore.add("&eKlik voor uitleg");
            button(slot, item(t.icon(), t.name(), lore.toArray(new String[0])), t.onClick());
            idx++;
        }

        button(49, item(Material.BARRIER, "&c&lSluiten"), Player::closeInventory);
        fillEmpty();
    }

    // ── Topic pages ──────────────────────────────────────────────────────────

    private void openBasics(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.WHITE_BANNER, "&e&lWat is KMC?",
                "Teams strijden door meerdere mini-games heen om de meeste punten. "
                        + "Het team met de meeste punten aan het eind wint het hele toernooi."));
        cards.add(cardOf(Material.LEATHER_CHESTPLATE, "&e&lTeams",
                "Je zit altijd in een team. Teampunten zijn de som van alle "
                        + "individuele punten van jouw teamgenoten. Gebruik /kmcteam om je team te zien."));
        cards.add(cardOf(Material.CLOCK, "&e&lRondes",
                "Het toernooi bestaat uit meerdere rondes. In elke ronde wordt via "
                        + "stemmen een mini-game gekozen die iedereen samen speelt."));

        List<TutorialTopicGui.Link> links = new ArrayList<>();
        links.add(new TutorialTopicGui.Link(
                item(Material.MAP, "&a&lLive stand bekijken", "&7Opent /kmcstandings"),
                p -> new StandingsGui(plugin, p.getUniqueId()).open(p)));

        new TutorialTopicGui(plugin, "&1&lBasis & Teams", cards, links).open(viewer);
    }

    private void openPoints(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.GOLD_INGOT, "&6&lPunten scoren",
                "Je verdient punten door een mini-game goed te spelen: hoe beter je "
                        + "plaatst (of hoe meer kills/doelen je haalt), hoe meer punten."));
        cards.add(cardOf(Material.CLOCK, "&6&lRonde-multiplier",
                "Latere rondes zijn zwaarder: dezelfde prestatie levert in een latere "
                        + "ronde meer punten op dan in de eerste ronde."));
        cards.add(cardOf(Material.PLAYER_HEAD, "&6&lJij én je team",
                "Elk punt dat jij scoort telt zowel voor jouw eigen profiel als voor "
                        + "de totaalscore van je team."));

        List<TutorialTopicGui.Link> links = new ArrayList<>();
        links.add(new TutorialTopicGui.Link(
                item(Material.NETHERITE_PICKAXE, "&a&lJouw profiel", "&7Opent /kmcprofile"),
                p -> new ProfileGui(plugin, p.getUniqueId(), p.getName()).open(p)));
        links.add(new TutorialTopicGui.Link(
                item(Material.DIAMOND, "&a&lTeam power ranking", "&7Opent /kmcpowerrank"),
                p -> new PowerRankGui(plugin).open(p)));

        new TutorialTopicGui(plugin, "&1&lPuntensysteem", cards, links).open(viewer);
    }

    private void openSpecials(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.SUNFLOWER, "&d&lGolden Hour",
                "Eén willekeurige ronde per toernooi is een Golden Hour: dubbele punten "
                        + "voor iedereen. Je komt er pas vlak voor die game achter welke ronde dit is."));
        cards.add(cardOf(Material.TOTEM_OF_UNDYING, "&d&lComeback-bonus",
                "Staat jouw team flink achter in de stand? Dan krijg je een kleine "
                        + "extra puntenboost om het spannend te houden."));
        cards.add(cardOf(Material.REDSTONE, "&d&lBlood Moon",
                "Een speciale chaos-modifier in Mob Mayhem: extra en sterkere mobs "
                        + "voor een korte, heftige piek in het gevecht."));

        new TutorialTopicGui(plugin, "&1&lSpeciale momenten", cards, List.of()).open(viewer);
    }

    private void openVoting(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.PAPER, "&a&lStemmen",
                "Voor elke ronde opent een stemming met alle mini-games die dit "
                        + "toernooi nog niet gespeeld zijn. Gebruik /kmcvote om je stem uit te brengen."));
        cards.add(cardOf(Material.CLOCK, "&a&lGeen stem?",
                "Niet gestemd of de stemming loopt af zonder winnaar? Dan kiest het "
                        + "systeem automatisch willekeurig uit de resterende games."));
        cards.add(cardOf(Material.REPEATING_COMMAND_BLOCK, "&a&lGeen herhaling",
                "Een mini-game die dit toernooi al gespeeld is, komt niet meer terug "
                        + "in de stemming — zo speelt iedereen een zo gevarieerd mogelijk toernooi."));

        List<TutorialTopicGui.Link> links = new ArrayList<>();
        links.add(new TutorialTopicGui.Link(
                item(Material.ENCHANTED_BOOK, "&a&lAlle spellen bekijken", "&7Opent /kmchelp"),
                p -> new HelpGui(plugin).open(p)));

        new TutorialTopicGui(plugin, "&1&lStemmen & rondes", cards, links).open(viewer);
    }

    private void openAchievements(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.EXPERIENCE_BOTTLE, "&a&lAchievements",
                "Verspreid over het hele toernooi zitten verborgen en zichtbare "
                        + "prestaties, in drie zeldzaamheden: algemeen, zeldzaam en legendarisch. "
                        + "Ze ontgrendelen automatisch zodra je eraan voldoet."));
        cards.add(cardOf(Material.GOLD_INGOT, "&a&lMedailles",
                "Eindig bij de top 3 van een mini-game en je verdient een medaille: "
                        + "goud, zilver of brons. Deze tellen op in je medaillekast."));
        cards.add(cardOf(Material.BEACON, "&a&lHall of Fame",
                "De eeuwige records van de server: meeste kills, meeste punten en "
                        + "meeste overwinningen — per toernooi én aller tijden."));

        List<TutorialTopicGui.Link> links = new ArrayList<>();
        links.add(new TutorialTopicGui.Link(
                item(Material.PLAYER_HEAD, "&a&lJouw profiel", "&7Opent /kmcprofile — toont je achievement-voortgang"),
                p -> new ProfileGui(plugin, p.getUniqueId(), p.getName()).open(p)));
        links.add(new TutorialTopicGui.Link(
                item(Material.BEACON, "&a&lHall of Fame", "&7Opent /kmchof"),
                p -> new HallOfFameGui(plugin).open(p)));
        links.add(new TutorialTopicGui.Link(
                item(Material.GOLD_INGOT, "&a&lMedaillekast", "&7Opent /kmcmedals"),
                p -> new MedalsGui(plugin, p.getUniqueId()).open(p)));

        new TutorialTopicGui(plugin, "&1&lAchievements & Records", cards, links).open(viewer);
    }

    private void openProgress(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.NETHERITE_PICKAXE, "&b&lProfiel",
                "Je eigen overzicht: punten, kills/deaths, winstreak, favoriete "
                        + "game en speeltijd. Open met /kmcprofile."));
        cards.add(cardOf(Material.GOLDEN_APPLE, "&b&lMVP",
                "De beste speler per mini-game, zowel voor dit toernooi als aller "
                        + "tijden. Open met /kmcmvp."));
        cards.add(cardOf(Material.FIREWORK_ROCKET, "&b&lMomentum",
                "Wie stijgt of daalt het hardst in de stand, en welk team zit in "
                        + "een hot streak? Open met /kmcmomentum."));

        List<TutorialTopicGui.Link> links = new ArrayList<>();
        links.add(new TutorialTopicGui.Link(
                item(Material.NETHERITE_PICKAXE, "&a&lProfiel", "&7Opent /kmcprofile"),
                p -> new ProfileGui(plugin, p.getUniqueId(), p.getName()).open(p)));
        links.add(new TutorialTopicGui.Link(
                item(Material.GOLDEN_APPLE, "&a&lMVP", "&7Opent /kmcmvp"),
                p -> new MvpGui(plugin, p.getUniqueId()).open(p)));
        links.add(new TutorialTopicGui.Link(
                item(Material.FIREWORK_ROCKET, "&a&lMomentum", "&7Opent /kmcmomentum"),
                p -> new MomentumGui(plugin).open(p)));

        new TutorialTopicGui(plugin, "&1&lJouw voortgang", cards, links).open(viewer);
    }

    private void openLanguage(Player viewer) {
        List<ItemStack> cards = new ArrayList<>();
        cards.add(cardOf(Material.BOOK, "&f&lJe eigen taal kiezen",
                "Met /kmclanguage (ook bereikbaar via /taal of /kmclang) kies je zelf "
                        + "in welke taal je alle KMC-berichten, scoreboard en tab-lijst te zien krijgt. "
                        + "Elke speler kiest zijn eigen taal — het verandert niets voor anderen."));

        List<TutorialTopicGui.Link> links = new ArrayList<>();
        links.add(new TutorialTopicGui.Link(
                item(Material.BOOK, "&a&lKies je taal", "&7Opent /kmclanguage"),
                p -> new LanguageGui(plugin, p).open(p)));

        new TutorialTopicGui(plugin, "&1&lTaal / Language", cards, links).open(viewer);
    }

    private ItemStack cardOf(Material icon, String title, String text) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.addAll(TutorialTopicGui.wrap(text, 34, "&7"));
        return item(icon, title, lore.toArray(new String[0]));
    }
}
