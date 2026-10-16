package org.thornex.musicparty.dto;

import lombok.Data;

@Data
public class User {
    private final String token; // 🟢 真正的唯一标识 (UUID)
    private String sessionId;   // 🟢 当前的 WebSocket 会话 ID (会变)
    private String name;
    private boolean isGuest;
    private long lastActiveTime;

    public User(String token, String sessionId, String name) {
        this.token = token;
        this.sessionId = sessionId;
        this.name = name;
        this.isGuest = name == null || name.trim().toLowerCase().startsWith("guest") || name.trim().startsWith("游客");
        this.lastActiveTime = System.currentTimeMillis();
    }
}

