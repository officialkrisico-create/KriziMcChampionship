package nl.kmc.kmccore.host;

import nl.kmc.kmccore.host.HostDialogues.Audience;
import nl.kmc.kmccore.host.HostQueue.Offer;
import nl.kmc.kmccore.host.HostQueue.Speech;
import nl.kmc.kmccore.host.HostQueue.SpeechLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HostQueueTest {

    private static Speech speech(String key, String id, int priority, int cooldown, long maxWaitMs, boolean once) {
        return new Speech(key, id, List.of(new SpeechLine("tekst", 0, 60, null)), priority, Audience.ALL, null, null,
                true, cooldown, maxWaitMs, once);
    }

    private static Speech team(String id, String teamId, int cooldown) {
        return new Speech("", id, List.of(new SpeechLine("x", 0, 40, null)), 50, Audience.ALL, null, teamId,
                true, cooldown, 5000, false);
    }

    @Test
    void higherPriorityIsSpokenFirstAndTiesKeepTheirOrder() {
        HostQueue q = new HostQueue(8);
        q.offer(speech("a", "a", 50, 0, 60_000, false), 0, "");
        q.offer(speech("b", "b", 80, 0, 60_000, false), 0, "");
        q.offer(speech("c", "c", 50, 0, 60_000, false), 0, "");
        assertEquals("b", q.poll(1).dialogueId);
        assertEquals("a", q.poll(1).dialogueId);
        assertEquals("c", q.poll(1).dialogueId);
        assertNull(q.poll(1));
    }

    @Test
    void aKeyIsNeverQueuedTwiceNorWhileItIsBeingSpoken() {
        HostQueue q = new HostQueue(8);
        assertEquals(Offer.QUEUED, q.offer(speech("results:3", "r", 50, 0, 60_000, false), 0, ""));
        assertEquals(Offer.DUPLICATE, q.offer(speech("results:3", "r", 50, 0, 60_000, false), 0, ""));
        Speech s = q.poll(0);
        assertEquals(Offer.DUPLICATE, q.offer(speech("results:3", "r", 50, 0, 60_000, false), 0, s.key),
                "the same event fired again while it is being spoken");
    }

    @Test
    void aOnceKeyIsNotSpokenAgainThisSession() {
        HostQueue q = new HostQueue(8);
        assertEquals(Offer.QUEUED, q.offer(speech("finale:champion", "f", 100, 0, 60_000, true), 0, ""));
        q.poll(0);
        assertEquals(Offer.DUPLICATE, q.offer(speech("finale:champion", "f", 100, 0, 60_000, true), 10, ""));
        q.reset();
        assertEquals(Offer.QUEUED, q.offer(speech("finale:champion", "f", 100, 0, 60_000, true), 20, ""));
    }

    @Test
    void cooldownBlocksARepeatOfTheSameDialogueForTheSameTeam() {
        HostQueue q = new HostQueue(8);
        assertEquals(Offer.QUEUED, q.offer(team("live-goal", "red", 10), 0, ""));
        q.markPlayed(q.poll(0), 0);
        assertEquals(Offer.COOLDOWN, q.offer(team("live-goal", "red", 10), 4_000, ""));
        assertEquals(Offer.QUEUED, q.offer(team("live-goal", "blue", 10), 4_000, ""));
        assertEquals(Offer.QUEUED, q.offer(team("live-goal", "red", 10), 11_000, ""));
    }

    @Test
    void anOldAnnouncementExpiresInsteadOfBeingSpokenLate() {
        HostQueue q = new HostQueue(8);
        q.offer(speech("a", "a", 50, 0, 5_000, false), 0, "");
        assertEquals(-1, q.topPriority(6_000));
        assertNull(q.poll(6_000));
    }

    @Test
    void aFullQueueDropsTheLeastImportantOne() {
        HostQueue q = new HostQueue(2);
        q.offer(speech("a", "a", 10, 0, 60_000, false), 0, "");
        q.offer(speech("b", "b", 20, 0, 60_000, false), 0, "");
        assertEquals(Offer.FULL, q.offer(speech("c", "c", 10, 0, 60_000, false), 0, ""));
        assertEquals(Offer.QUEUED, q.offer(speech("d", "d", 90, 0, 60_000, false), 0, ""));
        assertEquals(List.of("b", "d"), q.pendingIds().stream().sorted().toList());
    }

    @Test
    void clearingPendingForgetsNothingElseButAResetForgetsEverything() {
        HostQueue q = new HostQueue(8);
        q.offer(speech("a", "a", 50, 0, 60_000, true), 0, "");
        q.clearPending();
        assertEquals(0, q.size());
        assertEquals(Offer.DUPLICATE, q.offer(speech("a", "a", 50, 0, 60_000, true), 1, ""));
        q.reset();
        assertEquals(Offer.QUEUED, q.offer(speech("a", "a", 50, 0, 60_000, true), 2, ""));
    }
}
