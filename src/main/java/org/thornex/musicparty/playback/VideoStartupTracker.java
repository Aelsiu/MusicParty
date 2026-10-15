package org.thornex.musicparty.playback;

import org.thornex.musicparty.dto.VideoWaitState;
import org.thornex.musicparty.dto.VideoWatchStatusRequest;

import java.util.*;

/** Mailbox-owned initial readiness. Buffering is independent of having already played. */
public final class VideoStartupTracker {
    private String playbackId, waitId;
    private long revision, sourceRevision, position;
    private final Map<String, Observation> participants = new LinkedHashMap<>();
    private final Map<String, Observation> observations = new HashMap<>();
    private final Set<String> inactive = new HashSet<>();

    public void begin(String playback, long source, long frozenPosition, Collection<String> sessions) {
        cancel();
        playbackId = playback; waitId = UUID.randomUUID().toString(); revision = 1;
        sourceRevision = source; position = frozenPosition;
        members(sessions);
    }
    public boolean waiting() { return waitId != null; }
    public void cancel() { playbackId = null; waitId = null; participants.clear(); observations.clear(); inactive.clear(); }
    public void members(Collection<String> sessions) {
        if (!waiting()) return;
        Set<String> online = new HashSet<>(sessions);
        participants.keySet().retainAll(online); observations.keySet().retainAll(online); inactive.retainAll(online);
        for (String session : sessions) if (!inactive.contains(session)) participants.computeIfAbsent(session, ignored -> observations.computeIfAbsent(session,key->new Observation()));
    }
    public void revise(long frozenPosition, long source) {
        if (!waiting() || position == frozenPosition && sourceRevision == source) return;
        position = frozenPosition; sourceRevision = source; revision++;
        participants.values().forEach(observation -> observation.ready = false);
    }
    public boolean report(String session, VideoWatchStatusRequest report) {
        if (!waiting() || report == null || !Objects.equals(playbackId, report.playbackId())
                || report.sourceRevision() != sourceRevision || report.sequence() < 0 || report.sourceGeneration() < 0
                || !Set.of("PREPARING", "READY", "BUFFERING", "BLOCKED", "ERROR", "INACTIVE").contains(report.status() == null ? "" : report.status())) return false;
        boolean present = participants.containsKey(session);
        Observation observation = observations.get(session);
        if (observation != null && (report.sequence() <= observation.sequence || report.sourceGeneration() < observation.generation)) return false;
        observation = observations.computeIfAbsent(session, ignored -> new Observation());
        observation.sequence = report.sequence(); observation.generation = report.sourceGeneration();
        if (!report.foreground() || "INACTIVE".equals(report.status())) {
            inactive.add(session); return participants.remove(session) != null;
        }
        inactive.remove(session);
        participants.put(session, observation);
        boolean previous = observation.ready;
        observation.ready = "READY".equals(report.status()) && report.synchronizedWithRoom()
                && Objects.equals(waitId, report.waitId()) && Objects.equals(revision, report.waitRevision())
                && report.positionMs() != null && report.positionMs() >= 0
                && Math.abs(report.positionMs() - position) <= 750;
        return !present || previous != observation.ready;
    }
    /** No timeout excludes a still-online slow viewer. The caller enforces the minimum cooldown. */
    public boolean finish() {
        if (!waiting() || participants.values().stream().anyMatch(observation -> !observation.ready)) return false;
        cancel(); return true;
    }
    public VideoWaitState snapshot() {
        return !waiting() ? null : new VideoWaitState(waitId, revision, position, 0,
                participants.size(), (int) participants.values().stream().filter(observation -> observation.ready).count(), sourceRevision, true);
    }
    private static final class Observation { long sequence = -1, generation; boolean ready; }
}
