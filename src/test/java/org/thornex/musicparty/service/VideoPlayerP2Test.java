package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.api.BilibiliVideoApiService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoPlayerP2Test {
    private static final String BV = "BV1GJ411x7h7";
    private static ResolvedVideoSource compliant(VideoPlayerServiceTest.Fixture f) {
        var source = f.source();
        var mp4 = new Mp4VideoSource(source.url(), List.of(), "mp4", 16, "q16", "fixture", 60000, null, 640, 360);
        return new ResolvedVideoSource(mp4.url(), List.of(), "mp4", 16, 60000, source.resolvedAt(), null, null, mp4);
    }
    private static VideoDescriptor longDescriptor() {
        return new VideoDescriptor(BV + ":p1", BV, 123, 1, "Long", "First", "Owner", 120000, null);
    }
    @Test void loweredDurationDuringMetadataAdmissionDoesNotAddLateDescriptor() {
        var f = new VideoPlayerServiceTest.Fixture();
        var metadata = Sinks.<VideoDescriptor>one();
        when(f.api.getVideoDescriptor(anyString())).thenReturn(metadata.asMono());
        f.enqueue(BV); f.properties.getPlayer().setVideoMaxDurationMinutes(1);
        metadata.tryEmitValue(longDescriptor());
        assertTrue(f.state().queue().isEmpty()); assertEquals(0, f.mailbox.reservedCompletionCount());
    }
    @Test void loweredDurationDuringFirstSourceResolvePreservesPendingWithoutStarting() {
        var f = new VideoPlayerServiceTest.Fixture();
        when(f.api.getVideoDescriptor(anyString())).thenReturn(Mono.just(longDescriptor()));
        var source = Sinks.<ResolvedVideoSource>one(); when(f.api.resolveSource(any())).thenReturn(source.asMono());
        f.enqueue(BV); String queueId = f.state().queue().getFirst().queueId();
        f.act(() -> f.player.togglePause("SYSTEM")); f.properties.getPlayer().setVideoMaxDurationMinutes(1);
        source.tryEmitValue(f.source());
        assertNull(f.state().nowPlaying()); assertTrue(f.state().isPaused());
        assertEquals("VIDEO_TOO_LONG", f.state().sourceErrorCode());
        assertEquals(queueId, f.state().videoPending().queueId()); assertEquals(1, f.state().queue().size());
    }
    @Test void loweringDurationDuringAdmittedRenewalKeepsSameInstanceAndLatestIntent() {
        var f = new VideoPlayerServiceTest.Fixture();
        when(f.api.getVideoDescriptor(anyString())).thenReturn(Mono.just(longDescriptor()));
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.advance(6000); var initial = f.state().nowPlaying();
        var renewal = Sinks.<ResolvedVideoSource>one(); when(f.api.resolveCurrentInstanceSource(any())).thenReturn(renewal.asMono());
        clearInvocations(f.api);
        f.act(() -> f.player.refreshSource(initial.playbackId(), initial.sourceRevision(), "fixture"));
        f.properties.getPlayer().setVideoMaxDurationMinutes(1); f.advance(2000);
        renewal.tryEmitValue(f.source());
        assertEquals(initial.playbackId(), f.state().nowPlaying().playbackId());
        assertEquals(8000, f.state().nowPlaying().currentPosition()); assertFalse(f.state().isPaused());
        verify(f.api).resolveCurrentInstanceSource(any());
    }
    @Test void noCompliantSourceClearsAliasesAndRequiresExplicitRecoveryDespitePolicyOrCookieChanges() {
        var f = new VideoPlayerServiceTest.Fixture();
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(10000);
        var original = f.state().nowPlaying();
        f.properties.getPlayer().setVideoMaxResolution("480P");
        f.properties.getPlayer().setVideoPolicyRevision(1);
        var blocked = f.state();
        assertTrue(blocked.policyRecoveryRequired()); assertTrue(blocked.isPaused());
        assertEquals("PLAYING", blocked.playIntent()); assertEquals("VIDEO_QUALITY_LIMIT", blocked.sourceErrorCode());
        assertNull(blocked.nowPlaying().music().url()); assertNull(blocked.nowPlaying().videoSource());
        assertEquals(original.playbackId(), blocked.nowPlaying().playbackId());
        assertEquals(original.sourceRevision() + 1, blocked.nowPlaying().sourceRevision());
        f.properties.getPlayer().setVideoMaxResolution("UNLIMITED"); f.properties.getPlayer().setVideoPolicyRevision(2);
        f.act(f.player::cookieChanged); f.advance(10000); f.act(f.player::tick);
        assertTrue(f.state().policyRecoveryRequired()); verify(f.api, times(1)).resolveSource(any());
        f.act(() -> f.player.recoverSource("owner-session", original.playbackId(), blocked.nowPlaying().sourceRevision(), null));
        assertFalse(f.state().policyRecoveryRequired()); assertFalse(f.state().isPaused());
        assertEquals(original.currentPosition(), f.state().nowPlaying().currentPosition());
    }
    @Test void pendingPolicyFailureRetainsQuotaAndRecoveryCreatesFirstInstanceAtZero() {
        var f = new VideoPlayerServiceTest.Fixture();
        f.properties.getQueue().setMaxSize(1);
        when(f.api.resolveSource(any())).thenReturn(Mono.error(new BilibiliVideoApiService.VideoSourceException(
                BilibiliVideoApiService.FailureCode.VIDEO_QUALITY_LIMIT, "fixture policy")));
        f.enqueue(BV); String queueId = f.state().queue().getFirst().queueId();
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertTrue(f.state().policyRecoveryRequired()); assertNull(f.state().nowPlaying());
        assertEquals(queueId, f.state().videoPending().queueId());
        assertEquals(0, f.mailbox.submit(() -> f.player.availableImportSlots("owner")).join());
        when(f.api.resolveSource(any())).thenReturn(Mono.just(compliant(f))); f.advance(6000);
        f.act(() -> f.player.recoverSource("owner-session", null, 0, queueId));
        assertNotNull(f.state().nowPlaying().playbackId()); assertEquals(0, f.state().nowPlaying().currentPosition());
        assertTrue(f.state().queue().isEmpty()); assertFalse(f.state().isPaused());
    }
    @Test void manualPauseDuringRecoveryPreservesPausedIntent() {
        var f = new VideoPlayerServiceTest.Fixture();
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.properties.getPlayer().setVideoMaxResolution("480P"); f.properties.getPlayer().setVideoPolicyRevision(1);
        var blocked = f.state(); f.advance(6000);
        var completion = Sinks.<ResolvedVideoSource>one(); when(f.api.resolveSource(any())).thenReturn(completion.asMono());
        f.act(() -> f.player.recoverSource("owner-session", blocked.nowPlaying().playbackId(), blocked.nowPlaying().sourceRevision(), null));
        f.act(() -> f.player.togglePause("SYSTEM")); completion.tryEmitValue(compliant(f));
        assertFalse(f.state().policyRecoveryRequired()); assertEquals("PAUSED", f.state().playIntent()); assertTrue(f.state().isPaused());
    }
}
