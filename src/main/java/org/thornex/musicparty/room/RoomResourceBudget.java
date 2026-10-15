package org.thornex.musicparty.room;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Server-wide budgets bound expensive work without limiting rooms or members. */
@Service
public class RoomResourceBudget {
    private final Semaphore downloads;
    private final Semaphore transcoders;
    private final ConcurrentLinkedQueue<String> waiting = new ConcurrentLinkedQueue<>();
    private boolean silenceAttempted;
    private byte[] silence;

    public RoomResourceBudget(MultiRoomProperties properties) {
        downloads = new Semaphore(Math.max(1, properties.getMaxConcurrentDownloads()), true);
        transcoders = new Semaphore(Math.max(1, properties.getMaxConcurrentTranscoders()), true);
    }

    public <T> Mono<T> download(Supplier<Mono<T>> work) {
        return Mono.using(() -> {
            downloads.acquire();
            return new Lease(downloads);
        }, lease -> Mono.defer(work), Lease::close).subscribeOn(Schedulers.boundedElastic());
    }

    public synchronized Lease transcoder(String roomId) {
        if (!waiting.contains(roomId)) waiting.add(roomId);
        if (!roomId.equals(waiting.peek()) || !transcoders.tryAcquire()) return null;
        waiting.remove(roomId);
        return new Lease(transcoders);
    }

    public synchronized void cancel(String roomId) { waiting.remove(roomId); }

    public synchronized byte[] silence(Supplier<byte[]> generator) {
        if (!silenceAttempted) { silenceAttempted = true; silence = generator.get(); }
        return silence;
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();
        Lease(Semaphore semaphore) { this.semaphore = semaphore; }
        @Override public void close() { if (released.compareAndSet(false, true)) semaphore.release(); }
    }
}
