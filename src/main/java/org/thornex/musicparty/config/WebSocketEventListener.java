package org.thornex.musicparty.config;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.*;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.room.*;

@Component
public class WebSocketEventListener {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.thornex.musicparty.playback.RoomPlaybackCoordinator playback;
    private final UserService users;
    private final MusicPlayerService player;
    private final RoomAccessService access;
    private final RoomRepository repository;
    private final RoomLifecycleService lifecycle;
    public WebSocketEventListener(UserService users,MusicPlayerService player,RoomAccessService access,RoomRepository repository,RoomLifecycleService lifecycle) { this.users=users;this.player=player;this.access=access;this.repository=repository;this.lifecycle=lifecycle; }
    @EventListener public void connected(SessionConnectEvent event) {
        var h=StompHeaderAccessor.wrap(event.getMessage());var c=access.connection(h.getSessionId());
        try(var ignored=RoomContext.enter(c.roomId())) { lifecycle.joined(c.roomId());
            if (Boolean.TRUE.equals(h.getSessionAttributes() == null ? null : h.getSessionAttributes().get(WebSocketAuthInterceptor.MEMBER_REGISTERED_ATTRIBUTE))) return;
            if (playback != null) { playback.joinMember(h.getSessionId(),h.getFirstNativeHeader("user-token"),h.getFirstNativeHeader("user-name")); return; }
            Runnable joined=() -> { users.handleConnect(h.getSessionId(),h.getFirstNativeHeader("user-token"),h.getFirstNativeHeader("user-name"));
                if(playback!=null) playback.membersChanged(); else player.broadcastOnlineUsers(); };
            joined.run(); }
    }
    @EventListener public void disconnected(SessionDisconnectEvent event) {
        var c=access.disconnect(event.getSessionId());if(c==null || !repository.exists(c.roomId()))return;
        try(var ignored=RoomContext.enter(c.roomId())) { Runnable departed=() -> { if(playback!=null) playback.sessionDisconnected(event.getSessionId()); users.disconnectUser(event.getSessionId());
                if(playback!=null) playback.membersChanged(); else player.broadcastOnlineUsers(); };
            if(playback!=null) playback.internal(departed); else departed.run();
            lifecycle.departed(c.roomId()); }
        catch(org.springframework.beans.factory.support.ScopeNotActiveException e) {
            // Deletion can finish between the existence check and resolving a room-scoped service.
            if(repository.exists(c.roomId())) throw e;
        }
    }
}
