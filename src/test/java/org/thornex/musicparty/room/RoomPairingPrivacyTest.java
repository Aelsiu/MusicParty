package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.Map;
import static org.mockito.Mockito.*;

class RoomPairingPrivacyTest {
    @Test void shareNotificationsContainOnlyThePermissionFlagNeverTheCode() {
        var repository=mock(RoomRepository.class);
        var messaging=mock(SimpMessagingTemplate.class);
        var lifecycle=new RoomLifecycleService(repository,mock(RoomAccessService.class),mock(RoomScope.class),
                mock(RoomSocketRegistry.class),mock(ApplicationContext.class),messaging,new MultiRoomProperties());
        for(boolean enabled:new boolean[]{true,false}) {
            var room=mock(RoomRepository.Room.class);
            when(room.shareEnabled()).thenReturn(enabled);when(repository.room("room")).thenReturn(room);
            lifecycle.shareChanged("room");
            verify(messaging).convertAndSend("/topic/rooms/room/share",Map.of("enabled",enabled));
            verify(room,never()).pairingCode();
        }
        verifyNoMoreInteractions(messaging);
    }
    @Test void openAndCloseNotificationsContainOnlyTheVisibilityFlagNeverTheCode() {
        var repository=mock(RoomRepository.class);
        var messaging=mock(SimpMessagingTemplate.class);
        var lifecycle=new RoomLifecycleService(repository,mock(RoomAccessService.class),mock(RoomScope.class),
                mock(RoomSocketRegistry.class),mock(ApplicationContext.class),messaging,new MultiRoomProperties());
        for(boolean open:new boolean[]{true,false}) {
            var room=mock(RoomRepository.Room.class);
            when(room.pairingOpen()).thenReturn(open);
            when(repository.room("room")).thenReturn(room);
            lifecycle.pairingChanged("room");
            verify(messaging).convertAndSend("/topic/rooms/room/pairing",Map.of("open",open));
            verify(room,never()).pairingCode();
        }
        verifyNoMoreInteractions(messaging);
    }
}
