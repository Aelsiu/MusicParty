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
    private final CommandSupport commands;
    private final org.thornex.musicparty.room.RoomRepository repository;

    @Override
    public String getCommand() {
        return "code";
    }

    @Override
    public void execute(String args, User user) {
        String parameter=CommandSupport.parameter(args,"copy");
        if (!java.util.Set.of("copy","on","off").contains(parameter)) {
            commands.reply(user,"用法：//code [copy|on|off]，不带参数默认 copy");
            return;
        }
        if (!commands.requireManager(user)) return;
        if ("on".equals(parameter) || "off".equals(parameter)) {
            boolean open="on".equals(parameter);
            String room=org.thornex.musicparty.room.RoomContext.require();
            repository.setPairingOpen(room,open);
            messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/pairing"), java.util.Map.of("open",open));
            commands.reply(user,open?"已开启房内配对码展示":"已关闭房内配对码展示");
            return;
        }
        PlayerEvent event = new PlayerEvent(
                "INFO",
                PlayerAction.PAIRING_TRIGGER.name(),
                user.getToken(),
                "COPY_PAIRING_CODE",
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
