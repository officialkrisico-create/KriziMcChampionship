package nl.kmc.kmccore.host;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /kmchost <info|spawn|setspawn|remove|skin|intro|announce|dialogue|test|skip|enable|disable|reload>},
 * also reachable as {@code /kmc host ...}. Only for {@code kmc.host.admin}. Nothing here touches scores,
 * games or the automation: the host can only speak and be placed.
 */
public final class HostCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = new ArrayList<>(List.of("info", "spawn", "setspawn", "remove", "skin", "intro",
            "announce", "dialogue", "test", "skip", "enable", "disable", "reload"));
    static { SUBS.addAll(HostManager.DEMOS); }

    private final KMCCore plugin;

    public HostCommand(KMCCore plugin) { this.plugin = plugin; }

    private static void msg(CommandSender s, String text) { s.sendMessage(MessageUtil.color(text)); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.host.admin")) {
            msg(sender, "&cJe hebt hier geen toestemming voor.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("kmc")) {
            if (args.length == 0 || !args[0].equalsIgnoreCase("host")) {
                msg(sender, "&7Gebruik: &e/kmc host <" + String.join("|", SUBS) + ">");
                return true;
            }
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        var host = plugin.getHost();
        if (host == null) { msg(sender, "&cDe host is niet beschikbaar."); return true; }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender, host);
            case "spawn" -> {
                var pm = plugin.getPresenter();
                var spot = pm.getSavedSpot();
                if (spot == null) { msg(sender, "&cDe host heeft nog geen plek. Gebruik &e/kmchost setspawn&c."); return true; }
                pm.setSpot(spot);
                msg(sender, "&a[KMC] Host geplaatst op de opgeslagen plek. &7Hij praat pas na &e/kmcauto start&7.");
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { msg(sender, "&cAlleen spelers."); return true; }
                plugin.getPresenter().setSpot(p.getLocation());
                msg(sender, "&a[KMC] Host staat nu op jouw positie. &7Hij praat pas na &e/kmcauto start&7.");
            }
            case "remove" -> msg(sender, plugin.getPresenter().remove() ? "&a[KMC] Host verwijderd." : "&7Er is geen host geplaatst.");
            case "skin" -> {
                if (args.length < 2) { msg(sender, "&cGebruik: /kmchost skin <spelernaam>"); return true; }
                plugin.getPresenter().setSkin(args[1]);
                msg(sender, "&a[KMC] Skin van de host: &e" + args[1]);
            }
            case "intro" -> {
                boolean ok = host.replayIntro(args.length >= 2 ? args[1] : null);
                msg(sender, ok ? "&a[KMC] Introductie wordt afgespeeld." : "&cDie introductie bestaat niet of de host staat uit.");
            }
            case "announce" -> {
                if (args.length < 2) { msg(sender, "&cGebruik: /kmchost announce <bericht>"); return true; }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                if (text.length() > 300) { msg(sender, "&cMaximaal 300 tekens."); return true; }
                msg(sender, host.announce(text) ? "&a[KMC] De host zegt het." : "&cDe host staat uit.");
            }
            case "dialogue" -> {
                if (args.length < 2) { msg(sender, "&cGebruik: /kmchost dialogue <naam>"); return true; }
                boolean ok = host.play(args[1], sender instanceof Player p ? p : null);
                msg(sender, ok ? "&a[KMC] Dialoog wordt afgespeeld." : "&cOnbekende dialoog '" + args[1] + "' (of niets te zeggen). &7/kmchost info toont er een paar.");
            }
            case "test" -> {
                boolean ok = host.test(sender instanceof Player p ? p : null);
                if (!ok) msg(sender, "&cDe host staat uit.");
                else if (!plugin.getPresenter().isPlaced()) msg(sender, "&eDe host is nog niet geplaatst: &7/kmchost setspawn. De regels staan nu alleen in je action bar.");
                else msg(sender, "&a[KMC] Testtekst boven het hoofd van de host (alleen voor jou). Er wordt niets aan scores of games veranderd.");
            }
            case "skip" -> msg(sender, host.skip() ? "&a[KMC] Huidige speech overgeslagen." : "&7De host zegt op dit moment niets.");
            case "enable" -> {
                host.setEnabled(true);
                msg(sender, "&a[KMC] Host ingeschakeld (tot de volgende herstart; zet host.enabled in config.yml voor altijd).");
            }
            case "disable" -> {
                host.setEnabled(false);
                msg(sender, "&e[KMC] Host uitgeschakeld (tot de volgende herstart; zet host.enabled in config.yml voor altijd).");
            }
            case "reload" -> {
                plugin.reloadConfig();
                host.reload();
                msg(sender, "&a[KMC] Host-configuratie en host.yml herladen. &7" + host.dialogueCount() + " dialogen, "
                        + host.contentWarnings().size() + " waarschuwingen.");
                host.contentWarnings().stream().limit(5).forEach(w -> msg(sender, "&e  ! &7" + w));
            }
            case "lore", "opening", "rules", "overview", "teams", "lineup", "results", "standings", "finale", "third",
                 "second", "champion", "awards", "podium" -> {
                boolean ok = host.demo(sub, sender instanceof Player p ? p : null);
                msg(sender, ok ? "&a[KMC] De host speelt &e" + sub + "&a (testdata waar echte data ontbreekt, camera en podium alleen als die er zijn). &7/kmchost skip stopt hem."
                        : "&cDat lukt niet: onbekend onderdeel, de dialoog ontbreekt in host.yml, of de host staat uit.");
            }
            default -> msg(sender, "&7Gebruik: &e/kmchost <" + String.join("|", SUBS) + ">");
        }
        return true;
    }

    private void info(CommandSender sender, HostManager host) {
        Map<String, String> s = host.status();
        msg(sender, "&6&lKMC Host &7— status: &f" + s.get("state") + " &8| &7KMCAuto: &f" + s.get("kmcauto"));
        msg(sender, "&7Geplaatst: &f" + s.get("placed") + " &8| &7zegt nu: &f" + s.get("speaking")
                + " &8| &7wachtrij: &f" + s.get("queued"));
        msg(sender, "&7Dialogen: &f" + s.get("dialogues") + " &8| &7waarschuwingen in host.yml: &f" + s.get("warnings"));
        msg(sender, "&7De host praat pas automatisch na &e/kmcauto start&7 en stopt zodra KMCAuto eindigt.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.host.admin")) return List.of();
        if (cmd.getName().equalsIgnoreCase("kmc")) {
            if (args.length == 1) return filter(List.of("host"), args[0]);
            if (!args[0].equalsIgnoreCase("host")) return List.of();
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length == 1) return filter(SUBS, args[0]);
        var host = plugin.getHost();
        if (host == null) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("dialogue")) return filter(host.dialogueIds(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("intro")) {
            List<String> ids = new ArrayList<>();
            for (var g : plugin.getGameManager().getEnabledGames()) ids.add(g.getId());
            return filter(ids, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
