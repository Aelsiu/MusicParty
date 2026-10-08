package org.thornex.musicparty.dto;

public record PlayerEvent(
        String type,
        String action,    // 动作代码, 如 "SKIP", "PAUSE", "ADD"
        String userId,    // 发起者 ID
        String message,   // 新增: 格式化好的显示文本
        String payload,    // 额外信息 (如歌曲名)
        String playbackEpoch, String mediaMode, Long modeEpoch,
        String eventId, String waitId, String playbackId, String userName, Long sourceRevision
) {
    public PlayerEvent(String type, String action, String userId, String message, String payload) {
        this(type, action, userId, message, payload, null, null, null, null, null, null, null, null);
    }
    public PlayerEvent(String type, String action, String userId, String message, String payload,
            String playbackEpoch, String mediaMode, Long modeEpoch) {
        this(type, action, userId, message, payload, playbackEpoch, mediaMode, modeEpoch, null, null, null, null, null);
    }
    public PlayerEvent versioned(String epoch, String mode, long modeVersion) {
        return new PlayerEvent(type, action, userId, message, payload, epoch, mode, modeVersion,
                eventId, waitId, playbackId, userName, sourceRevision);
    }
}

