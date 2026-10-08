package org.thornex.musicparty.playback;

import org.thornex.musicparty.dto.VideoWaitState;
import org.thornex.musicparty.dto.VideoWatchStatusRequest;
import org.thornex.musicparty.event.VideoWaitTimeoutEvent;

import java.util.*;
import java.util.function.LongSupplier;

/** Mailbox-owned, ephemeral watching leases. All elapsed time uses a monotonic clock. */
public final class VideoWaitTracker {
    public static final long BUFFERING_NANOS = 2_000_000_000L;
    public static final long WAIT_NANOS = 15_000_000_000L;
    public static final long LEASE_NANOS = 6_000_000_000L;
    private static final long MIN_REPORT_NANOS = 100_000_000L;
    private static final int MAX_WATCHERS = 512;
    private static final Set<String> STATUSES = Set.of("PREPARING", "READY", "BUFFERING", "BLOCKED", "ERROR", "INACTIVE");
    private final LongSupplier nanos;
    private final LongSupplier wall;
    private final Map<String, Watcher> watchers = new LinkedHashMap<>();
    private final Set<String> excludedIdentities = new HashSet<>();
    private String playbackId;
    private Barrier barrier;

    public VideoWaitTracker(LongSupplier nanos, LongSupplier wall) { this.nanos = nanos; this.wall = wall; }

    public void newPlayback(String id) {
        playbackId = id; barrier = null; watchers.clear(); excludedIdentities.clear();
    }
    public void cancel() {
        barrier = null;
        watchers.values().forEach(w -> { w.bufferingAt = null; w.readyForBarrier = false; });
    }
    public Set<String> sessions() { return Set.copyOf(watchers.keySet()); }
    public boolean isWaiting() { return barrier != null; }
    public boolean isExcluded(String identity) { return excludedIdentities.contains(identity); }

    /** The earliest absolute monotonic boundary; heartbeats never move a buffering/barrier deadline. */
    public Long nextCheckNanos(boolean playing) {
        if (!playing) return null;
        Long next = barrier == null ? null : barrier.deadlineNanos;
        for (Watcher watcher : watchers.values()) {
            if (!watcher.qualified || excludedIdentities.contains(watcher.identity)) continue;
            long lease = watcher.lastReport + LEASE_NANOS;
            if (next == null || lease < next) next = lease;
            if (barrier == null && watcher.qualified && !excludedIdentities.contains(watcher.identity) && watcher.bufferingAt != null) {
                long trigger = watcher.bufferingAt + BUFFERING_NANOS;
                if (trigger < next) next = trigger;
            }
        }
        return next;
    }

    public VideoWaitState snapshot() {
        if (barrier == null) return null;
        int ready = (int) barrier.participants.stream().filter(this::ready).count();
        return new VideoWaitState(barrier.id, barrier.revision, barrier.position, barrier.deadlineAt,
                barrier.participants.size(), ready, barrier.sourceRevision);
    }

    /** Invalidating READY never extends the original deadline. */
    public void revise(long position, long sourceRevision) {
        if (barrier == null) return;
        barrier.position = position; barrier.sourceRevision = sourceRevision; barrier.revision++;
        barrier.participants.forEach(session -> { Watcher w = watchers.get(session); if (w != null) w.readyForBarrier = false; });
    }

    public void disconnect(String session) {
        watchers.remove(session);
        if (barrier != null) barrier.participants.remove(session);
    }

    public Update report(String session, String identity, String displayName, VideoWatchStatusRequest report,
                         boolean playing, long position, long sourceRevision) {
        if (report == null || !Objects.equals(playbackId, report.playbackId())
                || report.sourceRevision() != sourceRevision || report.sourceGeneration() < 0
                || report.sequence() < 0 || report.status() == null || !STATUSES.contains(report.status()) || identity == null || identity.isBlank())
            return Update.ignored();
        long now = nanos.getAsLong();
        Watcher watcher = watchers.get(session);
        if (watcher != null && (!watcher.identity.equals(identity) || report.sequence() <= watcher.sequence
                || report.sourceGeneration() < watcher.generation || now - watcher.lastReport < MIN_REPORT_NANOS))
            return Update.ignored();
        Update advance = tick(playing, position, sourceRevision);
        if (!watchers.containsKey(session) && watchers.size() >= MAX_WATCHERS) return advance;
        watcher = watchers.computeIfAbsent(session, ignored -> new Watcher(identity));
        watcher.sequence = report.sequence(); watcher.generation = report.sourceGeneration(); watcher.lastReport = now;
        watcher.name = displayName == null || displayName.isBlank() ? "一位观看成员" : displayName;
        watcher.status = report.status();
        boolean available = report.foreground() && report.playbackAllowed()
                && !Set.of("BLOCKED", "ERROR", "INACTIVE").contains(report.status());
        if (!available) {
            watcher.qualified = false; watcher.bufferingAt = null; watcher.readyForBarrier = false;
            if (barrier != null) barrier.participants.remove(session);
        } else {
            if (barrier == null && "READY".equals(report.status()) && report.synchronizedWithRoom()) watcher.qualified = true;
            if ("BUFFERING".equals(report.status()) && watcher.qualified && playing && watcher.bufferingAt == null)
                watcher.bufferingAt = now;
            if ("READY".equals(report.status())) {
                watcher.bufferingAt = null;
                watcher.readyForBarrier = barrier != null && report.synchronizedWithRoom()
                        && Objects.equals(barrier.id, report.waitId()) && report.waitRevision() != null
                        && report.waitRevision() == barrier.revision && report.positionMs() != null
                        && report.positionMs() >= 0
                        && Math.abs(report.positionMs() - barrier.position) <= 750;
            } else watcher.readyForBarrier = false;
        }
        // A report can remove the last participant or complete the current barrier.
        boolean ended = finishReadyBarrier();
        return new Update(true, advance.created(), advance.ended() || ended, advance.timeouts());
    }

    public Update tick(boolean playing, long position, long sourceRevision) {
        long now = nanos.getAsLong();
        boolean changed = false, created = false, ended = false;
        List<VideoWaitTimeoutEvent> timeouts = new ArrayList<>();
        for (String session : new ArrayList<>(watchers.keySet())) {
            if (now - watchers.get(session).lastReport >= LEASE_NANOS) { disconnect(session); changed = true; }
        }
        if (barrier != null && now >= barrier.deadlineNanos) {
            String waitId = barrier.id;
            Set<String> notified = new HashSet<>();
            for (String session : new ArrayList<>(barrier.participants)) {
                Watcher watcher = watchers.get(session);
                if (watcher == null || ready(session)) continue;
                excludedIdentities.add(watcher.identity);
                if (notified.add(watcher.identity)) timeouts.add(new VideoWaitTimeoutEvent(
                        UUID.randomUUID().toString(), waitId, playbackId, sourceRevision, watcher.name));
            }
            barrier.participants.removeIf(session -> {
                Watcher w = watchers.get(session); return w == null || excludedIdentities.contains(w.identity);
            });
            changed = true;
        }
        if (barrier != null && finishReadyBarrier()) { ended = true; changed = true; }
        if (!playing) { watchers.values().forEach(w -> w.bufferingAt = null); return new Update(changed, false, ended, timeouts); }
        if (barrier == null && !ended) {
            // A manual pause/replay restarts detection without clearing prior timeout exclusions.
            watchers.values().stream().filter(w -> w.qualified && "BUFFERING".equals(w.status) && w.bufferingAt == null)
                    .forEach(w -> w.bufferingAt = now);
            boolean trigger = watchers.values().stream().anyMatch(w -> w.qualified && !excludedIdentities.contains(w.identity)
                    && w.bufferingAt != null && now - w.bufferingAt >= BUFFERING_NANOS);
            if (trigger) {
                Set<String> participants = new LinkedHashSet<>();
                watchers.forEach((session,w) -> { if (w.qualified && !excludedIdentities.contains(w.identity)) {
                    participants.add(session); w.readyForBarrier = false;
                }});
                barrier = new Barrier(position, sourceRevision, now + WAIT_NANOS, wall.getAsLong() + 15000, participants);
                created = true; changed = true;
            }
        }
        return new Update(changed, created, ended, timeouts);
    }

    private boolean ready(String session) { Watcher watcher = watchers.get(session); return watcher != null && watcher.readyForBarrier; }
    private boolean finishReadyBarrier() {
        if (barrier == null || !barrier.participants.stream().allMatch(this::ready)) return false;
        barrier.participants.forEach(session -> { Watcher w = watchers.get(session); if (w != null) w.bufferingAt = null; });
        barrier = null; return true;
    }
    private static final class Watcher {
        final String identity;
        String name = "一位观看成员", status = "PREPARING";
        long sequence = -1, generation, lastReport;
        boolean qualified, readyForBarrier;
        Long bufferingAt;
        Watcher(String identity) { this.identity = identity; }
    }
    private static final class Barrier {
        final String id = UUID.randomUUID().toString();
        final long deadlineNanos, deadlineAt;
        final Set<String> participants;
        long revision = 1, position, sourceRevision;
        Barrier(long position, long sourceRevision, long deadlineNanos, long deadlineAt, Set<String> participants) {
            this.position = position; this.sourceRevision = sourceRevision; this.deadlineNanos = deadlineNanos;
            this.deadlineAt = deadlineAt; this.participants = participants;
        }
    }
    public record Update(boolean changed, boolean created, boolean ended, List<VideoWaitTimeoutEvent> timeouts) {
        public Update { timeouts = List.copyOf(timeouts); }
        static Update ignored() { return new Update(false, false, false, List.of()); }
    }
}
