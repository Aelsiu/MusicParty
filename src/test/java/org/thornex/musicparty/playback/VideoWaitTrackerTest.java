package org.thornex.musicparty.playback;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.VideoWaitState;
import org.thornex.musicparty.dto.VideoWatchStatusRequest;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class VideoWaitTrackerTest {
    @Test void exactBufferingBoundaryFreezesAndOldReadyCannotReleaseBarrier() {
        Fixture f = new Fixture(); f.qualify("a"); f.advance(100); f.report("a", "BUFFERING");
        f.advance(1999); assertFalse(f.tick().created()); assertNull(f.tracker.snapshot());
        f.advance(1); assertTrue(f.tick().created());
        VideoWaitState barrier = f.tracker.snapshot(); assertEquals(9000, barrier.frozenPosition());
        f.report("a", "READY"); assertNotNull(f.tracker.snapshot());
        f.advance(100); f.ready("a", barrier); assertNull(f.tracker.snapshot());
    }

    @Test void exactTimeoutBoundaryExcludesVerifiedIdentityAcrossReconnectAndSourceChanges() {
        Fixture f = new Fixture(); VideoWaitState barrier = f.waiting("a");
        for (int i = 0; i < 14; i++) { f.advance(1000); f.report("a", "BUFFERING"); }
        f.advance(999); assertTrue(f.tick().timeouts().isEmpty());
        f.advance(1); var timeout = f.tick(); assertEquals(1, timeout.timeouts().size());
        assertEquals("name-a", timeout.timeouts().getFirst().userName());
        assertEquals(barrier.waitId(), timeout.timeouts().getFirst().waitId());
        assertTrue(f.tracker.isExcluded("identity-a")); assertNull(f.tracker.snapshot());
        assertTrue(f.tick().timeouts().isEmpty());
        f.tracker.disconnect("a"); f.advance(100);
        f.reportAs("new-a", "identity-a", "READY", null, 1, true, true, true);
        f.advance(100); f.reportAs("new-a", "identity-a", "BUFFERING", null, 2, true, true, true);
        f.advance(2000); assertFalse(f.tick().created());
        f.tracker.cancel(); assertTrue(f.tracker.isExcluded("identity-a"));
        f.tracker.newPlayback("new-playback"); assertFalse(f.tracker.isExcluded("identity-a"));
    }

    @Test void leaseAndDisconnectExitWithoutTimeoutOrAutomaticRequalification() {
        Fixture f = new Fixture(); f.waiting("a"); f.advance(6000);
        var update = f.tick(); assertTrue(update.ended()); assertTrue(update.timeouts().isEmpty());
        assertFalse(f.tracker.isExcluded("identity-a"));
        f.report("a", "BUFFERING"); f.advance(2000); assertFalse(f.tick().created());
        f.qualify("b"); f.advance(100); f.report("b", "BUFFERING"); f.advance(2000); f.tick();
        f.tracker.disconnect("b"); assertTrue(f.tick().ended()); assertTrue(f.tick().timeouts().isEmpty());
    }

    @Test void sourceSeekAndRateRevisionsDiscardReadyAndKeepOriginalDeadline() {
        Fixture f = new Fixture(); f.qualify("b"); VideoWaitState original = f.waiting("a");
        f.advance(100); f.ready("b", original); assertEquals(1, f.tracker.snapshot().readyCount());
        f.tracker.revise(12300, 2); f.sourceRevision = 2;
        VideoWaitState revised = f.tracker.snapshot();
        assertEquals(original.deadlineAt(), revised.deadlineAt()); assertEquals(original.waitId(), revised.waitId());
        assertEquals(original.waitRevision() + 1, revised.waitRevision()); assertEquals(0, revised.readyCount());
        f.advance(100); f.ready("a", original); assertEquals(0, f.tracker.snapshot().readyCount());
        f.advance(100); f.ready("a", revised); f.ready("b", revised); assertNull(f.tracker.snapshot());
    }

    @Test void joiningDuringBarrierDoesNotJoinFixedParticipants() {
        Fixture f = new Fixture(); VideoWaitState barrier = f.waiting("a");
        f.qualify("b"); assertEquals(1, f.tracker.snapshot().participantCount());
        f.advance(100); f.ready("a", barrier); assertNull(f.tracker.snapshot());
    }

    @Test void initialPreparingBackgroundBlockedAndAutoplayDeniedCannotBlock() {
        Fixture f = new Fixture(); f.report("a", "BUFFERING"); f.advance(2000); assertFalse(f.tick().created());
        f.qualify("b"); f.advance(100); f.reportAs("b", "identity-b", "BUFFERING", null, 2, true, false, true);
        f.advance(2000); assertFalse(f.tick().created());
        f.qualify("c"); f.advance(100); f.reportAs("c", "identity-c", "BLOCKED", null, 2, true, true, false);
        f.advance(2000); assertFalse(f.tick().created());
    }

    @Test void rebuildingQualifiedSourceDoesNotResetContinuousBuffering() {
        Fixture f = new Fixture(); f.qualify("a"); f.advance(100); f.report("a", "BUFFERING");
        f.advance(1000); f.reportAs("a", "identity-a", "PREPARING", null, 3, false, true, true);
        f.advance(1000); assertTrue(f.tick().created());
    }

    @Test void manualCancelPreservesTimeoutExclusionAndCancelsOldNotifications() {
        Fixture f = new Fixture(); f.waiting("a"); f.tracker.cancel();
        for (int i = 0; i < 15; i++) { f.advance(1000); f.playing = false; f.report("a", "BUFFERING"); }
        assertNull(f.tracker.snapshot()); assertTrue(f.tick().timeouts().isEmpty());
        f.playing = true; f.tick(); f.advance(2000); assertTrue(f.tick().created());
    }

    @Test void oldSequencesAndSourceGenerationsDoNotChangeLeaseOrReadiness() {
        Fixture f = new Fixture(); f.qualify("a"); f.advance(100);
        var old = f.reportAs("a", "identity-a", "BUFFERING", null, 0, true, true, true);
        assertFalse(old.changed()); f.advance(2000); assertFalse(f.tick().created());
        f.report("a", "BUFFERING"); f.advance(2000); f.tick();
        VideoWaitState barrier = f.tracker.snapshot(); f.advance(100);
        var stale = new VideoWatchStatusRequest("playback", 0, 0, 100, "READY", true, true, true,
                barrier.waitId(), barrier.waitRevision(), barrier.frozenPosition());
        assertFalse(f.tracker.report("a", "identity-a", "name-a", stale, true, 9000, 1).changed());
        assertNotNull(f.tracker.snapshot());
    }

    private static final class Fixture {
        final AtomicLong nanos = new AtomicLong(), wall = new AtomicLong(100000);
        final VideoWaitTracker tracker = new VideoWaitTracker(nanos::get, wall::get);
        final java.util.Map<String, Long> sequences = new java.util.HashMap<>();
        long sourceRevision = 1; boolean playing = true;
        Fixture() { tracker.newPlayback("playback"); }
        void advance(long ms) { nanos.addAndGet(ms * 1_000_000L); wall.addAndGet(ms); }
        void qualify(String session) { report(session, "READY"); }
        VideoWaitState waiting(String session) {
            qualify(session); advance(100); report(session, "BUFFERING"); advance(2000); tick(); return tracker.snapshot();
        }
        VideoWaitTracker.Update tick() { return tracker.tick(playing, 9000, sourceRevision); }
        VideoWaitTracker.Update report(String session, String status) {
            return reportAs(session, "identity-" + session, status, null, sequences.merge(session, 1L, Long::sum), true, true, true);
        }
        void ready(String session, VideoWaitState wait) {
            reportAs(session, "identity-" + session, "READY", wait, sequences.merge(session, 1L, Long::sum), true, true, true);
        }
        VideoWaitTracker.Update reportAs(String session, String identity, String status, VideoWaitState wait, long sequence,
                                          boolean synced, boolean foreground, boolean allowed) {
            return tracker.report(session, identity, "name-" + session, new VideoWatchStatusRequest("playback", sourceRevision,
                    0, sequence, status, synced, foreground, allowed, wait == null ? null : wait.waitId(),
                    wait == null ? null : wait.waitRevision(), wait == null ? 9000 : wait.frozenPosition()), playing, 9000, sourceRevision);
        }
    }
}
