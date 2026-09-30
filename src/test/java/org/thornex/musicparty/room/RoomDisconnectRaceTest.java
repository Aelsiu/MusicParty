package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.thornex.musicparty.config.WebSocketEventListener;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomDisconnectRaceTest {
    @Test void deletionBetweenDisconnectCheckAndScopedLookupFinishesQuietly() {
        check(false);
    }
    @Test void liveRoomFailuresRemainVisible() {
        check(true);
    }
    private void check(boolean stillExists) {
        var access=mock(RoomAccessService.class);var repository=mock(RoomRepository.class);var users=mock(UserService.class);
        when(access.disconnect("session")).thenReturn(new RoomAccessService.Connection("room","",""));
        when(repository.exists("room")).thenReturn(true,stillExists);
        var failure=new ScopeNotActiveException("users","room",new IllegalStateException("deleted"));
        doThrow(failure).when(users).disconnectUser("session");
        var listener=new WebSocketEventListener(users,mock(MusicPlayerService.class),access,repository,mock(RoomLifecycleService.class));
        var event=new SessionDisconnectEvent(this,MessageBuilder.withPayload(new byte[0]).build(),"session",CloseStatus.NORMAL);
        if(stillExists) assertThrows(ScopeNotActiveException.class,()->listener.disconnected(event));
        else assertDoesNotThrow(()->listener.disconnected(event));
        assertNull(RoomContext.current());
    }
}
