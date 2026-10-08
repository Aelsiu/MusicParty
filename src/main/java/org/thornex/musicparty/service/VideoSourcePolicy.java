package org.thornex.musicparty.service;

import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.util.VideoSourceExpiry;
import java.time.Instant;
import java.util.List;

/** Pure projection of already validated sources. Never fetches media or changes signed URLs. */
public final class VideoSourcePolicy {
    private VideoSourcePolicy() {}

    public static boolean isValidCap(String cap) {
        return cap != null && List.of("UNLIMITED", "480P", "720P", "1080P", "1440P", "2160P").contains(cap);
    }

    public static boolean allows(String cap, Integer width, Integer height) {
        if (!isValidCap(cap)) throw new IllegalArgumentException("Invalid video resolution cap");
        if ("UNLIMITED".equals(cap)) return true;
        return width != null && height != null && width > 0 && height > 0
                && Math.min(width, height) <= Integer.parseInt(cap.substring(0, cap.length() - 1));
    }

    /** Returns null when no complete playable representation remains; clears every fallback alias. */
    public static ResolvedVideoSource filter(ResolvedVideoSource source, String cap) {
        if (source == null) return null;
        if (!isValidCap(cap)) throw new IllegalArgumentException("Invalid video resolution cap");
        DashVideoSource dash = source.dash();
        if (dash != null) {
            List<MediaTrackSource> videos = dash.videoTracks().stream()
                    .filter(track -> allows(cap, track.width(), track.height())).toList();
            dash = videos.isEmpty() || dash.audioTracks().isEmpty() ? null
                    : new DashVideoSource(videos, dash.audioTracks(), dash.durationMs(), dash.minBufferTime());
        }
        Mp4VideoSource mp4 = source.mp4();
        if (mp4 != null && !allows(cap, mp4.width(), mp4.height())) mp4 = null;
        // Legacy-only sources remain valid under the unlimited default, never under a finite cap.
        if (dash == null && mp4 == null) return "UNLIMITED".equals(cap) && source.dash() == null
                && source.mp4() == null && source.url() != null ? source : null;
        Instant expiry = null;
        if (dash != null) {
            for (MediaTrackSource track : dash.videoTracks()) expiry = earlier(expiry, expiry(track.url(), track.backupUrls()));
            for (MediaTrackSource track : dash.audioTracks()) expiry = earlier(expiry, expiry(track.url(), track.backupUrls()));
        }
        if (mp4 != null) expiry = earlier(expiry, expiry(mp4.url(), mp4.backupUrls()));
        int quality = dash == null ? mp4.quality() : dash.videoTracks().stream().mapToInt(MediaTrackSource::quality).max().orElse(0);
        return new ResolvedVideoSource(mp4 == null ? null : mp4.url(), mp4 == null ? List.of() : mp4.backupUrls(),
                dash == null ? mp4.format() : "dash", quality, source.streamDurationMs(), source.resolvedAt(), expiry, dash, mp4);
    }

    private static Instant expiry(String primary, List<String> backups) {
        Instant result = VideoSourceExpiry.deadline(primary);
        for (String backup : backups) result = earlier(result, VideoSourceExpiry.deadline(backup));
        return result;
    }
    private static Instant earlier(Instant a, Instant b) {
        return a == null ? b : b == null || a.isBefore(b) ? a : b;
    }
}
