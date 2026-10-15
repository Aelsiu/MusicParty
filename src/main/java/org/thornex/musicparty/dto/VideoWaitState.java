package org.thornex.musicparty.dto;

/** Public barrier state; never contains connection IDs or watcher credentials. */
public record VideoWaitState(String waitId, long waitRevision, long frozenPosition, long deadlineAt,
                             int participantCount, int readyCount, long sourceRevision, boolean initial) {
    public VideoWaitState(String waitId, long waitRevision, long frozenPosition, long deadlineAt,
                          int participantCount, int readyCount, long sourceRevision) {
        this(waitId, waitRevision, frozenPosition, deadlineAt, participantCount, readyCount, sourceRevision, false);
    }
}
