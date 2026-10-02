package org.thornex.musicparty.service.command;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.UserService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RoomNavigationCommandTest {

    @ParameterizedTest
    @CsvSource({
            "code, PAIRING_TRIGGER, COPY_PAIRING_CODE",
            "rooms, ROOMS_TRIGGER, OPEN_ROOM_MANAGER"
    })
    void navigationCommandsArePrivateToTheSendingTabAndExcludedFromChatHistory(
            String command, String action, String message) {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        UserService users = mock(UserService.class);
        User sharedProfile = new User("shared-token", "newer-tab", "Manager");
        when(users.getUser("sending-tab")).thenReturn(Optional.of(sharedProfile));
        CommandSupport commands=mock(CommandSupport.class);
        when(commands.requireManager(any())).thenReturn(true);
        ChatService chat = new ChatService(template, users, new AppProperties(),
                List.of(new PairingCodeCommand(template, commands, mock(org.thornex.musicparty.room.RoomRepository.class)), new RoomsCommand(template, commands)));

        assertTrue(chat.processIncomingMessage("sending-tab", "//" + command.toUpperCase()));

        ArgumentCaptor<PlayerEvent> event = ArgumentCaptor.forClass(PlayerEvent.class);
        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(template).convertAndSendToUser(eq("sending-tab"), eq("/queue/events"),
                event.capture(), headers.capture());
        assertEquals("sending-tab", SimpMessageHeaderAccessor.getSessionId(headers.getValue()));
        assertEquals(action, event.getValue().action());
        assertEquals(message, event.getValue().message());
        assertEquals("shared-token", event.getValue().userId());
        assertNull(event.getValue().payload(), "Pairing codes must come from the authorized manager API");
        assertTrue(chat.getHistoryFull().isEmpty());
        assertEquals("newer-tab", sharedProfile.getSessionId());
        verifyNoMoreInteractions(template);
    }

    @Test void pairingOnAndOffDispatchThroughChatWithoutBroadcastingOrSavingCommandMessages() {
        var template = mock(SimpMessagingTemplate.class);
        var users = mock(UserService.class);
        var repository = mock(org.thornex.musicparty.room.RoomRepository.class);
        var commands = mock(CommandSupport.class);
        when(users.getUser("sending-tab")).thenReturn(Optional.of(new User("profile", "other-tab", "Owner")));
        when(commands.requireManager(any())).thenReturn(true);
        var chat = new ChatService(template, users, new AppProperties(),
                List.of(new PairingCodeCommand(template, commands, repository)));
        try (var ignored = org.thornex.musicparty.room.RoomContext.enter("Room1234")) {
            assertTrue(chat.processIncomingMessage("sending-tab", "//CODE ON"));
            assertTrue(chat.processIncomingMessage("sending-tab", "//code off"));
        }
        verify(repository).setPairingOpen("Room1234", true);
        verify(repository).setPairingOpen("Room1234", false);
        verify(template).convertAndSend("/topic/rooms/Room1234/pairing", java.util.Map.of("open", true));
        verify(template).convertAndSend("/topic/rooms/Room1234/pairing", java.util.Map.of("open", false));
        verify(commands).reply(argThat(sender -> sender.getSessionId().equals("sending-tab")), eq("已开启房内配对码展示"));
        verify(commands).reply(argThat(sender -> sender.getSessionId().equals("sending-tab")), eq("已关闭房内配对码展示"));
        assertTrue(chat.getHistoryFull().isEmpty());
        verifyNoMoreInteractions(repository, template);
    }
}
