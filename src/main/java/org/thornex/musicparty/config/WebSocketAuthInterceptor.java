package org.thornex.musicparty.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.controller.AuthController;

@Component
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final AuthController authController;

    public WebSocketAuthInterceptor(AuthController authController) {
        this.authController = authController;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // 只拦截连接命令
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String inputPassword = accessor.getFirstNativeHeader("room-password");

            if (!isPasswordValid(inputPassword)) {
                log.warn("WebSocket Connection Refused: Invalid Room Password. Session: {}", accessor.getSessionId());
                // 抛出异常将直接导致连接断开，并向客户端发送 ERROR 帧
                throw new MessageDeliveryException("INVALID_ROOM_PASSWORD");
            }

            log.info("WebSocket Authenticated: Session {}", accessor.getSessionId());
        }
        return message;
    }

    private boolean isPasswordValid(String input) {
        String currentRoomPass = authController.getRawPassword();

        return AuthController.isValidPin(currentRoomPass)
                && AuthController.isValidPin(input)
                && authController.getRoomName() != null
                && currentRoomPass.equals(input);
    }
}
