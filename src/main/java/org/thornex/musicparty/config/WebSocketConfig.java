package org.thornex.musicparty.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;
    @org.springframework.beans.factory.annotation.Autowired
    private org.thornex.musicparty.room.RoomSocketRegistry sockets;

    @Override public void configureWebSocketTransport(org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new org.springframework.web.socket.handler.WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session) throws Exception { sockets.add(session); super.afterConnectionEstablished(session); }
            @Override public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception { sockets.remove(session.getId()); super.afterConnectionClosed(session,status); }
        });
    }

    public WebSocketConfig(WebSocketAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        ThreadPoolTaskScheduler te = new ThreadPoolTaskScheduler();
        te.setPoolSize(1);
        te.setThreadNamePrefix("wss-heartbeat-");
        te.initialize();

        config.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(te) // 🟢 绑定调度器
                .setHeartbeatValue(new long[]{10000, 10000}); // 🟢 设置心跳：[发, 收] 均为 10秒

        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 允许所有来源，专门针对 SockJS 的严格模式
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        // 注册拦截器
        registration.interceptors(authInterceptor);
    }
}
