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
    String seekPolicy,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = org.thornex.musicparty.room.StrictIntegerDeserializer.class)
    Integer pairingIntervalMinutes,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = org.thornex.musicparty.room.StrictIntegerDeserializer.class)
    Integer videoMaxDurationMinutes,
    String videoMaxResolution, Boolean xifanEnabled
) {
    public AdminConfigUpdateRequest(Integer maxSize,
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
    String seekPolicy,
        Integer pairingIntervalMinutes,
        Integer videoMaxDurationMinutes,
    String videoMaxResolution) {this(maxSize,historySize,maxUserSongs,maxPlaylistImportSize,maxChatHistorySize,minChatIntervalMs,maxChatMessageLength,neteaseQuality,neteaseEnabled,bilibiliEnabled,bilibiliMaxDurationMinutes,voteSkipEnabled,voteSkipThreshold,voteSkipWaitTime,seekPolicy,pairingIntervalMinutes,videoMaxDurationMinutes,videoMaxResolution,null); }
    public AdminConfigUpdateRequest(Integer maxSize, Integer historySize, Integer maxUserSongs,
            Integer maxPlaylistImportSize, Integer maxChatHistorySize, Long minChatIntervalMs,
            Integer maxChatMessageLength, String neteaseQuality, Boolean neteaseEnabled,
            Boolean bilibiliEnabled, Integer bilibiliMaxDurationMinutes, Boolean voteSkipEnabled,
            Double voteSkipThreshold, Integer voteSkipWaitTime, String seekPolicy, Integer pairingIntervalMinutes,
            Integer videoMaxDurationMinutes) {
        this(maxSize, historySize, maxUserSongs, maxPlaylistImportSize, maxChatHistorySize, minChatIntervalMs,
                maxChatMessageLength, neteaseQuality, neteaseEnabled, bilibiliEnabled, bilibiliMaxDurationMinutes,
                voteSkipEnabled, voteSkipThreshold, voteSkipWaitTime, seekPolicy, pairingIntervalMinutes, videoMaxDurationMinutes, null);
    }
    public AdminConfigUpdateRequest(Integer maxSize, Integer historySize, Integer maxUserSongs,
            Integer maxPlaylistImportSize, Integer maxChatHistorySize, Long minChatIntervalMs,
            Integer maxChatMessageLength, String neteaseQuality, Boolean neteaseEnabled,
            Boolean bilibiliEnabled, Integer bilibiliMaxDurationMinutes, Boolean voteSkipEnabled,
            Double voteSkipThreshold, Integer voteSkipWaitTime, String seekPolicy, Integer pairingIntervalMinutes) {
        this(maxSize, historySize, maxUserSongs, maxPlaylistImportSize, maxChatHistorySize,
                minChatIntervalMs, maxChatMessageLength, neteaseQuality, neteaseEnabled,
                bilibiliEnabled, bilibiliMaxDurationMinutes, voteSkipEnabled, voteSkipThreshold,
                voteSkipWaitTime, seekPolicy, pairingIntervalMinutes, null);
    }
    public AdminConfigUpdateRequest(Integer maxSize, Integer historySize, Integer maxUserSongs,
            Integer maxPlaylistImportSize, Integer maxChatHistorySize, Long minChatIntervalMs,
            Integer maxChatMessageLength, String neteaseQuality, Boolean neteaseEnabled,
            Boolean bilibiliEnabled, Integer bilibiliMaxDurationMinutes, Boolean voteSkipEnabled,
            Double voteSkipThreshold, Integer voteSkipWaitTime, String seekPolicy) {
        this(maxSize, historySize, maxUserSongs, maxPlaylistImportSize, maxChatHistorySize,
                minChatIntervalMs, maxChatMessageLength, neteaseQuality, neteaseEnabled,
                bilibiliEnabled, bilibiliMaxDurationMinutes, voteSkipEnabled, voteSkipThreshold,
                voteSkipWaitTime, seekPolicy, null);
    }
    public boolean hasGlobalSystemConfig() {
        return maxSize != null || historySize != null || maxUserSongs != null
                || maxPlaylistImportSize != null || maxChatHistorySize != null
                || minChatIntervalMs != null || maxChatMessageLength != null
                || bilibiliMaxDurationMinutes != null || pairingIntervalMinutes != null || videoMaxDurationMinutes != null || videoMaxResolution != null;
    }

    public boolean hasRoomConfig() {
        return xifanEnabled != null || neteaseQuality != null || neteaseEnabled != null || bilibiliEnabled != null
                || voteSkipEnabled != null || voteSkipThreshold != null
                || voteSkipWaitTime != null || seekPolicy != null;
    }
}
