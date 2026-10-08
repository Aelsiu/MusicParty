package org.thornex.musicparty.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.thornex.musicparty.controller.MusicSocketController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.playback.*;
import org.thornex.musicparty.room.*;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketIdentityBootstrapTest {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PlaybackMailbox mailbox = new PlaybackMailbox("room", 32, executor);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final UserService users = spy(new UserService(publisher));
    private final MusicPlayerService music = mock(MusicPlayerService.class);
    private final VideoPlayerService video = mock(VideoPlayerService.class);
    private final QueuePersistenceService persistence = mock(QueuePersistenceService.class);
    private final RoomAccessService access = mock(RoomAccessService.class);
    private final Map<String, RoomAccessService.Connection> connections = new ConcurrentHashMap<>();
    private final CountDownLatch releaseBootstrap = new CountDownLatch(1);
    private RoomPlaybackCoordinator coordinator;
    private WebSocketAuthInterceptor interceptor;
    private MusicSocketController controller;

    private PlayerState raw() {
        return new PlayerState(null, List.of(), "SEQUENTIAL", false, true, false, List.of(), true,
                false, false, false, false, 0, false, false, .5, 15, 0, 0,
                new PlayerState.AppConfigSummary(100, 100, 10, 100, 100, 0, 100, "standard", true,
                        true, 10, false, .5, 15, false,
                        new PlayerState.AppConfigSummary.PrivateDjConfigSummary("OFF", false, false, false), "DISABLED"));
    }

    @BeforeEach void setup() {
        when(persistence.restoredMode()).thenReturn("MUSIC");
        when(music.getCurrentPlayerState()).thenReturn(raw());
        when(video.getCurrentPlayerState()).thenReturn(raw());
        doAnswer(call -> {
            connections.put(call.getArgument(0), new RoomAccessService.Connection(call.getArgument(1), call.getArgument(3), call.getArgument(2)));
            return null;
        }).when(access).connect(anyString(), anyString(), any(), any(), any());
        when(access.connection(anyString())).thenAnswer(call -> {
            var value = connections.get(call.getArgument(0));
            if (value == null) throw new IllegalStateException("ROOM_ACCESS_EXPIRED");
            return value;
        });
        when(access.disconnect(anyString())).thenAnswer(call -> connections.remove(call.getArgument(0)));
        var messaging = mock(SimpMessagingTemplate.class);
        coordinator = new RoomPlaybackCoordinator(mailbox, new PlaybackEventSink(), music, video, persistence, users,
                access, mock(LiveStreamService.class), messaging, publisher);
        interceptor = new WebSocketAuthInterceptor(access);
        ReflectionTestUtils.setField(interceptor, "playback", coordinator);
        controller = new MusicSocketController(coordinator, users, messaging, mock(ChatService.class), access);
    }

    @AfterEach void close() {
        releaseBootstrap.countDown();
        mailbox.closeNow();
        users.stop();
        executor.shutdownNow();
    }

    private Message<byte[]> connect(String session, String name) {
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId(session);
        headers.setSessionAttributes(new ConcurrentHashMap<>());
        headers.setNativeHeader("playback-protocol", "3");
        headers.setNativeHeader("room-id", "room");
        headers.setNativeHeader("room-token", "admission");
        headers.setNativeHeader("user-token", "profile-" + session);
        headers.setNativeHeader("user-name", name);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test void coldConnectCannotBeAdmittedBeforeBootstrapAndIdentityCommitAndListenerDoesNotRegisterTwice() throws Exception {
        CountDownLatch bootstrapEntered = new CountDownLatch(1);
        doAnswer(call -> {
            assertEquals("room", RoomContext.current());
            bootstrapEntered.countDown();
            assertTrue(releaseBootstrap.await(2, TimeUnit.SECONDS));
            return null;
        }).when(persistence).ensureLoaded();
        Message<byte[]> frame = connect("owner", "房主");
        CompletableFuture<Message<?>> admitted = CompletableFuture.supplyAsync(() -> interceptor.preSend(frame, null), executor);
        assertTrue(bootstrapEntered.await(2, TimeUnit.SECONDS));
        assertFalse(admitted.isDone());
        assertTrue(users.getUser("owner").isEmpty());
        releaseBootstrap.countDown();
        assertSame(frame, admitted.get(2, TimeUnit.SECONDS));
        UserSummary me = controller.getMyUserInfo("owner");
        assertEquals("房主", me.name());
        assertFalse(me.isGuest());
        assertEquals("profile-owner", me.token());
        assertEquals("房主", controller.getSharedProfile("owner").get("name"));
        assertTrue(Boolean.TRUE.equals(StompHeaderAccessor.wrap(frame).getSessionAttributes().get(WebSocketAuthInterceptor.MEMBER_REGISTERED_ATTRIBUTE)));
        var lifecycle = mock(RoomLifecycleService.class);
        var listener = new WebSocketEventListener(users, music, access, mock(RoomRepository.class), lifecycle);
        ReflectionTestUtils.setField(listener, "playback", coordinator);
        listener.connected(new SessionConnectEvent(this, frame));
        verify(users, times(1)).handleConnect("owner", "profile-owner", "房主");
        verify(lifecycle).joined("room");
        assertNull(RoomContext.current());
    }

    @Test void meAndProfileReadAfterAcceptedRoomWorkAndGuestsRemainReadable() throws Exception {
        interceptor.preSend(connect("guest", "游客"), null);
        assertTrue(controller.getMyUserInfo("guest").isGuest());
        CountDownLatch updating = new CountDownLatch(1), releaseUpdate = new CountDownLatch(1);
        var mutation = coordinator.internal(() -> {
            updating.countDown();
            try { assertTrue(releaseUpdate.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new IllegalStateException(error); }
            User user = users.getUser("guest").orElseThrow();
            user.setName("已命名成员"); user.setGuest(false); user.getBindings().put("bilibili", "123");
        });
        assertTrue(updating.await(2, TimeUnit.SECONDS));
        var me = CompletableFuture.supplyAsync(() -> controller.getMyUserInfo("guest"), executor);
        var profile = CompletableFuture.supplyAsync(() -> controller.getSharedProfile("guest"), executor);
        try {
            assertThrows(TimeoutException.class, () -> me.get(50, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> profile.get(50, TimeUnit.MILLISECONDS));
        } finally { releaseUpdate.countDown(); }
        mutation.get(2, TimeUnit.SECONDS);
        assertEquals("已命名成员", me.get(2, TimeUnit.SECONDS).name());
        assertFalse(me.get().isGuest());
        assertEquals(Map.of("bilibili", "123"), profile.get(2, TimeUnit.SECONDS).get("bindings"));
    }

    @Test void failedColdInitializationRevokesAccessAndNeverLeavesAnOnlineUser() {
        doThrow(new IllegalStateException("Snapshot could not be restored")).when(persistence).ensureLoaded();
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(connect("owner", "房主"), null));
        assertFalse(connections.containsKey("owner"));
        assertTrue(users.getUser("owner").isEmpty());
        verify(users, never()).handleConnect(anyString(), any(), any());
    }

    @Test void memberReadRejectsAConnectionFromAnotherRoom() {
        connections.put("wrong-room", new RoomAccessService.Connection("other", null, "profile"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.getMyUserInfo("wrong-room"));
    }
}
