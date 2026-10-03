package org.thornex.musicparty.config;

import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.room.*;
import java.util.*;

@Component
public class WebSocketAuthInterceptor implements ExecutorChannelInterceptor {
    private final RoomAccessService access;
    private final ThreadLocal<RoomContext> context = new ThreadLocal<>();
    private static final Set<String> SENDS=Set.of("/app/player/resync","/app/enqueue","/app/enqueue/playlist","/app/control/next","/app/control/toggle-shuffle","/app/control/toggle-pause","/app/control/seek","/app/queue/top","/app/queue/remove","/app/control/like","/app/user/rename","/app/user/bind","/app/chat","/app/chat/history/fetch");
    private static final Set<String> PRIVATE=Set.of("/app/user/me","/app/user/profile","/app/chat/history","/app/topic/player/state","/app/topic/users/online","/user/queue/me","/user/queue/profile","/user/queue/player/state","/user/queue/chat/history","/user/queue/events","/user/queue/chat/private");
    public WebSocketAuthInterceptor(RoomAccessService access) { this.access=access; }
    @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
        var a=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(a==null || a.getCommand()==null) return message;
        if(a.getCommand()==StompCommand.CONNECT) {
            String room=a.getFirstNativeHeader("room-id");
            access.connect(a.getSessionId(),room,a.getFirstNativeHeader("room-token"),a.getFirstNativeHeader("management-token"));
        } else if(a.getCommand()==StompCommand.SEND || a.getCommand()==StompCommand.SUBSCRIBE) {
            var c=access.connection(a.getSessionId());String d=a.getDestination();
            if(d==null) throw new MessageDeliveryException("ACCESS_DENIED");
            if(a.getCommand()==StompCommand.SEND && !SENDS.contains(d)) throw new MessageDeliveryException("ACCESS_DENIED");
            if(a.getCommand()==StompCommand.SUBSCRIBE && !PRIVATE.contains(d) && !d.matches("/topic/rooms/"+c.roomId()+"/(player/(state|queue|events)|users/online|chat|lifecycle|pairing|share)")) throw new MessageDeliveryException("ACCESS_DENIED");
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
