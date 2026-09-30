package org.thornex.musicparty.config;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.*;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.room.*;

@Component
public class WebSocketEventListener {
    private final UserService users;
    private final MusicPlayerService player;
    private final RoomAccessService access;
    private final RoomRepository repository;
    private final RoomLifecycleService lifecycle;
    public WebSocketEventListener(UserService users,MusicPlayerService player,RoomAccessService access,RoomRepository repository,RoomLifecycleService lifecycle) { this.users=users;this.player=player;this.access=access;this.repository=repository;this.lifecycle=lifecycle; }
    @EventListener public void connected(SessionConnectEvent event) {
        var h=StompHeaderAccessor.wrap(event.getMessage());var c=access.connection(h.getSessionId());
        try(var ignored=RoomContext.enter(c.roomId())) { lifecycle.joined(c.roomId());users.handleConnect(h.getSessionId(),h.getFirstNativeHeader("user-token"),h.getFirstNativeHeader("user-name"));player.broadcastOnlineUsers(); }
    }
    @EventListener public void disconnected(SessionDisconnectEvent event) {
        var c=access.disconnect(event.getSessionId());if(c==null || !repository.exists(c.roomId()))return;
        try(var ignored=RoomContext.enter(c.roomId())) { users.disconnectUser(event.getSessionId());player.broadcastOnlineUsers();lifecycle.departed(c.roomId()); }
        catch(org.springframework.beans.factory.support.ScopeNotActiveException e) {
            // Deletion can finish between the existence check and resolving a room-scoped service.
            if(repository.exists(c.roomId())) throw e;
        }
    }
}
