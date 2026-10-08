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
    public record Selected(String itemId, String bvid, int page) {
        public String canonicalId() { return bvid + ":p" + page; }
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
    public static Failure failure(String code) { return new Failure(code, message(code), code.equals("TEMPORARY_FAILURE") || code.equals("IMPORT_BUSY")); }
    private static String message(String code) {
        return switch (code) {
            case "INVALID_FAVORITE_LINK" -> "请输入支持的 B站收藏夹链接";
            case "CREDENTIALS_REQUIRED" -> "请管理员配置 B站凭据";
            case "CREDENTIALS_INVALID" -> "B站凭据已失效，请管理员更新";
            case "CONTENT_UNAVAILABLE" -> "收藏夹不存在、已失效或无权访问";
            case "PREVIEW_EXPIRED", "PREVIEW_NOT_FOUND" -> "预览已失效，请重新预览";
            case "IMPORT_BUSY" -> "导入任务已满，请稍后重试";
            case "IMPORT_RESULT_UNAVAILABLE" -> "导入结果不可用，请先核对队列";
            case "REQUEST_ID_CONFLICT" -> "请求标识已用于另一组选择";
            case "INVALID_SELECTION" -> "选择不属于本次预览或分 P 无效";
            default -> "操作未完成，请稍后重试";
        };
    }
}
