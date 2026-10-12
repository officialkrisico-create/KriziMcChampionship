package nl.kmc.kmccore.presentation;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * A short signature tune per team, played when the team is introduced (team showcase) and at the winners'
 * reveal. Built from note-block sounds, so no resource pack is needed.
 *
 * <p>Every team gets a deterministic melody from its id (pentatonic scale, one of nine instruments). To
 * write your own, add to config.yml:
 * <pre>
 * team-jingles:
 *   red_rabbits:
 *     instrument: BELL        # HARP, BELL, FLUTE, PLING, BIT, CHIME, XYLOPHONE, GUITAR, BANJO
 *     notes: [0, 4, 7, 12, 7, 12]   # semitones above the lowest note, 0-24
 *     speed: 3                # ticks between notes
 * </pre>
 */
public final class TeamJingles {

    private static final String[] INSTRUMENTS = {"HARP", "BELL", "FLUTE", "PLING", "BIT", "CHIME", "XYLOPHONE", "GUITAR", "BANJO"};
    private static final int[] PENTATONIC = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};

    private TeamJingles() {}

    /** The tune: the instrument, the notes (semitones 0-24) and the ticks between them. */
    public record Tune(String instrument, int[] notes, int speed) {}

    /** Deterministic default tune for a team id. */
    public static Tune defaultTune(String teamId) {
        int h = teamId == null ? 0 : teamId.toLowerCase(Locale.ROOT).hashCode();
        int seed = h == Integer.MIN_VALUE ? 0 : Math.abs(h);
        String instrument = INSTRUMENTS[seed % INSTRUMENTS.length];
        int[] notes = new int[6];
        int s = seed;
        int idx = 3;
        for (int i = 0; i < notes.length; i++) {
            s = s * 1103515245 + 12345;
            int step = ((s >>> 16) % 3) - 1;                 // -1, 0, +1 along the scale: a gentle contour
            idx = Math.max(0, Math.min(PENTATONIC.length - 1, idx + step * 2 + (i == notes.length - 1 ? 1 : 0)));
            notes[i] = PENTATONIC[idx];
        }
        return new Tune(instrument, notes, 3);
    }

    public static Tune tuneFor(KMCCore plugin, KMCTeam team) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("team-jingles." + team.getId());
        Tune def = defaultTune(team.getId());
        if (s == null) return def;
        List<Integer> custom = s.getIntegerList("notes");
        int[] notes = custom.isEmpty() ? def.notes() : custom.stream().mapToInt(i -> Math.max(0, Math.min(24, i))).toArray();
        return new Tune(s.getString("instrument", def.instrument()).toUpperCase(Locale.ROOT), notes,
                Math.max(1, s.getInt("speed", def.speed())));
    }

    /** Note-block pitch for a semitone offset 0..24 (0.5 .. 2.0). */
    public static float pitch(int semitone) {
        return (float) Math.pow(2.0, (semitone - 12) / 12.0);
    }

    static Sound sound(String instrument) {
        return switch (instrument) {
            case "BELL"      -> Sound.BLOCK_NOTE_BLOCK_BELL;
            case "FLUTE"     -> Sound.BLOCK_NOTE_BLOCK_FLUTE;
            case "PLING"     -> Sound.BLOCK_NOTE_BLOCK_PLING;
            case "BIT"       -> Sound.BLOCK_NOTE_BLOCK_BIT;
            case "CHIME"     -> Sound.BLOCK_NOTE_BLOCK_CHIME;
            case "XYLOPHONE" -> Sound.BLOCK_NOTE_BLOCK_XYLOPHONE;
            case "GUITAR"    -> Sound.BLOCK_NOTE_BLOCK_GUITAR;
            case "BANJO"     -> Sound.BLOCK_NOTE_BLOCK_BANJO;
            default          -> Sound.BLOCK_NOTE_BLOCK_HARP;
        };
    }

    /**
     * Schedules the tune for everyone online, starting {@code at} ticks on the caller's clock.
     *
     * @return how many ticks the tune lasts
     */
    public static long schedule(KMCCore plugin, KMCTeam team, long at, BiConsumer<Long, Runnable> later) {
        if (!plugin.getConfig().getBoolean("team-jingles.enabled", true)) return 0;
        Tune tune = tuneFor(plugin, team);
        Sound snd = sound(tune.instrument());
        for (int i = 0; i < tune.notes().length; i++) {
            final float pitch = pitch(tune.notes()[i]);
            later.accept(at + (long) i * tune.speed(), () -> {
                for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), snd, 0.8f, pitch);
            });
        }
        return (long) tune.notes().length * tune.speed();
    }

    /** Plays the tune right now (no scheduler hooks — uses plain Bukkit tasks). */
    public static void playNow(KMCCore plugin, KMCTeam team) {
        schedule(plugin, team, 0, (delay, r) -> Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(0, delay)));
    }
}
