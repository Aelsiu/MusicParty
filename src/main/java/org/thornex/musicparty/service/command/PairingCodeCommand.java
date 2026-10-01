package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;

@Component
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class PairingCodeCommand implements ChatCommand {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public String getCommand() {
        return "code";
    }

    @Override
    public void execute(String args, User user) {
        PlayerEvent event = new PlayerEvent(
                "INFO",
                PlayerAction.PAIRING_TRIGGER.name(),
                user.getToken(),
                "OPEN_PAIRING_MODAL",
                null
        );
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(user.getSessionId());
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                user.getSessionId(), "/queue/events", event, headers.getMessageHeaders()
        );
    }
}
