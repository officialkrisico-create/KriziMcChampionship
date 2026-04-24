package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /kmcarena <setlobby|setorigin|setteamspawn|addsolospawn|clearsolospawns|status|paste|reset> [args...]
 *
 * Workflow to set up a new game arena:
 * 1. Drop schematic file in plugins/KMCCore/schematics/
 * 2. Add to config.yml:  games.list.<gameId>.schematic: filename.schem
 * 3. Stand at desired paste location, run:  /kmcarena setorigin <gameId>
 * 4. Stand at each team's spawn, run:       /kmcarena setteamspawn <gameId> <teamId>
 *    OR for FFA games, stand at each spawn: /kmcarena addsolospawn <gameId>
 * 5. Verify with:                           /kmcarena status <gameId>
 * 6. Test paste:                            /kmcarena paste <gameId>
 */
public class ArenaCommand implements CommandExecutor, TabCompleter {

    private final KMCCore plugin;

    public ArenaCommand(KMCCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("kmc.arena.admin")) {
            sender.sendMessage(MessageUtil.get("no-permission"));
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        switch (args[0].toLowerCase()) {

            case "setlobby" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                plugin.getArenaManager().setLobby(p.getLocation());
                sender.sendMessage(MessageUtil.color("&a✔ Lobby ingesteld op jouw huidige locatie."));
            }

            case "setorigin" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena setorigin <gameId>")); return true; }
                String gameId = args[1].toLowerCase();
                if (plugin.getGameManager().getGame(gameId) == null) {
                    sender.sendMessage(MessageUtil.color("&cGame '" + gameId + "' bestaat niet."));
                    return true;
                }
                plugin.getSchematicManager().setOriginForGame(gameId, p.getLocation());
                sender.sendMessage(MessageUtil.color("&a✔ Arena origin voor '" + gameId + "' ingesteld."));
                sender.sendMessage(MessageUtil.color("&7Tip: &e/kmcarena paste " + gameId + " &7om te testen."));
            }

            case "setteamspawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 3) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena setteamspawn <gameId> <teamId>")); return true; }
                String gameId = args[1].toLowerCase();
                String teamId = args[2].toLowerCase();
                if (plugin.getGameManager().getGame(gameId) == null) {
                    sender.sendMessage(MessageUtil.color("&cGame bestaat niet.")); return true;
                }
                if (plugin.getTeamManager().getTeam(teamId) == null) {
                    sender.sendMessage(MessageUtil.color("&cTeam bestaat niet.")); return true;
                }
                plugin.getArenaManager().setTeamSpawn(gameId, teamId, p.getLocation());
                sender.sendMessage(MessageUtil.color("&a✔ Spawn voor team &e" + teamId + " &ain &e" + gameId + " &aingesteld."));
            }

            case "addsolospawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Alleen spelers."); return true; }
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena addsolospawn <gameId>")); return true; }
                String gameId = args[1].toLowerCase();
                if (plugin.getGameManager().getGame(gameId) == null) {
                    sender.sendMessage(MessageUtil.color("&cGame bestaat niet.")); return true;
                }
                plugin.getArenaManager().addSoloSpawn(gameId, p.getLocation());
                int count = plugin.getArenaManager().getSoloSpawns(gameId).size();
                sender.sendMessage(MessageUtil.color("&a✔ Solo spawn #" + count + " toegevoegd aan " + gameId + "."));
            }

            case "clearsolospawns" -> {
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena clearsolospawns <gameId>")); return true; }
                plugin.getArenaManager().clearSoloSpawns(args[1].toLowerCase());
                sender.sendMessage(MessageUtil.color("&a✔ Solo spawns gewist."));
            }

            case "status" -> {
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena status <gameId>")); return true; }
                String gameId = args[1].toLowerCase();
                sender.sendMessage(MessageUtil.color("&6═══ Arena status: " + gameId + " ═══"));
                for (String line : plugin.getArenaManager().getStatusReport(gameId).split("\n")) {
                    sender.sendMessage(MessageUtil.color("&7" + line));
                }
            }

            case "paste" -> {
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena paste <gameId>")); return true; }
                String gameId = args[1].toLowerCase();
                if (!plugin.getSchematicManager().isWorldEditAvailable()) {
                    sender.sendMessage(MessageUtil.color("&cWorldEdit is niet geïnstalleerd!"));
                    return true;
                }
                sender.sendMessage(MessageUtil.color("&ePaste bezig..."));
                boolean ok = plugin.getArenaManager().loadArenaForGame(gameId);
                sender.sendMessage(ok
                        ? MessageUtil.color("&a✔ Arena geplakt.")
                        : MessageUtil.color("&c✘ Paste mislukt. Zie console voor details."));
            }

            case "reset" -> {
                if (args.length < 2) { sender.sendMessage(MessageUtil.color("&cGebruik: /kmcarena reset <gameId>")); return true; }
                String gameId = args[1].toLowerCase();
                boolean ok = plugin.getArenaManager().resetArenaForGame(gameId);
                sender.sendMessage(ok
                        ? MessageUtil.color("&a✔ Arena gereset.")
                        : MessageUtil.color("&c✘ Reset mislukt."));
            }

            default -> usage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] args) {
        if (args.length == 1)
            return List.of("setlobby","setorigin","setteamspawn","addsolospawn",
                           "clearsolospawns","status","paste","reset")
                    .stream().filter(o -> o.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2 && !args[0].equalsIgnoreCase("setlobby"))
            return plugin.getGameManager().getAllGames().stream()
                    .map(g -> g.getId())
                    .filter(id -> id.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("setteamspawn"))
            return plugin.getTeamManager().getAllTeams().stream()
                    .map(KMCTeam::getId)
                    .filter(id -> id.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }

    private void usage(CommandSender s) {
        s.sendMessage(MessageUtil.color("&cGebruik: /kmcarena <setlobby|setorigin|setteamspawn|addsolospawn|clearsolospawns|status|paste|reset> [args...]"));
    }
}
