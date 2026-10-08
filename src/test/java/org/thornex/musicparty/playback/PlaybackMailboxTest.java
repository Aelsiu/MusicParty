package org.thornex.musicparty.playback;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.room.RoomContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackMailboxTest {
    private static class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> workers = new ArrayDeque<>();
        @Override public void execute(Runnable command) { workers.add(command); }
        void drain() { while (!workers.isEmpty()) workers.removeFirst().run(); }
    }

    @Test void boundedFifoReservesCompletionAndAlwaysHasQuiesceSlot() {
        ManualExecutor executor = new ManualExecutor();
        PlaybackMailbox mailbox = new PlaybackMailbox("room-A", 2, executor);
        List<String> executed = new ArrayList<>();
        var ticket = mailbox.reserveCompletion();
        var first = mailbox.submit(() -> { executed.add("first"); });
        CompletionException busy = assertThrows(CompletionException.class, () -> mailbox.submit(() -> {}).join());
        assertInstanceOf(PlaybackMailbox.RoomBusyException.class, busy.getCause());
        var completed = ticket.commit(() -> executed.add("network-result"));
        assertEquals(0, mailbox.reservedCompletionCount());
        var closed = mailbox.quiesce(() -> executed.add("save-and-close"));
        assertFalse(closed.cancel(false), "a lifecycle timeout must not cancel its reserved finalizer");
        assertSame(closed, mailbox.quiesce(() -> fail("duplicate finalizer")));
        assertThrows(CompletionException.class, () -> mailbox.submit(() -> {}).join());
        assertEquals(1, executor.workers.size());
        executor.drain();
        assertTrue(first.isDone());
        assertTrue(completed.isDone());
        assertTrue(closed.isDone());
        assertEquals(List.of("first", "network-result", "save-and-close"), executed);
    }

    @Test void completionCancellationIsExactlyOnceAndCloseInvalidatesLateResult() {
        ManualExecutor executor = new ManualExecutor();
        PlaybackMailbox mailbox = new PlaybackMailbox("room", 1, executor);
        var cancelled = mailbox.reserveCompletion();
        assertTrue(cancelled.cancel());
        assertFalse(cancelled.cancel());
        assertEquals(0, mailbox.reservedCompletionCount());
        var late = mailbox.reserveCompletion();
        mailbox.quiesce(() -> {});
        assertEquals(0, mailbox.reservedCompletionCount());
        assertFalse(late.cancel());
        assertThrows(CompletionException.class, () -> late.commit(() -> fail("closed room revived")).join());
        executor.drain();
    }

    @Test void coalescingContextAndSynchronousReentryDoNotReenterEngine() {
        ManualExecutor executor = new ManualExecutor();
        PlaybackMailbox mailbox = new PlaybackMailbox("room", 3, executor);
        List<String> executed = new ArrayList<>();
        AtomicInteger flushes = new AtomicInteger();
        mailbox.setAfterTask(() -> {
            assertEquals("room", RoomContext.current());
            assertTrue(mailbox.isOnMailboxThread());
            flushes.incrementAndGet();
        });
        var tick = mailbox.submitCoalesced("tick", () -> {
            executed.add("tick");
            var event = mailbox.submit(() -> { executed.add("event"); });
            assertThrows(IllegalStateException.class, event::join);
            executed.add("tick-returned");
        });
        assertSame(tick, mailbox.submitCoalesced("tick", () -> fail("duplicate tick")));
        try (var ignored = RoomContext.enter("different-room")) {
            executor.drain();
            assertEquals("different-room", RoomContext.current());
        }
        assertEquals(List.of("tick", "tick-returned", "event"), executed);
        assertEquals(2, flushes.get());
        assertNull(RoomContext.current());
    }

    @Test void oneRoomSerializesWhileAnotherRoomCanRun() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PlaybackMailbox slow = new PlaybackMailbox("slow", 3, executor);
            PlaybackMailbox fast = new PlaybackMailbox("fast", 3, executor);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger inRoom = new AtomicInteger();
            AtomicReference<Throwable> violation = new AtomicReference<>();
            var first = slow.submit(() -> {
                assertEquals(1, inRoom.incrementAndGet());
                started.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (Throwable error) { violation.set(error); }
                finally { inRoom.decrementAndGet(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = slow.submit(() -> assertEquals(1, inRoom.incrementAndGet()));
            fast.submit(() -> assertEquals("fast", RoomContext.current())).get(2, TimeUnit.SECONDS);
            assertFalse(second.isDone());
            assertFalse(first.cancel(false), "HTTP timeout cannot cancel an already running command");
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertNull(violation.get());
        }
    }

    @Test void cancelledUnstartedTaskNeverRunsAndFinalizerClosesEvenOnSaveFailure() {
        ManualExecutor executor = new ManualExecutor();
        PlaybackMailbox mailbox = new PlaybackMailbox("room", 1, executor);
        var queued = mailbox.submit(() -> fail("cancelled command ran"));
        assertTrue(queued.cancel(false));
        var close = mailbox.quiesce(() -> { throw new IllegalStateException("SQLite write failed"); });
        executor.drain();
        assertThrows(CompletionException.class, close::join);
        assertThrows(CompletionException.class, () -> mailbox.submit(() -> {}).join());
    }
}
