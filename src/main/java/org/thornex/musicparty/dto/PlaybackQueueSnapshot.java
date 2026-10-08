package org.thornex.musicparty.dto;

import java.util.List;

public record PlaybackQueueSnapshot(int protocolVersion, String playbackEpoch,
        String mediaMode, long modeEpoch, long queueRevision, List<MusicQueueItem> queue) {
    public PlaybackQueueSnapshot { queue = List.copyOf(queue); }
}
