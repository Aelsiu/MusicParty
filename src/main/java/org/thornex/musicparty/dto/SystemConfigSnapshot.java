package org.thornex.musicparty.dto;

import org.thornex.musicparty.config.AppProperties;

/** The eight ROOT-owned limits shared by every room. */
public record SystemConfigSnapshot(
        int maxQueueSize, int maxHistorySize, int maxUserSongs,
        int maxPlaylistImportSize, int maxChatHistorySize, long minChatIntervalMs,
        int maxChatMessageLength, int bilibiliMaxDurationMinutes
) {
    public static SystemConfigSnapshot from(AppProperties properties) {
        return new SystemConfigSnapshot(
                properties.getQueue().getMaxSize(), properties.getQueue().getHistorySize(),
                properties.getQueue().getMaxUserSongs(), properties.getPlayer().getMaxPlaylistImportSize(),
                properties.getChat().getMaxHistorySize(), properties.getChat().getMinIntervalMs(),
                properties.getChat().getMaxMessageLength(), properties.getBilibili().getMaxDurationMinutes());
    }

    public SystemConfigSnapshot withUpdate(AdminConfigUpdateRequest request) {
        return new SystemConfigSnapshot(
                request.maxSize() == null ? maxQueueSize : request.maxSize(),
                request.historySize() == null ? maxHistorySize : request.historySize(),
                request.maxUserSongs() == null ? maxUserSongs : request.maxUserSongs(),
                request.maxPlaylistImportSize() == null ? maxPlaylistImportSize : request.maxPlaylistImportSize(),
                request.maxChatHistorySize() == null ? maxChatHistorySize : request.maxChatHistorySize(),
                request.minChatIntervalMs() == null ? minChatIntervalMs : request.minChatIntervalMs(),
                request.maxChatMessageLength() == null ? maxChatMessageLength : request.maxChatMessageLength(),
                request.bilibiliMaxDurationMinutes() == null ? bilibiliMaxDurationMinutes : request.bilibiliMaxDurationMinutes());
    }

    public String validationError() {
        if (maxQueueSize < 1 || maxQueueSize > 10000) return "队列最大歌曲上限超出范围";
        if (maxHistorySize < 0 || maxHistorySize > 10000) return "历史记录歌曲上限超出范围";
        if (maxUserSongs < 1 || maxUserSongs > 10000) return "单人歌曲上限超出范围";
        if (maxPlaylistImportSize < 1 || maxPlaylistImportSize > 10000) return "歌单导入上限超出范围";
        if (maxChatHistorySize < 0 || maxChatHistorySize > 100000) return "消息历史条数超出范围";
        if (minChatIntervalMs < 0 || minChatIntervalMs > 600000) return "发言间隔超出范围";
        if (maxChatMessageLength < 1 || maxChatMessageLength > 10000) return "消息最大长度超出范围";
        if (bilibiliMaxDurationMinutes < 1 || bilibiliMaxDurationMinutes > 1440) return "B站时长上限超出范围";
        return null;
    }
}
