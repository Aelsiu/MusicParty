package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.room.RoomContext;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChatResetIsolationTest {
    private final ChatService chat = new ChatService(mock(SimpMessagingTemplate.class),
            mock(UserService.class), new AppProperties(), List.of());

    @Test void playbackResetPreservesRoomConversation() {
      try (var ignored = RoomContext.enter("test-room")) {
        ChatMessage original = new ChatMessage("before", "member", "成员", "聊天现场", 1L, MessageType.CHAT);
        chat.addMessage(original);
        chat.onSystemEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN,
                PlayerAction.RESET, "SYSTEM", null));
        assertTrue(chat.getHistoryFull().contains(original));
        assertEquals(2, chat.getHistoryFull().size());
      }
    }

    @Test void explicitChatClearStillClearsConversation() {
      try (var ignored = RoomContext.enter("test-room")) {
        chat.addMessage(new ChatMessage("before", "member", "成员", "聊天现场", 1L, MessageType.CHAT));
        chat.clearHistoryAndNotify();
        assertEquals(1, chat.getHistoryFull().size());
        assertEquals("聊天记录已由管理员清空", chat.getHistoryFull().getFirst().content());
      }
    }
}
