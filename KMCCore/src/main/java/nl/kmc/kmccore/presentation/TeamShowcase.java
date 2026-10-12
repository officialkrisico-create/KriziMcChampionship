package nl.kmc.kmccore.presentation;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * The interactive part of the team-showcase ceremony. For every team in turn:
 * <ol>
 *   <li>the team's online players are lined up on a small stage in the lobby,</li>
 *   <li>the camera swings around them for everyone else (a camera stop),</li>
 *   <li>each player is introduced with a title, a note and a firework in the team colour,</li>
 *   <li>the team ends on a big finale firework, then the players are sent back.</li>
 * </ol>
 * Everything is scheduled through the supplied {@code later} callback, so the ceremony's
 * generation guard / skip cancels it; {@link #cancel()} puts players back where they were.
 */
public final class TeamShowcase {

    private static final int PER_PLAYER_TICKS = 24;
    private static final int LEAD_TICKS       = 40;
    private static final int TAIL_TICKS       = 45;

    private final KMCCore plugin;
    private final Map<UUID, Location> originalLocations = new HashMap<>();

    public TeamShowcase(KMCCore plugin) { this.plugin = plugin; }

    /**
     * Schedules the whole showcase starting at {@code startTick}.
     *
     * @param teams  teams in presentation order
     * @param blocks the chat roster block for each team (same order), broadcast as the team is introduced
     * @param later  schedules a task N ticks from the ceremony's own clock (relative to now)
     * @return the tick at which the last team is done
     */
    public long schedule(List<KMCTeam> teams, List<List<String>> blocks, long startTick,
                         BiConsumer<Long, Runnable> later) {
        long tick = startTick;
        Location stage = stageLocation();
        for (int i = 0; i < teams.size(); i++) {
            KMCTeam team = teams.get(i);
            List<String> block = i < blocks.size() ? blocks.get(i) : List.of();
            tick += scheduleTeam(team, block, tick, stage, later);
        }
        return tick;
    }

    private long scheduleTeam(KMCTeam team, List<String> block, long at, Location stage,
                              BiConsumer<Long, Runnable> later) {
        List<UUID> memberIds = new ArrayList<>(team.getMembers());
        List<Player> members = memberIds.stream().map(Bukkit::getPlayer)
                .filter(p -> p != null && p.isOnline()).collect(Collectors.toList());
        int n = members.size();
        long duration = LEAD_TICKS + (long) Math.max(1, n) * PER_PLAYER_TICKS + TAIL_TICKS;

        ChatColor chat = team.getColor() != null ? team.getColor() : ChatColor.WHITE;
        Color color = toColor(chat);
        String teamName = team.getDisplayName();

        // 1) Roster in chat + team title + camera stop
        later.accept(at, () -> {
            block.forEach(Bukkit::broadcastMessage);
            if (stage == null || n == 0) {
                for (Player p : Bukkit.getOnlinePlayers())
                    p.sendTitle(chat + "" + ChatColor.BOLD + teamName.toUpperCase(),
                            ChatColor.GRAY + (n == 0 ? "Niemand online" : n + " spelers"), 5, 50, 10);
                return;
            }
            lineUp(members, stage);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(chat + "" + ChatColor.BOLD + teamName.toUpperCase(),
                        ChatColor.GRAY + "" + n + (n == 1 ? " speler" : " spelers"), 5, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.0f);
            }
            playCamera(team, members, stage, (int) duration);
        });
        // The team's own tune, right as it takes the stage.
        TeamJingles.schedule(plugin, team, at + 4, later);

        if (stage == null || n == 0) return n == 0 ? 50 : duration;

        // 2) Introduce each player
        for (int k = 0; k < n; k++) {
            final int idx = k;
            final Player pl = members.get(k);
            later.accept(at + LEAD_TICKS + (long) k * PER_PLAYER_TICKS, () -> {
                if (!pl.isOnline()) return;
                for (Player v : Bukkit.getOnlinePlayers()) {
                    v.sendTitle(chat + "" + ChatColor.BOLD + pl.getName(),
                            ChatColor.GRAY + teamName + "  •  " + (idx + 1) + "/" + n, 0, 30, 8);
                    v.playSound(v.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.8f + 0.7f * (idx + 1) / n);
                }
                burst(pl.getLocation().add(0, 0.5, 0), color, false);
                pl.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, pl.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
            });
        }

        // 3) Finale + send everyone back
        later.accept(at + LEAD_TICKS + (long) n * PER_PLAYER_TICKS, () -> {
            if (stage != null) burst(stage.clone().add(0, 1, 0), color, true);
            for (Player p : Bukkit.getOnlinePlayers())
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f);
        });
        later.accept(at + duration - 2, this::sendBack);

        return duration;
    }

    // ── Staging ───────────────────────────────────────────────────────────────

    /** Podium spots set with /kmcopeningsceremony (empty = use the lobby with an automatic line-up). */
    private List<Location> podium() {
        try { return plugin.getCeremonyFireworks().getPodium(); }
        catch (Throwable t) { return List.of(); }
    }

    /** Centre of the stage: the centre of the podium spots, else the KMC lobby (null when there is none). */
    private Location stageLocation() {
        List<Location> spots = podium();
        if (!spots.isEmpty() && spots.get(0).getWorld() != null) {
            double x = 0, y = 0, z = 0;
            for (Location s : spots) { x += s.getX(); y += s.getY(); z += s.getZ(); }
            Location c = new Location(spots.get(0).getWorld(), x / spots.size(), y / spots.size(), z / spots.size());
            c.setYaw(spots.get(0).getYaw());
            return c;
        }
        try {
            Location lobby = plugin.getArenaManager().getLobby();
            if (lobby != null && lobby.getWorld() != null) return lobby.clone();
        } catch (Exception ignored) { }
        return null;
    }

    /** Lines the team up along the X axis, facing +Z (towards the camera). */
    private void lineUp(List<Player> members, Location stage) {
        int n = members.size();
        List<Location> spots = podium();
        if (!spots.isEmpty()) {
            for (int i = 0; i < n; i++) {
                Player p = members.get(i);
                originalLocations.putIfAbsent(p.getUniqueId(), p.getLocation());
                p.teleport(spots.get(i % spots.size()));
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            Player p = members.get(i);
            originalLocations.putIfAbsent(p.getUniqueId(), p.getLocation());
            Location l = stage.clone().add((i - (n - 1) / 2.0) * 1.6, 0, 0);
            l.setYaw(0f);
            l.setPitch(0f);
            p.teleport(l);
        }
    }

    private void playCamera(KMCTeam team, List<Player> members, Location stage, int duration) {
        var cm = plugin.getCinematicManager();
        if (cm == null) return;
        List<UUID> memberIds = members.stream().map(Player::getUniqueId).toList();
        List<Player> viewers = Bukkit.getOnlinePlayers().stream()
                .filter(p -> !memberIds.contains(p.getUniqueId()))
                .collect(Collectors.toList());
        if (viewers.isEmpty()) return;

        // Camera sits in front of the line and drifts sideways while looking at the team.
        double y = stage.getY() + 2.2;
        int half = Math.max(10, duration / 2);
        float yaw = stage.getYaw();
        CameraRoute route = new CameraRoute("team-showcase-" + team.getId(), "team stop");
        route.addWaypoint(wp(stage, yaw, -3.5, y, 8.5, 180f + 16f, 12f, 10));
        route.addWaypoint(wp(stage, yaw, 0.0, y - 0.3, 6.0, 180f, 8f, half));
        route.addWaypoint(wp(stage, yaw, 3.5, y, 8.5, 180f - 16f, 12f, half));
        cm.playRouteInstance("team-showcase-" + team.getId(), route, viewers, false, null);
    }

    /**
     * A camera waypoint relative to the stage: dx to the right of the line-up, dz in front of it
     * (the direction the team faces, given by stageYaw); the camera looks back at the team.
     */
    private static CameraWaypoint wp(Location stage, float stageYaw, double dx, double y, double dz,
                                     float relYaw, float pitch, int ticks) {
        double r = Math.toRadians(stageYaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        double rx = Math.cos(r),  rz = Math.sin(r);
        double x = stage.getX() + rx * dx + fx * dz;
        double z = stage.getZ() + rz * dx + fz * dz;
        Location l = new Location(stage.getWorld(), x, y, z, stageYaw + relYaw, pitch);
        return new CameraWaypoint(l, ticks, InterpolationType.SMOOTH, "", "", "");
    }

    /** Puts everyone who was lined up back where they stood. */
    private void sendBack() {
        for (Map.Entry<UUID, Location> e : originalLocations.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) p.teleport(e.getValue());
        }
        originalLocations.clear();
    }

    /** Called when the ceremony is cancelled or skipped. */
    public void cancel() { sendBack(); }

    // ── Effects ───────────────────────────────────────────────────────────────

    private static void burst(Location at, Color color, boolean big) {
        if (at.getWorld() == null) return;
        try {
            Firework fw = at.getWorld().spawn(at, Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(big ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.BALL)
                    .withColor(color).withFade(Color.WHITE)
                    .trail(big).flicker(big).build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            fw.detonate();
        } catch (Exception ignored) { }
    }

    static Color toColor(ChatColor c) {
        return switch (c) {
            case DARK_RED -> Color.fromRGB(170, 0, 0);
            case RED -> Color.fromRGB(255, 85, 85);
            case GOLD -> Color.fromRGB(255, 170, 0);
            case YELLOW -> Color.fromRGB(255, 255, 85);
            case DARK_GREEN -> Color.fromRGB(0, 170, 0);
            case GREEN -> Color.fromRGB(85, 255, 85);
            case AQUA -> Color.fromRGB(85, 255, 255);
            case DARK_AQUA -> Color.fromRGB(0, 170, 170);
            case DARK_BLUE -> Color.fromRGB(0, 0, 170);
            case BLUE -> Color.fromRGB(85, 85, 255);
            case LIGHT_PURPLE -> Color.fromRGB(255, 85, 255);
            case DARK_PURPLE -> Color.fromRGB(170, 0, 170);
            case GRAY -> Color.fromRGB(170, 170, 170);
            case DARK_GRAY -> Color.fromRGB(85, 85, 85);
            case BLACK -> Color.fromRGB(30, 30, 30);
            default -> Color.WHITE;
        };
    }
}
