package org.thornex.musicparty.dto;

import java.time.Instant;
import java.util.List;

/** Metadata-only favorite contracts. No credentials, connection IDs, or media URLs. */
public final class VideoFavorites {
    private VideoFavorites() {}
    public record Item(String itemId, String bvid, String title, String cover, String ownerName,
                       long durationMs, boolean selectable, String reasonCode) {}
    public record Page(String folderId, String title, int page, List<Item> items, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
    public record Selection(String itemId, int page) {}
    public record Selected(String itemId, String bvid, int page, String trustedCanonicalId) {
        public Selected(String itemId,String bvid,int page) { this(itemId,bvid,page,null); }
        public String canonicalId() { return trustedCanonicalId==null ? bvid + ":p" + page : trustedCanonicalId; }
    }
    public record Preview(String previewId, String folderId, String title, int page, List<Item> items,
                          boolean hasMore, Integer nextPage, Instant expiresAt, boolean truncated) {
        public Preview { items = List.copyOf(items); }
    }
    public record ItemResult(String itemId, String canonicalId, String status, String reasonCode,
                             boolean retryable, String queueId) {}
    public record Counts(int requested, int pending, int added, int skipped, int failed, int cancelled) {}
    public record Operation(int protocolVersion, String roomId, String playbackEpoch, String mediaMode,
                            long modeEpoch, String requestId, String operationId, long operationRevision,
                            String status, Instant acceptedAt, Instant resultExpiresAt, Counts counts,
                            List<ItemResult> items) {
        public Operation { items = List.copyOf(items); }
    }
    public static final class Failure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public Failure(String code, String message, boolean retryable) {
            super(message); this.code = code; this.retryable = retryable;
        }
        public String getCode() { return code; }
        public boolean isRetryable() { return retryable; }
    }
    public static Failure failure(String code) { return new Failure(code, message(code), java.util.Set.of("TEMPORARY_FAILURE", "IMPORT_BUSY", "RESOLVE_BUSY", "ROOM_BUSY", "XIFAN_UNAVAILABLE", "MEDIA_METADATA_UNAVAILABLE", "TIMEOUT").contains(code)); }
    private static String message(String code) {
        return switch (code) {
            case "INVALID_VIDEO_INPUT" -> "视频输入或分 P 无效，请检查链接和页码";
            case "AMBIGUOUS_VIDEO_INPUT" -> "检测到多个链接，请只保留一个链接";
            case "PAGE_CONFLICT" -> "短链接与目标视频的分 P 冲突";
            case "RESOLVE_BUSY" -> "解析任务已满，请稍后重试";
            case "IMPORT_BATCH_TOO_LARGE" -> "选择超过本批上限，请减少选择";
            case "QUOTA_EXCEEDED" -> "待播队列配额不足";
            case "NAMED_MEMBER_REQUIRED" -> "请先设置昵称并连接房间";
            case "PLATFORM_NOT_AVAILABLE" -> "房间视频平台不可用";
            case "STALE_PLAYBACK_CONTEXT" -> "房间播放上下文已变化，请重新操作";
            case "INVALID_FAVORITE_LINK" -> "请输入支持的 B站收藏夹链接";
            case "CREDENTIALS_REQUIRED" -> "请管理员配置 B站凭据";
            case "CREDENTIALS_INVALID" -> "B站凭据已失效，请管理员更新";
            case "CONTENT_UNAVAILABLE" -> "收藏夹不存在、已失效或无权访问";
            case "PREVIEW_EXPIRED", "PREVIEW_NOT_FOUND" -> "预览已失效，请重新预览";
            case "IMPORT_BUSY" -> "导入任务已满，请稍后重试";
            case "IMPORT_RESULT_UNAVAILABLE" -> "导入结果不可用，请先核对队列";
            case "REQUEST_ID_CONFLICT" -> "请求标识已用于另一组选择";
            case "INVALID_SELECTION" -> "选择不属于本次预览或分 P 无效";
            case "XIFAN_UNAVAILABLE" -> "XIFAN 暂不可用，请稍后重试";
            case "MP4_NOT_SUPPORTED" -> "该线路不是支持的完整 MP4 音视频";
            case "MEDIA_METADATA_UNAVAILABLE" -> "无法在探测预算内确认媒体时长";
            case "MEDIA_ROUTE_UNSUPPORTED" -> "该媒体线路尚未支持，请选择其他线路";
            case "MEDIA_CONTENT_CHANGED" -> "媒体内容已变化，请重新选择剧集";
            case "TIMEOUT" -> "来源确认超时，请重试";
            default -> "操作未完成，请稍后重试";
        };
    }
}
