package nl.kmc.kmccore.audio;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Audio / ambience system using Minecraft's note-block sounds and
 * built-in sound effects.
 *
 * <p>No custom resource pack required — uses vanilla sounds only.
 *
 * <p>Tracks supported:
 * <ul>
 *   <li><b>Lobby ambient</b> — subtle, low-volume note loop</li>
 *   <li><b>Countdown tick</b> — escalating note pitch as timer counts down</li>
 *   <li><b>Build-up</b> — tension music for last 30s of close games</li>
 *   <li><b>Victory fanfare</b> — short jingle when game ends</li>
 *   <li><b>Defeat sting</b> — brief sad sound for eliminated players</li>
 * </ul>
 *
 * <p>Each track is a list of (note-block sound, pitch, delay-ticks) tuples
 * scheduled via Bukkit scheduler.
 */
public class AudioManager {

    private final KMCCore plugin;
    private BukkitTask lobbyAmbientTask;
    private final Map<UUID, BukkitTask> activeTracks = new HashMap<>();

    public AudioManager(KMCCore plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** Plays a victory fanfare for everyone. */
    public void playVictoryFanfare() {
        scheduleTrack(VICTORY_FANFARE, null);
    }

    /** Plays a defeat sting for one specific player. */
    public void playDefeatSting(Player p) {
        if (p == null || !p.isOnline()) return;
        scheduleTrack(DEFEAT_STING, p);
    }

    /** Plays a build-up tension track for the last 30s of a close game. */
    public void playBuildUp() {
        scheduleTrack(BUILDUP_TRACK, null);
    }

    /** Plays a single countdown tick — pitch rises as count drops. */
    public void playCountdownTick(int secondsLeft) {
        // Pitch rises as we approach 0
        // 10s left = pitch 0.8, 1s left = pitch 2.0
        float pitch = (float) Math.min(2.0, 0.8 + (10 - secondsLeft) * 0.12);
        if (secondsLeft <= 0) pitch = 2.0f;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(),
                    secondsLeft <= 3 ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.BLOCK_NOTE_BLOCK_BIT,
                    1f, pitch);
        }
    }

    /** Starts a low-volume ambient loop in the lobby. */
    public void startLobbyAmbient() {
        if (lobbyAmbientTask != null) lobbyAmbientTask.cancel();
        final int[] step = {0};
        lobbyAmbientTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Cycle through gentle notes every 4 seconds
            float pitch = LOBBY_AMBIENT_PITCHES[step[0] % LOBBY_AMBIENT_PITCHES.length];
            step[0]++;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 0.3f, pitch);
            }
        }, 0L, 80L);  // every 4 seconds
    }

    public void stopLobbyAmbient() {
        if (lobbyAmbientTask != null) {
            lobbyAmbientTask.cancel();
            lobbyAmbientTask = null;
        }
    }

    public void shutdown() {
        stopLobbyAmbient();
        for (BukkitTask t : activeTracks.values()) {
            try { t.cancel(); } catch (Exception ignored) {}
        }
        activeTracks.clear();
    }

    // ----------------------------------------------------------------
    // Internal — track scheduling
    // ----------------------------------------------------------------

    private record Note(Sound sound, float pitch, long delayTicks) {}

    private void scheduleTrack(List<Note> track, Player target) {
        for (Note note : track) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (target != null) {
                    if (target.isOnline()) {
                        target.playSound(target.getLocation(), note.sound, 1f, note.pitch);
                    }
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.playSound(p.getLocation(), note.sound, 0.7f, note.pitch);
                    }
                }
            }, note.delayTicks);
        }
    }

    // ----------------------------------------------------------------
    // Track definitions (vanilla sounds, vanilla pitches)
    // ----------------------------------------------------------------

    /** Victory fanfare — bright, ascending. */
    private static final List<Note> VICTORY_FANFARE = List.of(
            new Note(Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0),
            new Note(Sound.BLOCK_NOTE_BLOCK_BELL, 1.26f, 4),
            new Note(Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, 8),
            new Note(Sound.BLOCK_NOTE_BLOCK_BELL, 2.0f, 16),
            new Note(Sound.BLOCK_NOTE_BLOCK_HARP, 2.0f, 16),
            new Note(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 18)
    );

    /** Defeat sting — descending, somber. */
    private static final List<Note> DEFEAT_STING = List.of(
            new Note(Sound.BLOCK_NOTE_BLOCK_BASS, 0.94f, 0),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASS, 0.84f, 6),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 14),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 24)
    );

    /** Build-up tension — repeating heartbeat that accelerates. */
    private static final List<Note> BUILDUP_TRACK = List.of(
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 0),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 30),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 50),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 65),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 78),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 88),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 96),
            new Note(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 102)
    );

    /** Lobby ambient pitches — gentle pentatonic loop. */
    private static final float[] LOBBY_AMBIENT_PITCHES = {
            1.0f, 1.12f, 1.26f, 1.5f, 1.68f, 1.5f, 1.26f, 1.12f
    };
}
