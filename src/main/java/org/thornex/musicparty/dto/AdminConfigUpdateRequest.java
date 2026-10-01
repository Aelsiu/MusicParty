package org.thornex.musicparty.dto;

public record AdminConfigUpdateRequest(
    Integer maxSize,
    Integer historySize,
    Integer maxUserSongs,
    Integer maxPlaylistImportSize,
    Integer maxChatHistorySize,
    Long minChatIntervalMs,
    Integer maxChatMessageLength,
    String neteaseQuality,
    Boolean neteaseEnabled,
    Boolean bilibiliEnabled,
    Integer bilibiliMaxDurationMinutes,
    Boolean voteSkipEnabled,
    Double voteSkipThreshold,
    Integer voteSkipWaitTime,
    String seekPolicy
) {
    public boolean hasGlobalSystemConfig() {
        return maxSize != null || historySize != null || maxUserSongs != null
                || maxPlaylistImportSize != null || maxChatHistorySize != null
                || minChatIntervalMs != null || maxChatMessageLength != null
                || bilibiliMaxDurationMinutes != null;
    }

    public boolean hasRoomConfig() {
        return neteaseQuality != null || neteaseEnabled != null || bilibiliEnabled != null
                || voteSkipEnabled != null || voteSkipThreshold != null
                || voteSkipWaitTime != null || seekPolicy != null;
    }
}
