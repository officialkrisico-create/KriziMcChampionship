package nl.kmc.kmccore.commands;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.SetupDashboardGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /kmcsetup — opens the unified Setup Dashboard GUI. */
public final class SetupCommand implements CommandExecutor {

    private final KMCCore plugin;

    public SetupCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("kmc.arena.admin") && !sender.hasPermission("kmc.admin")) {
            sender.sendMessage("§cJe hebt geen toestemming hiervoor.");
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cAlleen spelers kunnen de setup GUI openen.");
            return true;
        }
        new SetupDashboardGui(plugin).open(p);
        return true;
    }
}
