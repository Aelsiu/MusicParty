package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoSourcePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static String url(long seconds) { return "https://test.bilivideo.com/fixture?deadline=" + seconds; }
    private static MediaTrackSource track(int quality, int width, int height, String primary) {
        return new MediaTrackSource(quality, quality, "q" + quality, "fixture", "video/mp4", "avc1.640028",
                100000, width, height, "60", "0-100", "101-200", primary, List.of(), null);
    }
    @Test void capsUseShortEdgeNotQualityCodecOrFrameRate() {
        assertTrue(VideoSourcePolicy.allows("1080P", 1920, 1080));
        assertTrue(VideoSourcePolicy.allows("1080P", 1080, 1920));
        assertFalse(VideoSourcePolicy.allows("720P", 1080, 1920));
        assertFalse(VideoSourcePolicy.allows("480P", null, 480));
        assertTrue(VideoSourcePolicy.allows("UNLIMITED", null, null));
        assertThrows(IllegalArgumentException.class, () -> VideoSourcePolicy.allows("q80", 640, 480));
        assertFalse(VideoSourcePolicy.isValidCap(null));
    }
    @Test void removesEveryFallbackAliasAndRecomputesFreshnessFromRemainingTracks() {
        long later = NOW.plusSeconds(600).getEpochSecond();
        var low = track(127, 1280, 720, url(later)); // qn cannot be the cap decision.
        var high = track(16, 3840, 2160, url(NOW.plusSeconds(30).getEpochSecond()));
        var audio = track(1, 0, 0, url(later));
        var mp4 = new Mp4VideoSource("https://test.bilivideo.com/unknown", List.of("https://test.bilivideo.com/backup"),
                "mp4", 32, "q32", "480p", 60000, null);
        var source = new ResolvedVideoSource(mp4.url(), mp4.backupUrls(), "dash", 127, 60000, NOW,
                NOW.plusSeconds(30), new DashVideoSource(List.of(low, high), List.of(audio), 60000, 1.5), mp4);
        var result = VideoSourcePolicy.filter(source, "720P");
        assertEquals(List.of(low), result.dash().videoTracks());
        assertEquals(List.of(audio), result.dash().audioTracks());
        assertNull(result.mp4()); assertNull(result.url()); assertEquals(List.of(), result.backupUrls());
        assertEquals(127, result.quality()); assertEquals(NOW.plusSeconds(600), result.expiresAt());
        assertFalse(result.hasUnknownExpiry());
        assertEquals(result, VideoSourcePolicy.filter(result, "720P"));
    }
    @Test void finiteCapDisallowsUnknownMp4AndEmptyDashButUnlimitedPreservesFallback() {
        var unknown = new ResolvedVideoSource("https://test.bilivideo.com/mp4", List.of(), "mp4", 32, 60000, NOW, null);
        assertNull(VideoSourcePolicy.filter(unknown, "2160P"));
        assertNotNull(VideoSourcePolicy.filter(unknown, "UNLIMITED"));
        var mp4 = new Mp4VideoSource(url(NOW.plusSeconds(600).getEpochSecond()), List.of(), "mp4", 32, "q32", "fixture", 60000, null, 480, 854);
        var known = new ResolvedVideoSource(mp4.url(), List.of(), "mp4", 32, 60000, NOW, null, null, mp4);
        assertNotNull(VideoSourcePolicy.filter(known, "480P"));
        assertNull(VideoSourcePolicy.filter(new ResolvedVideoSource(null, List.of(), "dash", 80, 60000, NOW, null,
                new DashVideoSource(List.of(track(80,1920,1080,url(NOW.plusSeconds(600).getEpochSecond()))), List.of(track(1,0,0,"https://test.bilivideo.com/audio")), 60000,1.5), null), "720P"));
    }
}
