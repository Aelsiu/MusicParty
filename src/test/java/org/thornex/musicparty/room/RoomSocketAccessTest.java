package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.thornex.musicparty.config.WebSocketAuthInterceptor;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RoomSocketAccessTest {
    @TempDir Path directory;
    private RoomRepository repository;
    private RoomAccessService access;
    private WebSocketAuthInterceptor interceptor;
    private String room, other;
    @BeforeEach void setup() throws Exception {
        var config=new MultiRoomProperties();config.setRootKey("TestRoot9");
        config.setDatabase(directory.resolve("rooms.sqlite").toString());config.setLicenseFile(directory.resolve("licenses.json").toString());
        repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        room=repository.create("ROOT","房间甲",UUID.randomUUID().toString()).id();
        other=repository.create("ROOT","房间乙",UUID.randomUUID().toString()).id();
        access=new RoomAccessService(repository,config);
        String manager=access.login("TestRoot9","root").token();access.connect("session",room,"",manager);
        interceptor=new WebSocketAuthInterceptor(access);
    }
    @AfterEach void close() throws Exception {repository.close();}
    private Message<?> frame(StompCommand command,String destination) {
        return frame(command,destination,"session");
    }
    private Message<?> frame(StompCommand command,String destination,String session) {
        var headers=StompHeaderAccessor.create(command);headers.setSessionId(session);headers.setDestination(destination);headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders());
    }
    @Test void shareSubscriptionIsAllowedOnlyInsideTheConnectedRoomForManagersAndMembers() {
        String admission=access.join(room,repository.room(room).pairingCode(),"member").token();
        var connect=StompHeaderAccessor.create(StompCommand.CONNECT);connect.setSessionId("member-session");
        connect.setNativeHeader("room-id",room);connect.setNativeHeader("room-token",admission);connect.setLeaveMutable(true);
        assertNotNull(interceptor.preSend(MessageBuilder.createMessage(new byte[0],connect.getMessageHeaders()),null));
        for(String session:new String[]{"session","member-session"}) {
            assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE,"/topic/rooms/"+room+"/share",session),null));
            for(String target:new String[]{"/topic/rooms/"+other+"/share","/topic/share","/topic/rooms/"+room+"/share/extra","/topic/rooms/"+room+"/share?other=true"})
                assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(StompCommand.SUBSCRIBE,target,session),null),target);
        }
    }
    @Test void shareAddsNoSendPermissionAndItsPrivateEventUsesTheExistingSenderQueue() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SEND,"/app/chat"),null));
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE,"/user/queue/events"),null));
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE,"/user/queue/chat/private"),null));
        for(String target:new String[]{"/topic/rooms/"+room+"/share","/topic/rooms/"+other+"/share","/app/share","/user/queue/events","/user/queue/chat/private"})
            assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(StompCommand.SEND,target),null),target);
        for(String target:new String[]{"/user/another/queue/events","/user/another/queue/chat/private","/user/queue/share","/app/share"})
            assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(StompCommand.SUBSCRIBE,target),null),target);
        for(var command:new StompCommand[]{StompCommand.SEND,StompCommand.SUBSCRIBE})
            assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(command,null),null));
    }
    @Test void crossRoomAndLegacyGlobalSubscriptionsAreDenied() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE,"/topic/rooms/"+room+"/chat"),null));
        for(String target:new String[]{"/topic/rooms/"+other+"/chat","/topic/player/state","/topic/rooms/"+room+"/unknown","/user/another/queue/me"})
            assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(StompCommand.SUBSCRIBE,target),null),target);
    }
    @Test void handlersReceiveAndReleaseTheirRoomContext() {
        var message=frame(StompCommand.SEND,"/app/chat");
        interceptor.beforeHandle(message,null,null);assertEquals(room,RoomContext.current());
        interceptor.afterMessageHandled(message,null,null,null);assertNull(RoomContext.current());
        assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(StompCommand.SEND,"/app/rooms/"+other+"/chat"),null));
    }
    @Test void seekingIsAdmittedThroughTheExistingAuthorizedConnection() {
        var message=frame(StompCommand.SEND,"/app/control/seek");
        assertNotNull(interceptor.preSend(message,null));
        interceptor.beforeHandle(message,null,null);assertEquals(room,RoomContext.current());
        interceptor.afterMessageHandled(message,null,null,null);assertNull(RoomContext.current());
        assertThrows(MessageDeliveryException.class,()->interceptor.preSend(frame(StompCommand.SEND,"/app/rooms/"+other+"/control/seek"),null));
    }
}
