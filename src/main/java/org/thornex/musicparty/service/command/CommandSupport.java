package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.room.RoomAccessService;
import org.thornex.musicparty.room.RoomContext;

import java.util.Locale;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CommandSupport {
    private final RoomAccessService access;
    private final SimpMessagingTemplate messaging;

    public static String parameter(String args, String fallback) {
        return args == null || args.isBlank() ? fallback : args.trim().toLowerCase(Locale.ROOT);
    }

    public boolean requireManager(User user) {
        try {
            var connection = access.connection(user.getSessionId());
            if (!RoomContext.require().equals(connection.roomId())
                    || connection.managerToken() == null || connection.managerToken().isBlank()) {
                reply(user, "此指令仅限本房间 Owner 或 Root 使用，请通过管理入口进入房间");
                return false;
            }
            access.own(connection.managerToken(), connection.roomId());
            return true;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            reply(user, e.getReason());
            return false;
        }
    }

    public void reply(User user, String content) {
        send(user, "/queue/chat/private", new ChatMessage(UUID.randomUUID().toString(),
                "SYSTEM", "SYSTEM", content, System.currentTimeMillis(), MessageType.SYSTEM));
    }

    public void event(User user, String action, String message, String payload) {
        send(user, "/queue/events", new PlayerEvent("INFO", action, user.getToken(), message, payload));
    }

    private void send(User user, String destination, Object value) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(user.getSessionId());
        headers.setLeaveMutable(true);
        messaging.convertAndSendToUser(user.getSessionId(), destination, value, headers.getMessageHeaders());
    }
}
