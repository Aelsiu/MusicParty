package org.thornex.musicparty.dto;

import java.util.List;

/** Static SegmentBase DASH input for the client's two-track MPD adapter. */
public record DashVideoSource(List<MediaTrackSource> videoTracks, List<MediaTrackSource> audioTracks,
                              long durationMs, double minBufferTime) {
    public DashVideoSource {
        videoTracks = List.copyOf(videoTracks);
        audioTracks = List.copyOf(audioTracks);
    }
}
