package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.event.SeekRejectedEvent;
import org.thornex.musicparty.service.UserService;
import org.thornex.musicparty.websocket.WebSocketBroadcaster;
import static org.mockito.Mockito.*;

class SeekFeedbackTest {
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
