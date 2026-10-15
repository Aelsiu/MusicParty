package org.thornex.musicparty.dto;

import java.time.Instant;
import java.util.List;
import org.thornex.musicparty.util.VideoSourceExpiry;

/** Temporary CDN credentials. Never persist this record or log its URL. */
public record ResolvedVideoSource(
        String url, List<String> backupUrls, String format, int quality,
        long streamDurationMs, Instant resolvedAt, Instant expiresAt,
        DashVideoSource dash, Mp4VideoSource mp4, String expiryBasis
) {
    public ResolvedVideoSource {
        expiryBasis = expiryBasis == null ? "URL_DEADLINE" : expiryBasis;
        if(!java.util.Set.of("URL_DEADLINE","UPSTREAM_DECLARED").contains(expiryBasis)) throw new IllegalArgumentException("expiryBasis");
        backupUrls = List.copyOf(backupUrls);
    }

    public ResolvedVideoSource(String url,List<String> backupUrls,String format,int quality,long streamDurationMs,Instant resolvedAt,Instant expiresAt,DashVideoSource dash,Mp4VideoSource mp4) { this(url,backupUrls,format,quality,streamDurationMs,resolvedAt,expiresAt,dash,mp4,"URL_DEADLINE"); }
    public ResolvedVideoSource(String url, List<String> backupUrls, String format, int quality,
                               long streamDurationMs, Instant resolvedAt, Instant expiresAt) {
        this(url, backupUrls, format, quality, streamDurationMs, resolvedAt, expiresAt, null,
                url == null ? null : new Mp4VideoSource(url, backupUrls, format, quality, "q" + quality,
                        "Q" + quality, streamDurationMs, expiresAt));
    }

    /** Mixed known / unknown expiry still requires the scheduler's bounded soft freshness. */
    public boolean hasUnknownExpiry() {
        if("UPSTREAM_DECLARED".equals(expiryBasis)) return expiresAt == null;
        if (dash != null) {
            for (MediaTrackSource track : dash.videoTracks()) if (unknown(track.url(), track.backupUrls())) return true;
            for (MediaTrackSource track : dash.audioTracks()) if (unknown(track.url(), track.backupUrls())) return true;
        }
        if (mp4 != null && unknown(mp4.url(), mp4.backupUrls())) return true;
        return dash == null && mp4 == null && unknown(url, backupUrls);
    }

    private static boolean unknown(String primary, List<String> backups) {
        if (VideoSourceExpiry.deadline(primary) == null) return true;
        for (String backup : backups) if (VideoSourceExpiry.deadline(backup) == null) return true;
        return false;
    }

    @Override
    public String toString() {
        return "ResolvedVideoSource[url=<redacted>, backups=" + backupUrls.size()
                + ", format=" + format + ", quality=" + quality
                + ", streamDurationMs=" + streamDurationMs + ", resolvedAt=" + resolvedAt
                + ", expiresAt=" + expiresAt + ", dash=" + (dash == null ? "none" :
                dash.videoTracks().size() + " video / " + dash.audioTracks().size() + " audio") + "]";
    }
}
