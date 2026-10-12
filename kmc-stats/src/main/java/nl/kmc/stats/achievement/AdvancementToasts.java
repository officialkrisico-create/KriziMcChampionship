package nl.kmc.stats.achievement;

import nl.kmc.core.domain.AchievementDefinition;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Shows KMC achievements as real Minecraft advancement toasts (custom icon, title, frame) in their own
 * "KMC Achievements" tab, so the vanilla advancements are no longer the thing players see.
 *
 * <p>Definitions are registered at runtime with {@code UnsafeValues#loadAdvancement}; they are not
 * persisted by the server, so {@link #register} must run on every start / reload. Failures are never
 * fatal: {@link #show} returns false and the caller falls back to a title.
 */
public final class AdvancementToasts {

    private static final Logger LOG = Logger.getLogger(AdvancementToasts.class.getName());

    private static final NamespacedKey ROOT = new NamespacedKey("kmc", "root");

    private boolean available;

    /** (Re)registers the root + one advancement per definition. */
    @SuppressWarnings("deprecation")
    public void register(Collection<AchievementDefinition> defs) {
        available = false;
        try {
            if (Bukkit.getAdvancement(ROOT) == null) {
                Bukkit.getUnsafe().loadAdvancement(ROOT, rootJson());
            }
            int n = 0;
            for (AchievementDefinition d : defs) {
                NamespacedKey key = keyFor(d);
                if (Bukkit.getAdvancement(key) != null) continue;
                Bukkit.getUnsafe().loadAdvancement(key, childJson(d));
                n++;
            }
            available = true;
            LOG.info("[KMC/Achievements] Registered " + n + " custom advancement(s) in the KMC tab.");
        } catch (Throwable t) {
            LOG.warning("[KMC/Achievements] Custom advancements unavailable (" + t + ") — using titles instead.");
        }
    }

    /** Grants the advancement so the client pops its toast. Returns false if that isn't possible. */
    public boolean show(Player player, AchievementDefinition def) {
        if (!available || player == null) return false;
        try {
            Advancement root = Bukkit.getAdvancement(ROOT);
            Advancement adv  = Bukkit.getAdvancement(keyFor(def));
            if (root == null || adv == null) return false;
            award(player, root);
            award(player, adv);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void award(Player player, Advancement adv) {
        AdvancementProgress pr = player.getAdvancementProgress(adv);
        if (!pr.isDone()) pr.awardCriteria("done");
    }

    /** Turns off vanilla advancement chat messages in every loaded world. */
    public static void silenceVanilla() {
        for (World w : Bukkit.getWorlds()) {
            try { w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false); } catch (Throwable ignored) { }
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    private static NamespacedKey keyFor(AchievementDefinition d) {
        String id = d.getId().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return new NamespacedKey("kmc", "ach/" + id);
    }

    private static String rootJson() {
        return "{\"display\":{\"icon\":{\"id\":\"minecraft:nether_star\"},"
                + "\"title\":{\"text\":\"KMC Achievements\",\"color\":\"gold\"},"
                + "\"description\":\"Jouw prestaties in het toernooi\","
                + "\"background\":\"minecraft:gui/advancements/backgrounds/stone\","
                + "\"frame\":\"task\",\"show_toast\":false,\"announce_to_chat\":false,\"hidden\":false},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String childJson(AchievementDefinition d) {
        String frame = switch (d.getRarity()) {
            case COMMON -> "task";
            case RARE -> "goal";
            default -> "challenge";
        };
        String color = switch (d.getRarity()) {
            case COMMON -> "gray";
            case RARE -> "aqua";
            case EPIC -> "light_purple";
            case LEGENDARY -> "gold";
        };
        String icon = "minecraft:" + d.getIcon().getKey().getKey();
        return "{\"parent\":\"kmc:root\",\"display\":{\"icon\":{\"id\":\"" + icon + "\"},"
                + "\"title\":{\"text\":" + quote(d.getName()) + ",\"color\":\"" + color + "\"},"
                + "\"description\":" + quote(d.getDescription()) + ","
                + "\"frame\":\"" + frame + "\",\"show_toast\":true,\"announce_to_chat\":false,"
                + "\"hidden\":" + d.isHidden() + "},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    private static String quote(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
