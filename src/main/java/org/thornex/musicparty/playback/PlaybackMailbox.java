package org.thornex.musicparty.playback;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.room.RoomContext;
import org.thornex.musicparty.room.RoomScoped;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded room actor. Its monitor only protects admission and dequeue, never user work. */
@Component
@RoomScoped
public class PlaybackMailbox {
    public static final int DEFAULT_CAPACITY = 256;
    private static final ThreadLocal<PlaybackMailbox> CURRENT = new ThreadLocal<>();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlaybackMailbox.class);

    private enum Lifecycle { OPEN, QUIESCING, CLOSED }
    private final Object monitor = new Object();
    private final String roomId;
    private final int capacity;
    private final Executor executor;
    private final ArrayDeque<Task<?>> queue = new ArrayDeque<>();
    private final Set<CompletionTicket> tickets = new HashSet<>();
    private final Map<Object, Task<?>> coalesced = new HashMap<>();
    private Lifecycle lifecycle = Lifecycle.OPEN;
    private boolean draining;
    private volatile Runnable afterTask = () -> {};
    private CompletableFuture<Void> quiescence;

    @Autowired public PlaybackMailbox(PlaybackExecutor executor) {
        this(RoomContext.require(), DEFAULT_CAPACITY, executor);
    }

    public PlaybackMailbox(String roomId, int capacity, Executor executor) {
        if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("roomId is required");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.roomId = roomId;
        this.capacity = capacity;
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    public String roomId() { return roomId; }
    public boolean isOnMailboxThread() { return CURRENT.get() == this; }
    public void assertOnMailboxThread() {
        if (!isOnMailboxThread()) throw new IllegalStateException("Playback mutation must run in its room mailbox");
    }
    public void setAfterTask(Runnable hook) { afterTask = java.util.Objects.requireNonNull(hook); }

    public <T> CompletableFuture<T> submit(Callable<T> work) { return enqueue(work, null); }
    public CompletableFuture<Void> submit(Runnable work) {
        return submit(() -> { work.run(); return null; });
    }

    /** At most one task with this key is waiting; a running tick may acquire one successor. */
    public CompletableFuture<Void> submitCoalesced(Object key, Runnable work) {
        return enqueue(() -> { work.run(); return null; }, java.util.Objects.requireNonNull(key));
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> enqueue(Callable<T> work, Object key) {
        Task<T> task;
        boolean start;
        synchronized (monitor) {
            if (lifecycle != Lifecycle.OPEN) return failed(new RoomClosedException());
            if (key != null && coalesced.containsKey(key)) return (CompletableFuture<T>) coalesced.get(key).result;
            if (queue.size() + tickets.size() >= capacity) return failed(new RoomBusyException());
            task = new Task<>(work, key, false);
            queue.addLast(task);
            if (key != null) coalesced.put(key, task);
            start = claimDrain();
        }
        if (start) startDrain();
        return task.result;
    }

    /** Reserve before subscribing/starting network work; a successful result can always be committed. */
    public CompletionTicket reserveCompletion() {
        synchronized (monitor) {
            if (lifecycle != Lifecycle.OPEN) throw new RoomClosedException();
            if (queue.size() + tickets.size() >= capacity) throw new RoomBusyException();
            CompletionTicket ticket = new CompletionTicket();
            tickets.add(ticket);
            return ticket;
        }
    }

    public final class CompletionTicket implements AutoCloseable {
        private boolean reserved = true;

        /** An atomic reservation-to-queue exchange, unaffected by normal overload. */
        public CompletableFuture<Void> commit(Runnable work) {
            return commit(() -> { work.run(); return null; });
        }

        public <T> CompletableFuture<T> commit(Callable<T> work) {
            Task<T> task;
            boolean start;
            synchronized (monitor) {
                if (!reserved || lifecycle != Lifecycle.OPEN) return failed(new RoomClosedException());
                reserved = false;
                tickets.remove(this);
                task = new Task<>(work, null, false);
                queue.addLast(task);
                start = claimDrain();
            }
            if (start) startDrain();
            return task.result;
        }

        /** Returns true exactly once when an outstanding reservation is released. */
        public boolean cancel() {
            synchronized (monitor) {
                if (!reserved) return false;
                reserved = false;
                tickets.remove(this);
                return true;
            }
        }
        @Override public void close() { cancel(); }
    }

    /** Reserved control slot: accepted immediate work completes before finalization. */
    public CompletableFuture<Void> quiesce(Runnable finalizer) {
        boolean start;
        synchronized (monitor) {
            if (quiescence != null) return quiescence;
            if (lifecycle == Lifecycle.CLOSED) return CompletableFuture.completedFuture(null);
            lifecycle = Lifecycle.QUIESCING;
            for (CompletionTicket ticket : tickets) ticket.reserved = false;
            tickets.clear();
            Task<Void> control = new Task<>(() -> {
                try { finalizer.run(); }
                finally { synchronized (monitor) { lifecycle = Lifecycle.CLOSED; } }
                return null;
            }, null, true);
            quiescence = control.result;
            queue.addLast(control);
            start = claimDrain();
        }
        if (start) startDrain();
        return quiescence;
    }

    /** Destruction fallback only; normal lifecycle calls quiesce before destroying the scope. */
    @PreDestroy public void closeNow() {
        synchronized (monitor) {
            lifecycle = Lifecycle.CLOSED;
            for (CompletionTicket ticket : tickets) ticket.reserved = false;
            tickets.clear();
            for (Task<?> task : queue) task.result.completeExceptionally(new RoomClosedException());
            queue.clear();
            coalesced.clear();
        }
    }

    public int pendingCount() { synchronized (monitor) { return queue.size(); } }
    public int reservedCompletionCount() { synchronized (monitor) { return tickets.size(); } }

    private boolean claimDrain() {
        if (draining) return false;
        draining = true;
        return true;
    }
    private void startDrain() {
        try { executor.execute(this::drain); }
        catch (RuntimeException error) {
            closeNow();
            log.error("Playback worker rejected for room {}", roomId, error);
        }
    }
    private void drain() {
        for (;;) {
            Task<?> task;
            synchronized (monitor) {
                task = queue.pollFirst();
                if (task == null) { draining = false; return; }
                if (task.key != null) coalesced.remove(task.key, task);
                task.result.begun = true;
            }
            if (task.result.isCancelled()) continue;
            PlaybackMailbox previous = CURRENT.get();
            CURRENT.set(this);
            try (var ignored = RoomContext.enter(roomId)) { task.execute(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }

    private final class Task<T> {
        final Callable<T> work;
        final Object key;
        final boolean control;
        final MailboxFuture<T> result = new MailboxFuture<>();
        Task(Callable<T> work, Object key, boolean control) {
            this.work = java.util.Objects.requireNonNull(work);
            this.key = key;
            this.control = control;
            result.cancellable = !control;
        }
        void execute() {
            T value = null;
            Throwable failure = null;
            try { value = work.call(); } catch (Throwable error) { failure = error; }
            if (!control) try { afterTask.run(); }
            catch (Throwable error) { log.error("Playback publication failed for room {}", roomId, error); }
            if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        }
    }

    private final class MailboxFuture<T> extends CompletableFuture<T> {
        private boolean begun;
        private boolean cancellable = true;
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (monitor) {
                if (begun || !cancellable) return false;
                return super.cancel(false);
            }
        }
        private void checkWait() {
            if (!isDone() && isOnMailboxThread()) throw new IllegalStateException("Cannot wait for this room mailbox from its own worker");
        }
        @Override public T join() { checkWait(); return super.join(); }
        @Override public T get() throws InterruptedException, ExecutionException { checkWait(); return super.get(); }
        @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            checkWait(); return super.get(timeout, unit);
        }
    }

    private <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> result = new MailboxFuture<>();
        result.completeExceptionally(error);
        return result;
    }

    public static final class RoomBusyException extends IllegalStateException {
        public RoomBusyException() { super("ROOM_BUSY"); }
    }
    public static final class RoomClosedException extends IllegalStateException {
        public RoomClosedException() { super("ROOM_CLOSED"); }
    }
}
