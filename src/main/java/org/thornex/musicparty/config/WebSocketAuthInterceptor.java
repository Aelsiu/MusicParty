package org.thornex.musicparty.config;

import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.room.*;
import java.util.*;

@Component
public class WebSocketAuthInterceptor implements ExecutorChannelInterceptor {
    public static final String MEMBER_REGISTERED_ATTRIBUTE = "musicParty.memberRegistered";
    private final RoomAccessService access;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.thornex.musicparty.playback.RoomPlaybackCoordinator playback;
    private final ThreadLocal<RoomContext> context = new ThreadLocal<>();
    private static final Set<String> SENDS=Set.of("/app/video/control/recover","/app/video/import/favorites","/app/video/import/parts","/app/video/import/episodes","/app/video/import/xifan","/app/video/import/status","/app/video/interaction/resync","/app/control/media-mode","/app/video/source/refresh","/app/video/watch/status","/app/video/control/rate","/app/video/control/page","/app/player/clock","/app/player/resync","/app/enqueue","/app/enqueue/playlist","/app/control/next","/app/control/toggle-shuffle","/app/control/toggle-pause","/app/control/seek","/app/queue/cancel-top","/app/queue/top","/app/queue/remove","/app/control/like","/app/user/rename","/app/user/bind","/app/chat","/app/chat/history/fetch");
    private static final Set<String> PRIVATE=Set.of("/user/queue/playback/command-result","/user/queue/video/import","/user/queue/video/interaction","/user/queue/player/clock","/app/user/me","/app/user/profile","/app/chat/history","/app/topic/player/state","/app/topic/users/online","/user/queue/me","/user/queue/profile","/user/queue/player/state","/user/queue/chat/history","/user/queue/events","/user/queue/chat/private");
    public WebSocketAuthInterceptor(RoomAccessService access) { this.access=access; }
    @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
        var a=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(a==null || a.getCommand()==null) return message;
        if(a.getCommand()==StompCommand.CONNECT) {
            if (!"3".equals(a.getFirstNativeHeader("playback-protocol"))) throw new MessageDeliveryException("CLIENT_UPGRADE_REQUIRED");
            String room=a.getFirstNativeHeader("room-id");
            String watcher = a.getSessionAttributes() == null ? null : (String)a.getSessionAttributes().get(VideoWatcherIdentity.ATTRIBUTE);
            access.connect(a.getSessionId(),room,a.getFirstNativeHeader("room-token"),a.getFirstNativeHeader("management-token"),watcher);
            if (playback != null) {
                try (var ignored = RoomContext.enter(room)) {
                    // This runs before inbound handlers or CONNECTED can expose /user/me to the client.
                    playback.joinMember(a.getSessionId(),a.getFirstNativeHeader("user-token"),a.getFirstNativeHeader("user-name"));
                    if (a.getSessionAttributes() != null) a.getSessionAttributes().put(MEMBER_REGISTERED_ATTRIBUTE, Boolean.TRUE);
                } catch (RuntimeException failure) {
                    access.disconnect(a.getSessionId());
                    throw new MessageDeliveryException(message,"ROOM_MEMBER_INITIALIZATION_FAILED",failure);
                }
            }
        } else if(a.getCommand()==StompCommand.SEND || a.getCommand()==StompCommand.SUBSCRIBE) {
            var c=access.connection(a.getSessionId());String d=a.getDestination();
            if(d==null) throw new MessageDeliveryException("ACCESS_DENIED");
            if(a.getCommand()==StompCommand.SEND && !SENDS.contains(d)) throw new MessageDeliveryException("ACCESS_DENIED");
            if(a.getCommand()==StompCommand.SUBSCRIBE && !PRIVATE.contains(d) && !d.matches("/topic/rooms/"+c.roomId()+"/(player/(state|queue|events)|users/online|chat|lifecycle|pairing|share|metadata)")) throw new MessageDeliveryException("ACCESS_DENIED");
        }
        return message;
    }
    @Override public Message<?> beforeHandle(Message<?> message,MessageChannel channel,MessageHandler handler) {
        String session=(String)message.getHeaders().get("simpSessionId");
        if(session!=null) {
            try { context.set(RoomContext.enter(access.connection(session).roomId())); }
            catch(RuntimeException e) { if(message.getHeaders().get("stompCommand")!=StompCommand.DISCONNECT) throw new MessageDeliveryException("ROOM_ACCESS_EXPIRED"); }
        }
        return message;
    }
    @Override public void afterMessageHandled(Message<?> message,MessageChannel channel,MessageHandler handler,Exception ex) { var c=context.get();if(c!=null)c.close();context.remove(); }
}
