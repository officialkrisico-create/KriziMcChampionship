package nl.kmc.speedbuild.util;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Small, safe teleport + reset helpers for stage transitions. */
public final class PlayerTeleportUtil {

    private PlayerTeleportUtil() {}

    public static void toStage(Player player, Location standOn) {
        player.setFallDistance(0);
        player.teleport(standOn);
        player.setGameMode(GameMode.SURVIVAL);
    }

    public static void toSpawn(Player player, Location spawn) {
        player.setFallDistance(0);
        if (spawn != null) player.teleport(spawn);
        player.setGameMode(GameMode.ADVENTURE);
    }
}
