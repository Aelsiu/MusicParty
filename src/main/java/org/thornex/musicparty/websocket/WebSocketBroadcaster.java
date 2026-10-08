package org.thornex.musicparty.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.UserService;
import org.thornex.musicparty.util.MessageFormatter;

@Component
@RequiredArgsConstructor
public class WebSocketBroadcaster {

    @EventListener
    public void onCommittedNotice(org.thornex.musicparty.playback.CommittedPlaybackNotice notice) {
        Object event = notice.event();
        if (event instanceof PlayerEvent dto) {
            messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/player/events"),
                    dto.versioned(notice.playbackEpoch(), notice.mediaMode(), notice.modeEpoch()));
        } else if (event instanceof SystemMessageEvent system) {
            PlayerEvent dto = format(system).versioned(notice.playbackEpoch(), notice.mediaMode(), notice.modeEpoch());
            messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/player/events"), dto);
        } else if (event instanceof org.thornex.musicparty.event.VideoWaitTimeoutEvent timeout) {
            PlayerEvent dto = new PlayerEvent("WARN","VIDEO_WAIT_TIMEOUT",null,
                    "「" + timeout.userName() + "」缓冲等待超过 15 秒，已暂时退出等待判定",null,
                    notice.playbackEpoch(),notice.mediaMode(),notice.modeEpoch(),timeout.eventId(),timeout.waitId(),
                    timeout.playbackId(),timeout.userName(),timeout.sourceRevision());
            messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/player/events"), dto);
        } else if (event instanceof org.thornex.musicparty.event.SeekRejectedEvent rejected) onSeekRejected(rejected);
    }

    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;

    @EventListener
    public void onSeekRejected(org.thornex.musicparty.event.SeekRejectedEvent event) {
        var headers=org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        headers.setSessionId(event.sessionId());headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(event.sessionId(), "/queue/events",
                new PlayerEvent("ERROR", "SYSTEM_MESSAGE", event.userToken(), event.message(), null), headers.getMessageHeaders());
    }

    /**
     * 监听播放器完整状态变更事件
     */
    @EventListener
    public void onPlayerStateChanged(PlayerStateEvent event) {
        messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/player/state"), event.getState());
    }

    /**
     * 监听队列更新事件
     */
    @EventListener
    public void onQueueChanged(QueueUpdateEvent event) {
        messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/player/queue"), event.getQueue());
    }

    /**
     * 监听系统消息事件（用于 Toast 通知等）
     */
    @EventListener
    public void onSystemMessage(SystemMessageEvent event) {
        messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/player/events"), format(event));
    }

    private PlayerEvent format(SystemMessageEvent event) {
        // 将内部的 SystemMessageEvent 转换为对外的 PlayerEvent DTO
        String actionCode = event.getAction() != null ? event.getAction().name() : "";
        String type = event.getLevel().name();

        String userName = "SYSTEM";
        if (!"SYSTEM".equals(event.getUserId())) {
            userName = userService.getUserByToken(event.getUserId())
                    .map(User::getName)
                    .orElse("Unknown");
        }

        String formattedMessage = MessageFormatter.format(event, userName);

        // 特殊处理密码修改的广播：标记为 PASSWORD_CHANGED 以便前端强制重新验证
        if (event.getAction() == PlayerAction.SYSTEM_MESSAGE && event.getPayload() != null && event.getPayload().startsWith("房间密码")) {
            actionCode = "PASSWORD_CHANGED";
            type = "ERROR";
        }

        PlayerEvent playerEvent = new PlayerEvent(
                type,
                actionCode,
                event.getUserId(),
                formattedMessage,
                event.getPayload()
        );
        return playerEvent;
    }
}
