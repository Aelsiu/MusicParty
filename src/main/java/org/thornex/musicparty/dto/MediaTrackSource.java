package org.thornex.musicparty.dto;

import java.time.Instant;
import java.util.List;

/** A validated DASH representation. URLs are temporary credentials, never log this object verbatim. */
public record MediaTrackSource(int id, int quality, String qualityId, String qualityLabel,
                               String mimeType, String codecs, long bandwidth,
                               int width, int height, String frameRate,
                               String initialization, String indexRange,
                               String url, List<String> backupUrls, Instant expiresAt) {
    public MediaTrackSource { backupUrls = List.copyOf(backupUrls); }

    @Override public String toString() {
        return "MediaTrackSource[id=" + id + ", quality=" + qualityId + ", mimeType=" + mimeType
                + ", codecs=" + codecs + ", url=<redacted>, backups=" + backupUrls.size()
                + ", expiresAt=" + expiresAt + "]";
    }
}
