package org.thornex.musicparty.playback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.room.RoomAccessService;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomPlaybackCoordinatorTest {
    private final PlaybackMailbox mailbox = new PlaybackMailbox("room", 32, Runnable::run);
    private final PlaybackEventSink sink = new PlaybackEventSink();
    private final MusicPlayerService music = mock(MusicPlayerService.class);
    private final VideoPlayerService video = mock(VideoPlayerService.class);
    private final QueuePersistenceService persistence = mock(QueuePersistenceService.class);
    private final UserService users = mock(UserService.class);
    private final RoomAccessService access = mock(RoomAccessService.class);
    private final LiveStreamService stream = mock(LiveStreamService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private RoomPlaybackCoordinator coordinator;

    private PlayerState raw() {
        return new PlayerState(null, List.of(), "SEQUENTIAL", false, true, false, List.of(),
                true, false, false, false, false, 0, false, false, .5, 15, 0, 0,
                new PlayerState.AppConfigSummary(100, 100, 10, 100, 100, 0, 100, "standard",
                        true, true, 10, false, .5, 15, false,
                        new PlayerState.AppConfigSummary.PrivateDjConfigSummary("OFF", false, false, false), "DISABLED"));
    }
    @BeforeEach void setup() {
        when(persistence.restoredMode()).thenReturn("MUSIC");
        when(music.getCurrentPlayerState()).thenReturn(raw());
        when(video.getCurrentPlayerState()).thenReturn(raw());
        when(video.getSourceStatus()).thenReturn("EMPTY");
        when(users.getUser("owner")).thenReturn(Optional.of(new User("u", "owner", "房主")));
        when(access.connection("owner")).thenReturn(new RoomAccessService.Connection("room", "manager-session", "u"));
        coordinator = new RoomPlaybackCoordinator(mailbox, sink, music, video, persistence, users, access, stream, messaging, publisher);
        coordinator.read();
        clearInvocations(music, video, persistence, stream, publisher, messaging);
    }
    private ObjectNode context(String requestId) {
        PlayerState state = coordinator.read();
        return mapper.createObjectNode().put("requestId", requestId).put("expectedPlaybackEpoch", state.playbackEpoch())
                .put("expectedMode", state.mediaMode()).put("expectedModeEpoch", state.modeEpoch()).putNull("playbackId");
    }

    @Test void failedStrictSaveCannotFreezeEnginesChangeModeOrPublishSuccess() throws Exception {
        PlayerState before = coordinator.read();
        doThrow(new IOException("SQLite write failed")).when(persistence).saveStrict("VIDEO");
        ObjectNode request = context("switch-failed").put("mode", "VIDEO");
        clearInvocations(publisher);
        var result = coordinator.command("owner", "mode", request).join();
        assertEquals(false, result.get("ok"));
        PlayerState after = coordinator.read();
        assertEquals("MUSIC", after.mediaMode());
        assertEquals(before.modeEpoch(), after.modeEpoch());
        assertEquals(before.queueRevision(), after.queueRevision());
        verify(music, never()).freezeForModeSwitch();
        verify(video, never()).activatePaused();
        verify(publisher, never()).publishEvent(isA(CommittedPlaybackNotice.class));
    }

    @Test void modeSwitchSavesFirstPublishesOnlyTargetAndAdvancesBothVersions() throws Exception {
        PlayerState before = coordinator.read();
        var result = coordinator.command("owner", "mode", context("switch-ok").put("mode", "VIDEO")).join();
        assertEquals(true, result.get("ok"));
        var order = inOrder(persistence, music, video, stream);
        order.verify(persistence).saveStrict("VIDEO");
        order.verify(music).freezeForModeSwitch();
        order.verify(music).setActive(false);
        order.verify(video).setActive(true);
        order.verify(stream).setPlaybackMode(false);
        order.verify(video).activatePaused();
        PlayerState after = coordinator.read();
        assertEquals("VIDEO", after.mediaMode());
        assertEquals(before.modeEpoch() + 1, after.modeEpoch());
        assertTrue(after.stateRevision() > before.stateRevision());
        assertEquals(before.queueRevision() + 1, after.queueRevision());
        var event = org.mockito.ArgumentCaptor.forClass(PlayerStateEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals("VIDEO", event.getValue().getState().mediaMode());
        assertTrue(event.getValue().getState().isPaused());
    }

    @Test void duplicateRequestNeverTogglesTwiceAndDifferentParametersAreRejected() {
        ObjectNode request = context("pause-once");
        assertEquals(true, coordinator.command("owner", "pause", request).join().get("ok"));
        assertEquals(true, coordinator.command("owner", "pause", request.deepCopy()).join().get("ok"));
        verify(music, times(1)).togglePause("owner");
        assertEquals("REQUEST_ID_CONFLICT", coordinator.command("owner", "pause", request.deepCopy().put("position", 1000)).join().get("code"));
        verify(music, times(1)).togglePause("owner");
    }

    @Test void delayedOldModeCommandCannotAffectNewModeAndOnlyActiveDirtyCanPublish() {
        ObjectNode delayed = context("old-pause");
        coordinator.command("owner", "mode", context("switch").put("mode", "VIDEO")).join();
        assertEquals("STALE_PLAYBACK_CONTEXT", coordinator.command("owner", "pause", delayed).join().get("code"));
        verify(music, never()).togglePause(anyString());
        verify(video, never()).togglePause(anyString());
        clearInvocations(publisher);
        coordinator.internal(() -> sink.markQueueDirty(MediaMode.MUSIC)).join();
        verify(publisher, never()).publishEvent(isA(PlayerStateEvent.class));
    }

    @Test void lifecycleFinalSaveFailureStillCancelsBothEnginesAndClosesMailbox() throws Exception {
        doThrow(new IOException("SQLite unavailable")).when(persistence).saveStrict("MUSIC");
        assertDoesNotThrow(() -> coordinator.close(false));
        verify(music).invalidateAsyncForClose();
        verify(video).invalidateAsyncForClose();
        verify(stream).suspendForEmptyRoom();
        assertThrows(java.util.concurrent.CompletionException.class, () -> mailbox.submit(() -> fail("room revived")).join());
    }

    @Test void unnamedMemberCannotMutateButGuestResyncRemainsAvailable() {
        User guest = new User("guest", "guest-session", "");
        guest.setGuest(true);
        when(users.getUser("guest-session")).thenReturn(Optional.of(guest));
        when(access.connection("guest-session")).thenReturn(new RoomAccessService.Connection("room", null, "guest"));
        assertEquals("NAMED_MEMBER_REQUIRED", coordinator.command("guest-session", "pause", context("guest-pause")).join().get("code"));
        assertDoesNotThrow(() -> coordinator.resync("guest-session"));
        verify(music, never()).togglePause(anyString());
    }

    @Test void guestWatchObservationUsesVerifiedConnectionAndRejectsOldContext() {
        coordinator.command("owner","mode",context("video").put("mode","VIDEO")).join();
        User guest = new User("arbitrary-profile","guest-session","游客");
        when(users.getUser("guest-session")).thenReturn(Optional.of(guest));
        when(access.connection("guest-session")).thenReturn(new RoomAccessService.Connection("room",null,"admission","verified-browser"));
        ObjectNode report = context("observer").put("playbackId","instance").put("sourceRevision",1)
                .put("sourceGeneration",1).put("sequence",1).put("status","READY")
                .put("synchronizedWithRoom",true).put("foreground",true).put("playbackAllowed",true);
        coordinator.watchStatus("guest-session",report).join();
        verify(video).watchStatus(eq("guest-session"),eq("verified-browser"),eq("游客"),any(VideoWatchStatusRequest.class));
        clearInvocations(video);
        coordinator.watchStatus("guest-session",report.deepCopy().put("expectedModeEpoch",0)).join();
        verify(video,never()).watchStatus(anyString(),anyString(),anyString(),any());
        when(access.connection("guest-session")).thenReturn(new RoomAccessService.Connection("room",null,"admission"));
        coordinator.watchStatus("guest-session",report).join();
        verify(video,never()).watchStatus(anyString(),anyString(),anyString(),any());
        verify(video,never()).togglePause(anyString());
    }

    @Test void httpProtocolTwoMustUpgradeBeforeExecutingAnyAction() {
        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> coordinator.http("manager","2","pause",context("old-http")));
        assertEquals("CLIENT_UPGRADE_REQUIRED",exception.getReason());
        verify(music,never()).togglePause(anyString());
        assertEquals(3,coordinator.read().protocolVersion());
    }

    @Test void interruptedCloseWaitsForItsFinalizerBeforeAllowingScopeDestruction() throws Exception {
        java.util.concurrent.CountDownLatch drainScheduled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Runnable> worker = new java.util.concurrent.atomic.AtomicReference<>();
        PlaybackMailbox queuedMailbox = new PlaybackMailbox("room", 32, task -> { worker.set(task); drainScheduled.countDown(); });
        RoomPlaybackCoordinator queued = new RoomPlaybackCoordinator(queuedMailbox, new PlaybackEventSink(), music, video,
                persistence, users, access, stream, messaging, publisher);
        java.util.concurrent.CountDownLatch closeReturned = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread closer = Thread.ofVirtual().start(() -> {
            try { queued.close(false); interruptPreserved.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable error) { failure.set(error); }
            finally { closeReturned.countDown(); }
        });
        assertTrue(drainScheduled.await(2, java.util.concurrent.TimeUnit.SECONDS));
        closer.interrupt();
        assertFalse(closeReturned.await(50, java.util.concurrent.TimeUnit.MILLISECONDS), "interrupt cannot destroy a scope ahead of final save");
        worker.get().run();
        assertTrue(closeReturned.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(failure.get());
        assertTrue(interruptPreserved.get());
        verify(persistence).saveStrict("MUSIC");
        verify(music).invalidateAsyncForClose();
        verify(video).invalidateAsyncForClose();
    }
}
