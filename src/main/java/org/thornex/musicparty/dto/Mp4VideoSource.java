package org.thornex.musicparty.dto;

import java.time.Instant;
import java.util.List;

/** Complete muxed single-segment fallback, fetched by clients directly. */
public record Mp4VideoSource(String url, List<String> backupUrls, String format, int quality,
                             String qualityId, String qualityLabel, long streamDurationMs, Instant expiresAt,
                             Integer width, Integer height) {
    public Mp4VideoSource { backupUrls = List.copyOf(backupUrls); }

    public Mp4VideoSource(String url, List<String> backupUrls, String format, int quality,
                          String qualityId, String qualityLabel, long streamDurationMs, Instant expiresAt) {
        this(url, backupUrls, format, quality, qualityId, qualityLabel, streamDurationMs, expiresAt, null, null);
    }

    @Override public String toString() {
        return "Mp4VideoSource[url=<redacted>, backups=" + backupUrls.size() + ", format=" + format
                + ", quality=" + qualityId + ", streamDurationMs=" + streamDurationMs
                + ", expiresAt=" + expiresAt + "]";
    }
}
