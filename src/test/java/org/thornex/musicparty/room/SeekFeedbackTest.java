package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SeekRejectedEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.UserService;
import org.thornex.musicparty.websocket.WebSocketBroadcaster;
import java.util.Optional;
import static org.mockito.Mockito.*;

class SeekFeedbackTest {
    @Test void acceptedJumpNamesItsOperatorAndStaysInItsRoom() {
        var messaging=mock(SimpMessagingTemplate.class);
        var users=mock(UserService.class);
        when(users.getUserByToken("operator-token")).thenReturn(Optional.of(new User("operator-token","session","小明")));
        var broadcaster=new WebSocketBroadcaster(messaging,users);
        try (var ignored=RoomContext.enter("SeekRoom01")) {
            broadcaster.onSystemMessage(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                    PlayerAction.JUMP,"operator-token",null));
        }
        verify(messaging).convertAndSend(eq("/topic/rooms/SeekRoom01/player/events"),
                argThat((Object value) -> value instanceof PlayerEvent e && "JUMP".equals(e.action())
                        && "INFO".equals(e.type()) && "operator-token".equals(e.userId())
                        && "小明 跳转了播放进度".equals(e.message())));
        verifyNoMoreInteractions(messaging);
    }

    @Test void rejectionIsDeliveredOnlyToItsRequestingConnection() {
        var messaging=mock(SimpMessagingTemplate.class);
        var broadcaster=new WebSocketBroadcaster(messaging,mock(UserService.class));
        broadcaster.onSeekRejected(new SeekRejectedEvent("requesting-session","token","房间跳转冷却中"));
        verify(messaging).convertAndSendToUser(eq("requesting-session"),eq("/queue/events"),
                argThat(value -> value instanceof PlayerEvent e && "房间跳转冷却中".equals(e.message())),
                argThat((java.util.Map<String,Object> headers) -> "requesting-session".equals(headers.get("simpSessionId"))));
        verifyNoMoreInteractions(messaging);
    }
}
