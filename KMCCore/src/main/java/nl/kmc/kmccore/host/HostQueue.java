package nl.kmc.kmccore.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one queue every host speech goes through, so several events never talk through each other.
 * Higher priority is spoken first; speeches wait at most {@code maxWaitMs} (an old announcement is dropped
 * instead of being spoken minutes late); a speech with a key is never queued twice; and an interrupted
 * speech is gone for good (it does not come back later). No Bukkit dependency.
 */
public final class HostQueue {

    /** One sentence, ready to be shown (placeholders already filled in). */
    public record SpeechLine(String text, int delayTicks, int holdTicks, String sound, HostDialogues.Stage stage) {

        public SpeechLine(String text, int delayTicks, int holdTicks, String sound) {
            this(text, delayTicks, holdTicks, sound, HostDialogues.Stage.NONE);
        }
    }

    public static final class Speech {
        public final String key;
        public final String dialogueId;
        public final List<SpeechLine> lines;
        public final int priority;
        public final HostDialogues.Audience audience;
        public final UUID target;
        public final String teamId;
        public final boolean skippable;
        public final int cooldownSeconds;
        public final long maxWaitMs;
        public final boolean once;
        long queuedAt;
        /** Optional camera: a recorded route that plays first, and/or an automatic focus on the host. */
        public String cutscene;
        public boolean focus;
        /** Staged: the host walks and gestures and the camera follows the lines (see HostDirector). */
        public boolean staged;
        /** Called once when the speech has ended — finished, skipped or stopped. */
        public Runnable onDone;

        /** Copies the camera/staging settings (and the end callback) from another speech. */
        public Speech withMetaOf(Speech o) {
            this.cutscene = o.cutscene;
            this.focus = o.focus;
            this.staged = o.staged;
            this.onDone = o.onDone;
            return this;
        }

        public Speech(String key, String dialogueId, List<SpeechLine> lines, int priority,
                      HostDialogues.Audience audience, UUID target, String teamId, boolean skippable,
                      int cooldownSeconds, long maxWaitMs, boolean once) {
            this.key = key == null ? "" : key;
            this.dialogueId = dialogueId == null ? "" : dialogueId;
            this.lines = List.copyOf(lines);
            this.priority = priority;
            this.audience = audience;
            this.target = target;
            this.teamId = teamId;
            this.skippable = skippable;
            this.cooldownSeconds = cooldownSeconds;
            this.maxWaitMs = maxWaitMs;
            this.once = once;
        }

        String cooldownKey() { return dialogueId + "|" + (teamId == null ? "" : teamId); }
    }

    public enum Offer { QUEUED, DUPLICATE, COOLDOWN, FULL }

    private final int capacity;
    private final List<Speech> pending = new ArrayList<>();
    private final Map<String, Long> lastPlayed = new HashMap<>();
    private final Set<String> onceKeys = new HashSet<>();

    public HostQueue(int capacity) { this.capacity = Math.max(1, capacity); }

    public Offer offer(Speech s, long nowMs, String currentKey) {
        if (!s.key.isEmpty()) {
            if (s.key.equals(currentKey)) return Offer.DUPLICATE;
            if (s.once && onceKeys.contains(s.key)) return Offer.DUPLICATE;
            for (Speech p : pending) if (p.key.equals(s.key)) return Offer.DUPLICATE;
        }
        if (s.cooldownSeconds > 0) {
            Long last = lastPlayed.get(s.cooldownKey());
            if (last != null && nowMs - last < s.cooldownSeconds * 1000L) return Offer.COOLDOWN;
        }
        if (pending.size() >= capacity) {
            Speech lowest = null;
            for (Speech p : pending) {
                if (lowest == null || p.priority < lowest.priority
                        || (p.priority == lowest.priority && p.queuedAt < lowest.queuedAt)) lowest = p;
            }
            if (lowest == null || lowest.priority >= s.priority) return Offer.FULL;
            pending.remove(lowest);
        }
        s.queuedAt = nowMs;
        pending.add(s);
        if (s.once && !s.key.isEmpty()) onceKeys.add(s.key);
        return Offer.QUEUED;
    }

    /** Highest-priority speech that has not expired (and removes it), or null. Expired ones are dropped. */
    public Speech poll(long nowMs) {
        pending.removeIf(p -> nowMs - p.queuedAt > p.maxWaitMs);
        Speech best = null;
        for (Speech p : pending) {
            if (best == null || p.priority > best.priority) best = p;     // ties keep the earlier one
        }
        if (best != null) pending.remove(best);
        return best;
    }

    /** The priority of the best waiting speech, or -1. */
    public int topPriority(long nowMs) {
        pending.removeIf(p -> nowMs - p.queuedAt > p.maxWaitMs);
        int top = -1;
        for (Speech p : pending) top = Math.max(top, p.priority);
        return top;
    }

    public void markPlayed(Speech s, long nowMs) {
        if (s.cooldownSeconds > 0) lastPlayed.put(s.cooldownKey(), nowMs);
    }

    public int size() { return pending.size(); }

    /** Drops everything that is waiting (cooldowns and once-keys stay). */
    public void clearPending() { pending.clear(); }

    /** A new session: nothing is remembered. */
    public void reset() {
        pending.clear();
        lastPlayed.clear();
        onceKeys.clear();
    }

    public List<String> pendingIds() {
        List<String> out = new ArrayList<>();
        for (Speech p : pending) out.add(p.dialogueId);
        return out;
    }
}
