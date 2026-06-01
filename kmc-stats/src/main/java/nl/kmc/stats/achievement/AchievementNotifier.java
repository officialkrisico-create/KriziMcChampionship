package nl.kmc.stats.achievement;

import nl.kmc.core.domain.AchievementDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Handles all player-facing notification when an achievement unlocks.
 * Kept separate from service logic so it can be swapped or mocked in tests.
 */
public final class AchievementNotifier {

    public void notify(Player player, AchievementDefinition def) {
        if (player == null) return;

        AchievementDefinition.Rarity rarity     = def.getRarity();
        String                       rarityLabel = rarity.getLabel();
        Sound                        sound       = unlockSound(rarity);

        // Title + chat + sound to the earner
        player.sendTitle(rarityLabel + " §l🏆 " + def.getName(), "§7" + def.getDescription(), 10, 80, 30);
        player.sendMessage("§6§l✦ Achievement Unlocked: §r" + rarityLabel + " §r§f" +
                def.getName() + " §7— " + def.getDescription());
        player.playSound(player.getLocation(), sound, 1.0f, 1.5f);

        // Server broadcast for RARE / EPIC / LEGENDARY
        if (rarity.isBroadcastOnUnlock()) {
            Bukkit.broadcastMessage("§6§l✦ ACHIEVEMENT§r §f" + player.getName() +
                    " §7unlocked §r" + rarityLabel + "§r §f" + def.getName() +
                    " §8[" + def.getCategory().getDisplayName() + "]");

            for (Player listener : Bukkit.getOnlinePlayers()) {
                listener.playSound(listener.getLocation(), sound, 0.5f, 1.2f);
            }
        }
    }

    private static Sound unlockSound(AchievementDefinition.Rarity rarity) {
        return rarity == AchievementDefinition.Rarity.LEGENDARY
                ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                : Sound.ENTITY_PLAYER_LEVELUP;
    }

    /** Notifies the player of their updated progress (not yet unlocked). */
    public void notifyProgress(Player player, AchievementDefinition def, int current, int target) {
        if (player == null) return;
        player.sendMessage("§8[§6Achievement§8] §7" + def.getName() +
                ": §f" + current + "§7/§f" + target);
    }
}
