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
public class RoomsCommand implements ChatCommand {

    private final SimpMessagingTemplate messagingTemplate;
    private final CommandSupport commands;

    @Override
    public String getCommand() {
        return "rooms";
    }

    @Override
    public void execute(String args, User user) {
        if (!args.isBlank()) { commands.reply(user,"用法：//rooms，不需要参数");return; }
        if (!commands.requireManager(user)) return;
        PlayerEvent event = new PlayerEvent(
                "INFO",
                PlayerAction.ROOMS_TRIGGER.name(),
                user.getToken(),
                "OPEN_ROOM_MANAGER",
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
