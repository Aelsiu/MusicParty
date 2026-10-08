package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.playback.PlaybackEventSink;
import org.thornex.musicparty.playback.PlaybackMailbox;
import org.thornex.musicparty.playback.VideoWaitAlarmScheduler;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.event.VideoWaitTimeoutEvent;
import reactor.core.Disposable;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.BilibiliVideoApiService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoPlayerServiceTest {
    private static final String BV = "BV1GJ411x7h7";
    private static final String BV2 = "BV1Q541167Qg";
    private static final String URL = "https://upos-sz-estgcos.bilivideo.com/unit-test.mp4";

    @Test
    void enqueueKeepsPausedAndManualPlayConsumesExactlyOnce() {
        Fixture f = new Fixture();
        f.enqueue(BV);
        assertTrue(f.state().isPaused());
        assertEquals(1, f.state().queue().size());
        verify(f.api, never()).resolveSource(any());
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertFalse(f.state().isPaused());
        assertEquals(BV + ":p1", f.state().nowPlaying().music().id());
        assertTrue(f.state().queue().isEmpty());
        assertTrue(f.snapshot().isEmpty());
        assertEquals(0, f.mailbox.reservedCompletionCount());
    }

    @Test
    void sourceFailurePreservesVisiblePendingAndDoesNotAutomaticallySkip() {
        Fixture f = new Fixture();
        when(f.api.resolveSource(any())).thenReturn(Mono.error(new BilibiliVideoApiService.VideoSourceException(
                BilibiliVideoApiService.FailureCode.TEMPORARY_SOURCE_FAILURE, "临时网络失败")));
        f.enqueue(BV);
        f.enqueue(BV2);
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertTrue(f.state().isPaused());
        assertFalse(f.state().isLoading());
        assertNull(f.state().nowPlaying());
        assertEquals("ERROR", f.state().sourceStatus());
        assertEquals(2, f.snapshot().size());
        assertEquals(BV + ":p1", f.snapshot().getFirst().music().id());
        verify(f.api, times(1)).resolveSource(any());
    }

    @Test
    void switchDuringSourceResolutionRetainsQueueIdAndDiscardsLateResult() {
        Fixture f = new Fixture();
        Sinks.One<ResolvedVideoSource> pending = Sinks.one();
        when(f.api.resolveSource(any())).thenReturn(pending.asMono());
        f.enqueue(BV);
        String queueId = f.state().queue().getFirst().queueId();
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertTrue(f.state().isLoading());
        assertEquals(1, f.mailbox.reservedCompletionCount());
        f.act(f.player::freezeForModeSwitch);
        pending.tryEmitValue(f.source());
        assertNull(f.state().nowPlaying());
        assertEquals(queueId, f.snapshot().getFirst().queueId());
        assertEquals(0, f.mailbox.reservedCompletionCount());
        when(f.api.resolveSource(any())).thenReturn(Mono.just(f.source()));
        f.act(f.player::activatePaused);
        assertTrue(f.state().isPaused());
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertEquals(BV + ":p1", f.state().nowPlaying().music().id());
        assertTrue(f.snapshot().isEmpty());
    }

    @Test
    void pendingRemainsWithinPersonalAndRoomQuotaUntilItStarts() {
        Fixture f = new Fixture();
        f.properties.getQueue().setMaxSize(1);
        f.properties.getQueue().setMaxUserSongs(1);
        when(f.api.resolveSource(any())).thenReturn(Mono.never());
        f.enqueue(BV);
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertEquals(1, f.state().queue().size());
        assertThrows(RuntimeException.class, () -> f.enqueue(BV2));
        assertEquals(1, f.snapshot().size());
    }

    @Test
    void clearCancelsAcceptedMetadataAndReservedQuotaWithoutRevival() {
        Fixture f = new Fixture();
        Sinks.One<VideoDescriptor> metadata = Sinks.one();
        when(f.api.getVideoDescriptor(anyString())).thenReturn(metadata.asMono());
        f.enqueue(BV);
        assertEquals(1, f.mailbox.reservedCompletionCount());
        f.act(f.player::clearQueue);
        metadata.tryEmitValue(f.descriptor(BV));
        assertTrue(f.state().queue().isEmpty());
        assertEquals(0, f.mailbox.reservedCompletionCount());
        when(f.api.getVideoDescriptor(anyString())).thenAnswer(call -> Mono.just(f.descriptor(call.getArgument(0))));
        f.enqueue(BV2);
        assertEquals(1, f.state().queue().size());
    }

    @Test
    void acceptedMetadataCompletesInOriginalVideoQueueAfterModeDeactivation() {
        Fixture f = new Fixture();
        Sinks.One<VideoDescriptor> metadata = Sinks.one();
        when(f.api.getVideoDescriptor(anyString())).thenReturn(metadata.asMono());
        f.enqueue(BV);
        f.act(f.player::freezeForModeSwitch);
        metadata.tryEmitValue(f.descriptor(BV));
        assertEquals(1, f.state().queue().size());
        assertTrue(f.state().isPaused());
        verify(f.api, never()).resolveSource(any());
    }

    @Test
    void refreshIsSingleflightAndKeepsInstanceProgressAndPauseState() {
        Fixture f = new Fixture();
        f.enqueue(BV);
        f.act(() -> f.player.togglePause("SYSTEM"));
        String playback = f.state().nowPlaying().playbackId();
        f.advance(6000);
        Sinks.One<ResolvedVideoSource> replacement = Sinks.one();
        when(f.api.resolveSource(any())).thenReturn(replacement.asMono());
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK"));
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK"));
        verify(f.api, times(2)).resolveSource(any()); // first play + one shared refresh
        f.advance(2000);
        replacement.tryEmitValue(f.source());
        assertEquals(playback, f.state().nowPlaying().playbackId());
        assertEquals(2, f.state().nowPlaying().sourceRevision());
        assertEquals(8000, f.state().nowPlaying().currentPosition());
        assertFalse(f.state().isPaused());
    }

    @Test
    void failedRefreshDoesNotPauseRoomOrMoveItsClockBackwards() {
        Fixture f = new Fixture();
        f.enqueue(BV);
        f.act(() -> f.player.togglePause("SYSTEM"));
        String playback = f.state().nowPlaying().playbackId();
        f.advance(6000);
        when(f.api.resolveSource(any())).thenReturn(Mono.error(new IllegalStateException("private-url-must-not-escape")));
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK"));
        f.advance(1000);
        assertFalse(f.state().isPaused());
        assertEquals("ERROR", f.state().sourceStatus());
        assertEquals(7000, f.state().nowPlaying().currentPosition());
        assertEquals(1, f.state().nowPlaying().sourceRevision());
    }

    @Test
    void cookieChangeInvalidatesOldSourceAttemptAndRetainsCurrentSource() {
        Fixture f = new Fixture();
        f.enqueue(BV);
        f.act(() -> f.player.togglePause("SYSTEM"));
        String playback = f.state().nowPlaying().playbackId();
        f.advance(6000);
        Sinks.One<ResolvedVideoSource> replacement = Sinks.one();
        when(f.api.resolveSource(any())).thenReturn(replacement.asMono());
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK"));
        f.act(f.player::cookieChanged);
        replacement.tryEmitValue(f.source());
        assertEquals(1, f.state().nowPlaying().sourceRevision());
        assertFalse(f.state().isPaused());
        assertFalse(f.state().isLoading());
        assertEquals(0, f.mailbox.reservedCompletionCount());
    }

    @Test
    void monotonicProgressIgnoresWallClockJumpAndInactiveTicks() {
        Fixture f = new Fixture();
        f.enqueue(BV);
        f.act(() -> f.player.togglePause("SYSTEM"));
        f.nanos.addAndGet(3_000_000_000L);
        f.wall.addAndGet(-60000);
        assertEquals(3000, f.state().nowPlaying().currentPosition());
        f.act(f.player::freezeForModeSwitch);
        f.advance(30 * 60000);
        f.act(f.player::tick);
        assertEquals(3000, f.state().nowPlaying().currentPosition());
        f.act(f.player::activatePaused);
        assertEquals(3000, f.state().nowPlaying().currentPosition());
    }

    @Test
    void historyPendingIsNotPersistedAsAUserQueueItemAndRestoreIsPaused() {
        Fixture f = new Fixture();
        f.act(() -> f.player.restore(List.of(), List.of(BilibiliVideoApiService.toMusic(f.descriptor(BV)))));
        when(f.api.resolveSource(any())).thenReturn(Mono.never());
        f.act(() -> f.player.togglePause("SYSTEM"));
        assertTrue(f.snapshot().isEmpty());
        assertEquals(1, f.player.getHistorySnapshot().size());
        f.act(f.player::freezeForModeSwitch);
        assertTrue(f.state().isPaused());
    }

    @Test
    void removePendingAndCloseCannotReviveAnAsynchronousPlay() {
        Fixture f = new Fixture();
        f.enqueue(BV);
        Sinks.One<ResolvedVideoSource> delayed = Sinks.one();
        when(f.api.resolveSource(any())).thenReturn(delayed.asMono());
        String queueId = f.state().queue().getFirst().queueId();
        f.act(() -> f.player.togglePause("SYSTEM"));
        f.act(() -> f.player.removeSongFromQueue(queueId, "owner-session", false));
        delayed.tryEmitValue(f.source());
        assertNull(f.state().nowPlaying());
        assertTrue(f.snapshot().isEmpty());
        f.act(f.player::close);
        assertEquals(0, f.mailbox.reservedCompletionCount());
        assertThrows(RuntimeException.class, () -> f.enqueue(BV2));
    }

    @Test
    void playlistImportsAllPagesAndChecksFirstPageMetadataForEveryItem() {
        Fixture f = new Fixture();
        f.properties.getPlayer().setMaxPlaylistImportSize(25);
        f.properties.getQueue().setMaxUserSongs(25);
        List<Music> first = java.util.stream.IntStream.range(0, 20).mapToObj(index ->
                new Music("BV" + String.format("%010d", index), "Search title", List.of("Owner"), 999999999,
                        "bilibili", null)).toList();
        List<Music> second = java.util.stream.IntStream.range(20, 25).mapToObj(index ->
                new Music("BV" + String.format("%010d", index), "Search title", List.of("Owner"), 999999999,
                        "bilibili", null)).toList();
        when(f.playlists.getPlaylistMusics("fav", 0, 20)).thenReturn(Mono.just(first));
        when(f.playlists.getPlaylistMusics("fav", 20, 20)).thenReturn(Mono.just(second));
        f.act(() -> f.player.enqueuePlaylist(new EnqueuePlaylistRequest("bilibili-video", "fav"), "owner-session"));
        assertEquals(25, f.state().queue().size());
        verify(f.api, times(25)).getVideoDescriptor(anyString());
        assertTrue(f.state().isPaused());
        assertEquals(0, f.mailbox.reservedCompletionCount());
    }

    @Test
    void inactiveMembershipChangesPreserveVotesUntilPausedReactivation() {
        Fixture f = new Fixture();
        f.properties.getPlayer().setVoteSkipEnabled(true);
        f.properties.getPlayer().setVoteSkipWaitTime(0);
        f.properties.getPlayer().setVoteSkipThreshold(1.0);
        when(f.users.getUser("voter-session")).thenReturn(java.util.Optional.of(new User("voter", "voter-session", "Voter")));
        UserSummary owner = new UserSummary("owner", "owner-session", "Owner", false);
        UserSummary voter = new UserSummary("voter", "voter-session", "Voter", false);
        UserSummary other = new UserSummary("other", "other-session", "Other", false);
        when(f.users.getRecentlyActiveUserTokens()).thenReturn(Set.of("owner", "voter", "other"));
        when(f.users.getOnlineUserSummaries()).thenReturn(List.of(owner, voter, other));
        f.enqueue(BV);
        f.act(() -> f.player.togglePause("SYSTEM"));
        String playbackId = f.state().nowPlaying().playbackId();
        f.act(() -> f.player.skipToNext("voter-session"));
        assertEquals(1, f.state().currentVotes());

        f.act(f.player::freezeForModeSwitch);
        when(f.users.getRecentlyActiveUserTokens()).thenReturn(Set.of("owner", "other"));
        when(f.users.getOnlineUserSummaries()).thenReturn(List.of(owner, other));
        f.act(f.player::membersChanged);
        when(f.users.getRecentlyActiveUserTokens()).thenReturn(Set.of("owner", "voter"));
        when(f.users.getOnlineUserSummaries()).thenReturn(List.of(owner, voter));
        f.act(f.player::membersChanged);
        f.act(f.player::activatePaused);

        assertEquals(1, f.state().currentVotes());
        assertTrue(f.state().isPaused());
        assertEquals(playbackId, f.state().nowPlaying().playbackId());
    }

    @Test
    void rateReanchorsTheOldClockAndIsPersistedOnlyForVideo() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.advance(2000); f.act(() -> f.player.setPlaybackRate(1.5, "owner-session", true));
        f.advance(2000); assertEquals(5000, f.state().nowPlaying().currentPosition());
        assertEquals(1.5, f.state().playbackRate());
        assertEquals(1.5, f.player.getPlayerSettings().playbackRate());
        assertThrows(RuntimeException.class, () -> f.act(() -> f.player.setPlaybackRate(2, "owner-session", false)));
        f.act(f.player::freezeForModeSwitch); f.advance(5000); f.act(f.player::activatePaused);
        assertEquals(5000, f.state().nowPlaying().currentPosition()); assertEquals(1.5, f.state().playbackRate());
    }

    @Test
    void renewalAllowsSeekAndPauseAndCommitsCurrentIntentInsteadOfTaskStartState() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.properties.getPlayer().setSeekPolicy("OWNER_AND_ENQUEUER");
        f.advance(6000); String playback = f.state().nowPlaying().playbackId();
        Sinks.One<ResolvedVideoSource> replacement = Sinks.one();
        when(f.api.resolveSource(any())).thenReturn(replacement.asMono());
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK"));
        assertFalse(f.state().isLoading()); f.advance(1000);
        f.act(() -> f.player.seek(new SeekRequest(playback, 15000L), "owner-session", true));
        f.act(() -> f.player.togglePause("SYSTEM")); f.advance(2000);
        replacement.tryEmitValue(f.source());
        assertTrue(f.state().isPaused()); assertEquals("PAUSED", f.state().playIntent());
        assertEquals(15000, f.state().nowPlaying().currentPosition()); assertEquals(2, f.state().nowPlaying().sourceRevision());
    }

    @Test
    void knownExpiryStartsBackgroundRenewalBeforeExpirationWithoutFreezingClock() {
        Fixture f = new Fixture();
        when(f.api.resolveSource(any())).thenAnswer(call -> Mono.just(f.knownSource(65000)));
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        Sinks.One<ResolvedVideoSource> renewal = Sinks.one(); when(f.api.resolveSource(any())).thenReturn(renewal.asMono());
        f.advance(4999); f.act(f.player::tick); verify(f.api, times(1)).resolveSource(any());
        f.advance(1); f.act(f.player::tick); verify(f.api, times(2)).resolveSource(any());
        f.advance(1000); assertEquals(6000, f.state().nowPlaying().currentPosition()); assertFalse(f.state().isLoading());
        renewal.tryEmitValue(f.source()); assertEquals(6000, f.state().nowPlaying().currentPosition());
    }

    @Test
    void resumingAnExpiredSourceNeverCountsThePausedIntervalAsPlayback() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(2000);
        f.act(() -> f.player.togglePause("SYSTEM")); f.advance(301000);
        Sinks.One<ResolvedVideoSource> renewal = Sinks.one(); when(f.api.resolveSource(any())).thenReturn(renewal.asMono());
        f.act(() -> f.player.togglePause("SYSTEM")); assertEquals(2000, f.state().nowPlaying().currentPosition());
        f.advance(1000); assertEquals(3000, f.state().nowPlaying().currentPosition());
        renewal.tryEmitValue(f.source()); assertEquals(3000, f.state().nowPlaying().currentPosition());
    }

    @Test
    void temporaryRefreshFailureRetriesAfterBackoffEvenWhileTheOldSourceIsFresh() {
        Fixture f = new Fixture();
        when(f.api.resolveSource(any())).thenAnswer(call -> Mono.just(f.knownSource(7200000)));
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(6000);
        String playback = f.state().nowPlaying().playbackId();
        when(f.api.resolveSource(any())).thenReturn(Mono.error(new BilibiliVideoApiService.VideoSourceException(
                BilibiliVideoApiService.FailureCode.TEMPORARY_SOURCE_FAILURE, "临时来源请求失败")));
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK_ERROR"));
        Sinks.One<ResolvedVideoSource> replacement = Sinks.one(); when(f.api.resolveSource(any())).thenReturn(replacement.asMono());
        f.advance(4999); f.act(f.player::tick); verify(f.api, times(2)).resolveSource(any());
        f.advance(1); f.act(f.player::tick); verify(f.api, times(3)).resolveSource(any());
        f.advance(1000); assertEquals(12000, f.state().nowPlaying().currentPosition());
        f.act(() -> f.player.togglePause("SYSTEM")); replacement.tryEmitValue(f.knownSource(7200000));
        assertEquals(playback, f.state().nowPlaying().playbackId()); assertEquals(2, f.state().nowPlaying().sourceRevision());
        assertEquals(12000, f.state().nowPlaying().currentPosition()); assertEquals("PAUSED", f.state().playIntent());
        assertTrue(f.state().queue().isEmpty());
    }

    @Test
    void credentialFailuresDoNotPollUntilExplicitCookieUpdateEvenIfTheSourceExpires() {
        Fixture f = new Fixture(); when(f.api.resolveSource(any())).thenAnswer(call -> Mono.just(f.knownSource(65000)));
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(6000);
        String playback = f.state().nowPlaying().playbackId();
        when(f.api.resolveSource(any())).thenReturn(Mono.error(new BilibiliVideoApiService.VideoSourceException(
                BilibiliVideoApiService.FailureCode.CREDENTIALS_INVALID, "凭据失效")));
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK_ERROR"));
        f.act(() -> f.player.togglePause("SYSTEM")); f.advance(60000); f.act(f.player::tick);
        verify(f.api, times(2)).resolveSource(any());
        when(f.api.resolveSource(any())).thenAnswer(call -> Mono.just(f.knownSource(7200000)));
        f.act(f.player::cookieChanged); f.act(f.player::tick); verify(f.api, times(3)).resolveSource(any());
        assertEquals(playback, f.state().nowPlaying().playbackId()); assertEquals("PAUSED", f.state().playIntent());
        assertEquals(6000, f.state().nowPlaying().currentPosition());
    }

    @Test
    void updatingCredentialsRearmsAFailedFreshSourceOnceWithoutChangingPauseIntent() {
        Fixture f = new Fixture(); when(f.api.resolveSource(any())).thenAnswer(call -> Mono.just(f.knownSource(7200000)));
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(6000);
        String playback = f.state().nowPlaying().playbackId();
        when(f.api.resolveSource(any())).thenReturn(Mono.error(new BilibiliVideoApiService.VideoSourceException(
                BilibiliVideoApiService.FailureCode.CREDENTIALS_INVALID, "凭据失效")));
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK_ERROR"));
        f.act(() -> f.player.togglePause("SYSTEM")); f.advance(6000); f.act(f.player::tick);
        verify(f.api, times(2)).resolveSource(any());
        when(f.api.resolveSource(any())).thenAnswer(call -> Mono.just(f.knownSource(7200000)));
        f.act(f.player::cookieChanged); f.act(f.player::tick); verify(f.api, times(3)).resolveSource(any());
        f.advance(10000); f.act(f.player::tick); verify(f.api, times(3)).resolveSource(any());
        assertEquals("PAUSED", f.state().playIntent()); assertEquals(6000, f.state().nowPlaying().currentPosition());
    }

    @Test
    void waitingFreezesEffectivePlaybackWhileKeepingIntentAndFreshReadyReleasesIt() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.watch(1, "READY", null); f.advance(100); f.watch(2, "BUFFERING", null);
        f.advance(1999); f.act(f.player::tick); assertNull(f.state().videoWait());
        f.advance(1); f.act(f.player::tick); VideoWaitState wait = f.state().videoWait();
        assertNotNull(wait); assertTrue(f.state().isPaused()); assertEquals("PLAYING", f.state().playIntent());
        f.advance(1000); assertEquals(2100, f.state().nowPlaying().currentPosition());
        f.watch(3, "READY", wait); assertNull(f.state().videoWait()); assertFalse(f.state().isPaused());
        f.advance(1000); assertEquals(3100, f.state().nowPlaying().currentPosition());
    }

    @Test
    void manualPauseCancelsBarrierAndLateReadyNeverRestoresIntent() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.watch(1, "READY", null); f.advance(100); f.watch(2, "BUFFERING", null); f.advance(2000); f.act(f.player::tick);
        VideoWaitState wait = f.state().videoWait();
        f.act(() -> f.player.togglePause("SYSTEM")); assertNull(f.state().videoWait());
        f.advance(100); f.watch(3, "READY", wait); f.advance(16000); f.act(f.player::tick);
        assertTrue(f.state().isPaused()); assertEquals("PAUSED", f.state().playIntent());
        assertEquals(2100, f.state().nowPlaying().currentPosition());
    }

    @Test
    void soleParticipantLeavingAtCreationBoundaryNeverMovesTheTimelineBackwards() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.watch(1, "READY", null); f.advance(100); f.watch(2, "BUFFERING", null);
        f.advance(2000); f.watch(3, "INACTIVE", null);
        assertNull(f.state().videoWait()); assertEquals(2100, f.state().nowPlaying().currentPosition());
        assertFalse(f.state().isPaused()); f.advance(1000); assertEquals(3100, f.state().nowPlaying().currentPosition());
    }

    @Test
    void selectingTrustedPageStartsNewInstanceAtZeroAndKeepsRateAndPauseIntent() {
        Fixture f = new Fixture();
        VideoDescriptor first = new VideoDescriptor(BV + ":p1", BV, 123, 1, "Title", "First", "Owner", 60000, null,
                List.of(new VideoPageDescriptor(BV + ":p1", 123, 1, "First", 60000, true),
                        new VideoPageDescriptor(BV + ":p2", 124, 2, "Second", 45000, true)));
        when(f.api.getVideoDescriptor(anyString())).thenReturn(Mono.just(first));
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(6000);
        f.act(() -> f.player.setPlaybackRate(1.5, "owner-session", true));
        f.act(() -> f.player.togglePause("SYSTEM")); String prior = f.state().nowPlaying().playbackId();
        Sinks.One<ResolvedVideoSource> next = Sinks.one(); when(f.api.resolveSource(any())).thenReturn(next.asMono());
        f.act(() -> f.player.switchPage(2, "owner-session", true));
        assertNotEquals(prior, f.state().nowPlaying().playbackId());
        assertEquals(BV + ":p2", f.state().nowPlaying().music().id()); assertNull(f.state().nowPlaying().videoSource());
        assertEquals(0, f.state().nowPlaying().currentPosition()); assertEquals(1.5, f.state().playbackRate());
        next.tryEmitValue(f.source()); assertEquals(0, f.state().nowPlaying().currentPosition()); assertTrue(f.state().isPaused());
        assertEquals(1, f.state().nowPlaying().sourceRevision());
    }

    @Test
    void renewalDuringWaitInvalidatesReadyWithoutMovingFreezeOrDeadline() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.advance(6000);
        f.watch(1, "READY", null); f.advance(100); f.watch(2, "BUFFERING", null); f.advance(2000); f.act(f.player::tick);
        VideoWaitState prior = f.state().videoWait(); String playback = f.state().nowPlaying().playbackId();
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK"));
        VideoWaitState current = f.state().videoWait();
        assertEquals(prior.waitId(), current.waitId()); assertEquals(prior.deadlineAt(), current.deadlineAt());
        assertEquals(prior.frozenPosition(), current.frozenPosition()); assertEquals(prior.waitRevision() + 1, current.waitRevision());
        f.advance(100); f.watch(3, "READY", prior); assertNotNull(f.state().videoWait());
        f.advance(100); f.watch(4, "READY", current); assertNull(f.state().videoWait());
    }

    @Test
    void oneShotAlarmEnforcesTwoAndFifteenSecondBoundariesWithoutPeriodicTick() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM"));
        f.watch(1, "READY", null); f.advance(100); f.watch(2, "BUFFERING", null);
        assertEquals(2_100_000_000L, f.alarms.nextDue()); assertEquals(1, f.alarms.pending());
        f.advance(1999); f.alarms.fireDue(); assertNull(f.state().videoWait());
        f.advance(1); f.alarms.fireDue(); VideoWaitState wait = f.state().videoWait(); assertNotNull(wait);
        for (int i = 0; i < 14; i++) { f.advance(1000); f.watch(3 + i, "BUFFERING", null); }
        assertEquals(17_100_000_000L, f.alarms.nextDue());
        f.advance(999); f.alarms.fireDue(); assertNotNull(f.state().videoWait());
        f.advance(1); f.alarms.fireDue(); assertNull(f.state().videoWait()); assertFalse(f.state().isPaused());
        assertEquals(1, f.sink.take(MediaMode.VIDEO).events().stream().filter(VideoWaitTimeoutEvent.class::isInstance).count());
        assertEquals(0, f.alarms.pending()); assertEquals(0, f.mailbox.reservedCompletionCount());
    }

    @Test
    void sourceCookieSeekAndRateChangesKeepOneAlarmAndTheOriginalDeadline() {
        Fixture f = new Fixture(); f.properties.getPlayer().setSeekPolicy("OWNER_AND_ENQUEUER");
        f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.watch(1, "READY", null);
        f.advance(100); f.watch(2, "BUFFERING", null); f.advance(2000); f.alarms.fireDue();
        VideoWaitState original = f.state().videoWait(); f.advance(3000); f.watch(3, "BUFFERING", null);
        String playback = f.state().nowPlaying().playbackId();
        f.act(() -> f.player.refreshCurrentSource("owner-session", playback, 1, "MEDIA_NETWORK_ERROR"));
        f.act(() -> f.player.setPlaybackRate(1.5, "owner-session", true));
        f.act(() -> f.player.seek(new SeekRequest(playback, 12000L), "owner-session", true));
        Long alarmAt = f.alarms.nextDue(); f.act(f.player::cookieChanged);
        assertEquals(alarmAt, f.alarms.nextDue()); assertEquals(1, f.alarms.pending());
        assertEquals(1, f.mailbox.reservedCompletionCount());
        assertEquals(original.waitId(), f.state().videoWait().waitId());
        assertEquals(original.deadlineAt(), f.state().videoWait().deadlineAt());
        assertEquals(12000, f.state().videoWait().frozenPosition()); assertEquals(2, f.state().videoWait().sourceRevision());
        f.act(f.player::close); assertEquals(0, f.alarms.pending()); assertEquals(0, f.mailbox.reservedCompletionCount());
    }

    @Test
    void manualPauseCancelsAlarmReservationAndLateScheduledCallbackCannotResumeOrNotify() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.watch(1, "READY", null);
        f.advance(100); f.watch(2, "BUFFERING", null); f.advance(2000); f.alarms.fireDue();
        FakeAlarmScheduler.Alarm late = f.alarms.nextAlarm(); f.act(() -> f.player.togglePause("SYSTEM"));
        assertEquals(0, f.alarms.pending()); assertEquals(0, f.mailbox.reservedCompletionCount());
        f.advance(20000); late.callback.run(); assertEquals("PAUSED", f.state().playIntent());
        assertEquals(2100, f.state().nowPlaying().currentPosition());
        assertEquals(0, f.sink.take(MediaMode.VIDEO).events().stream().filter(VideoWaitTimeoutEvent.class::isInstance).count());
    }

    @Test
    void newPlaybackDiscardsAnAlreadyScheduledOldInstanceCallback() {
        Fixture f = new Fixture(); f.enqueue(BV); f.enqueue(BV2); f.act(() -> f.player.togglePause("SYSTEM"));
        f.watch(1, "READY", null); f.advance(100); f.watch(2, "BUFFERING", null);
        FakeAlarmScheduler.Alarm late = f.alarms.nextAlarm(); String old = f.state().nowPlaying().playbackId();
        f.act(() -> f.player.skipToNext("SYSTEM")); f.advance(2000); late.callback.run();
        assertNotEquals(old, f.state().nowPlaying().playbackId()); assertEquals(BV2 + ":p1", f.state().nowPlaying().music().id());
        assertNull(f.state().videoWait()); assertEquals(2000, f.state().nowPlaying().currentPosition());
        assertEquals(0, f.mailbox.reservedCompletionCount());
    }

    @Test
    void watchingLeaseHeartbeatsOnlyPublishWhenPublicBarrierStateChanges() {
        Fixture f = new Fixture(); f.enqueue(BV); f.act(() -> f.player.togglePause("SYSTEM")); f.sink.take(MediaMode.VIDEO);
        f.watch(1, "READY", null); assertFalse(f.sink.take(MediaMode.VIDEO).stateDirty());
        f.advance(1000); f.watch(2, "READY", null); assertFalse(f.sink.take(MediaMode.VIDEO).stateDirty());
        f.advance(100); f.watch(3, "BUFFERING", null); assertFalse(f.sink.take(MediaMode.VIDEO).stateDirty());
        f.advance(2000); f.alarms.fireDue(); assertTrue(f.sink.take(MediaMode.VIDEO).stateDirty());
        f.advance(1000); f.watch(4, "BUFFERING", null); assertFalse(f.sink.take(MediaMode.VIDEO).stateDirty());
        f.advance(100); f.watch(5, "READY", f.state().videoWait()); assertTrue(f.sink.take(MediaMode.VIDEO).stateDirty());
    }

    static class Fixture {
        final AppProperties properties = new AppProperties();
        final BilibiliVideoApiService api = mock(BilibiliVideoApiService.class);
        final BilibiliMusicApiService playlists = mock(BilibiliMusicApiService.class);
        final UserService users = mock(UserService.class);
        final MusicQueueManager queue = new VideoQueueManager(properties);
        final PlaybackMailbox mailbox = new PlaybackMailbox("test-video-room", 256, Runnable::run);
        final AtomicLong nanos = new AtomicLong();
        final AtomicLong wall = new AtomicLong(1791086400000L);
        final PlaybackEventSink sink = new PlaybackEventSink();
        final FakeAlarmScheduler alarms = new FakeAlarmScheduler(nanos);
        final VideoPlayerService player;
        Fixture() {
            User owner = new User("owner", "owner-session", "Owner");
            when(users.getUser("owner-session")).thenReturn(java.util.Optional.of(owner));
            when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of("owner"));
            when(users.getOnlineUserSummaries()).thenReturn(List.of(new UserSummary("owner", "owner-session", "Owner", false)));
            when(api.getVideoDescriptor(anyString())).thenAnswer(call -> Mono.just(descriptor(call.getArgument(0))));
            when(api.resolveSource(any())).thenAnswer(call -> Mono.just(source()));
            when(api.resolveCurrentInstanceSource(any())).thenAnswer(call -> api.resolveSource(call.getArgument(0)));
            when(api.isCookieConfigured()).thenReturn(true);
            player = new VideoPlayerService(queue, users, properties, api, playlists, mailbox, sink, nanos::get, wall::get, alarms);
            act(player::activatePaused);
        }
        VideoDescriptor descriptor(String bvid) {
            if (bvid.endsWith(":p1")) bvid = bvid.substring(0, bvid.length() - 3);
            return new VideoDescriptor(bvid + ":p1", bvid, 123, 1, "Title", "First", "Owner", 60000, null);
        }
        ResolvedVideoSource source() { return new ResolvedVideoSource(URL, List.of(), "mp4", 16, 60000, Instant.ofEpochMilli(wall.get()), null); }
        ResolvedVideoSource knownSource(long lifetimeMs) {
            long deadline = (wall.get() + lifetimeMs) / 1000;
            return new ResolvedVideoSource(URL + "?deadline=" + deadline, List.of(), "mp4", 16, 60000,
                    Instant.ofEpochMilli(wall.get()), Instant.ofEpochSecond(deadline));
        }
        void act(Runnable work) { mailbox.submit(work).join(); }
        void enqueue(String id) { act(() -> player.enqueue(new EnqueueRequest("bilibili-video", id), "owner-session")); }
        PlayerState state() { return mailbox.submit(player::getCurrentPlayerState).join(); }
        List<MusicQueueItem> snapshot() { return mailbox.submit(player::getPersistenceQueueSnapshot).join(); }
        void advance(long millis) { wall.addAndGet(millis); nanos.addAndGet(millis * 1_000_000); }
        void watch(long sequence, String status, VideoWaitState wait) {
            PlayerState state = state();
            act(() -> player.watchStatus("owner-session", "verified-watcher", "Owner", new VideoWatchStatusRequest(
                    state.nowPlaying().playbackId(), state.nowPlaying().sourceRevision(), 0, sequence, status,
                    true, true, true, wait == null ? null : wait.waitId(), wait == null ? null : wait.waitRevision(),
                    wait == null ? state.nowPlaying().currentPosition() : wait.frozenPosition())));
        }
    }

    static class FakeAlarmScheduler implements VideoWaitAlarmScheduler {
        final AtomicLong now;
        final java.util.ArrayList<Alarm> alarms = new java.util.ArrayList<>();
        FakeAlarmScheduler(AtomicLong now) { this.now = now; }
        public Disposable schedule(long delay, Runnable callback) {
            Alarm alarm = new Alarm(now.get() + Math.max(1, delay), callback); alarms.add(alarm); return alarm;
        }
        int pending() { return (int) alarms.stream().filter(a -> !a.disposed).count(); }
        Alarm nextAlarm() { return alarms.stream().filter(a -> !a.disposed).min(java.util.Comparator.comparingLong(a -> a.due)).orElse(null); }
        Long nextDue() { Alarm alarm = nextAlarm(); return alarm == null ? null : alarm.due; }
        void fireDue() {
            for (;;) { Alarm alarm = nextAlarm(); if (alarm == null || alarm.due > now.get()) return;
                alarm.disposed = true; alarm.callback.run(); }
        }
        static class Alarm implements Disposable {
            final long due; final Runnable callback; boolean disposed;
            Alarm(long due, Runnable callback) { this.due = due; this.callback = callback; }
            public void dispose() { disposed = true; }
            public boolean isDisposed() { return disposed; }
        }
    }
}
