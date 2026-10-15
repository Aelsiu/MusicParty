package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;
import org.thornex.musicparty.playback.PlaybackMailbox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 私人FM/私人DJ 内容提供器：批次拉取、缓存、顺序编排。 */
@Slf4j
@Service
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class PrivateDjService {

    private final NeteaseMusicApiService neteaseMusicApiService;
    private final AppProperties appProperties;
    private PlaybackMailbox mailbox;
    private long generation;

    @org.springframework.beans.factory.annotation.Autowired
    public void configurePlaybackMailbox(PlaybackMailbox mailbox) { this.mailbox = mailbox; }

    private final Deque<PrivateDjSegment> fmBatch = new ArrayDeque<>();
    private final Deque<PrivateDjSegment> djBatch = new ArrayDeque<>();

    /** 按当前配置模式取下一段（FM 或 DJ） */
    public Mono<PrivateDjSegment> nextSegment() {
        return "DJ".equals(appProperties.getPrivateDj().getMode()) ? nextDjSegment() : nextFmSegment();
    }

    /** 强制取私人FM 段（加入队列功能固定用 FM） */
    public Mono<PrivateDjSegment> nextFmSegment() {
        return nextSegmentFrom(fmBatch, true);
    }

    /** 强制取私人DJ 段 */
    public Mono<PrivateDjSegment> nextDjSegment() {
        return nextSegmentFrom(djBatch, false);
    }

    /** 清空批次缓存（模式切换 / 总开关关闭时调用） */
    public void invalidate() {
        synchronized (this) {
            generation++;
            fmBatch.clear();
            djBatch.clear();
        }
    }

    private Mono<PrivateDjSegment> nextSegmentFrom(Deque<PrivateDjSegment> batch, boolean fm) {
        synchronized (this) {
            if (!batch.isEmpty()) {
                return Mono.just(batch.poll());
            }
        }
        Mono<JsonNode> fetch = fm
                ? neteaseMusicApiService.fetchPersonalFm()
                : neteaseMusicApiService.fetchAidjRcmd(null, null);
        if (mailbox != null) {
            long expectedGeneration = generation;
            return Mono.create(sink -> {
                PlaybackMailbox.CompletionTicket ticket;
                try { ticket = mailbox.reserveCompletion(); }
                catch (RuntimeException error) { sink.error(error); return; }
                java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicReference<Disposable> subscription = new java.util.concurrent.atomic.AtomicReference<>();
                sink.onCancel(() -> {
                    cancelled.set(true); ticket.cancel();
                    Disposable activeRequest = subscription.get();
                    if (activeRequest != null) activeRequest.dispose();
                });
                Disposable request = fetch.map(root -> fm ? parseFmBatch(root) : parseDjBatch(root))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .subscribe(segments -> ticket.commit(() -> {
                            if (expectedGeneration != generation) { sink.error(new IllegalStateException("Private provider request cancelled")); return; }
                            batch.addAll(segments);
                            // A cancelled playback attempt may cache this batch but must not consume its first segment.
                            if (cancelled.get()) return;
                            if (batch.isEmpty()) sink.error(new IllegalStateException("Private provider returned no playable segments"));
                            else sink.success(batch.pollFirst());
                        }).exceptionally(error -> { sink.error(error); return null; }),
                        error -> ticket.commit(() -> { if (!cancelled.get()) sink.error(error); })
                                .exceptionally(commitError -> { sink.error(commitError); return null; }));
                subscription.set(request);
                if (cancelled.get()) request.dispose();
            });
        }
        return fetch
                .map(root -> fm ? parseFmBatch(root) : parseDjBatch(root))
                .map(segments -> {
                    synchronized (this) {
                        batch.addAll(segments);
                        return batch.poll();
                    }
                });
    }

    // ---- JSON 解析（结构见 私人电台与私人DJ_API使用文档.md §3）----

    static List<PrivateDjSegment> parseFmBatch(JsonNode root) {
        List<PrivateDjSegment> out = new ArrayList<>();
        JsonNode data = root.path("data");
        if (!data.isArray()) return out;
        for (JsonNode s : data) {
            if (s.has("id")) out.add(segmentFromSong(s));
        }
        return out;
    }

    static List<PrivateDjSegment> parseDjBatch(JsonNode root) {
        JsonNode res = root.path("data").path("aiDjResources");
        // 第一遍：收集本批次内歌曲 id → 封面，供语音段取"被点评歌曲"的封面
        Map<String, String> songCovers = new HashMap<>();
        for (JsonNode r : res) {
            if ("song".equals(r.path("type").asText())) {
                JsonNode sd = r.path("value").path("songData");
                if (!sd.isMissingNode() && sd.has("id")) {
                    String cover = sd.path("album").path("picUrl").asText("");
                    songCovers.put(String.valueOf(sd.path("id").asLong()), cover.isEmpty() ? null : toHttps(cover));
                }
            }
        }
        List<PrivateDjSegment> out = new ArrayList<>();
        for (JsonNode r : res) {
            String type = r.path("type").asText();
            if ("audio".equals(type)) {
                JsonNode a = r.path("value").path("audioList").path(0);
                String url = toHttps(a.path("audioUrl").asText());
                if (!url.isEmpty()) {
                    long durMs = Math.round(a.path("duration").asDouble() * 1000);
                    String related = null;
                    JsonNode rel = a.path("introductionRelatedSongIds");
                    if (rel.isArray() && rel.size() > 0) {
                        related = String.valueOf(rel.get(0).asLong());
                    }
                    out.add(new PrivateDjSegment.Voice(url, a.path("audioId").asText(), durMs, related,
                            related == null ? null : songCovers.get(related)));
                }
            } else if ("song".equals(type)) {
                JsonNode sd = r.path("value").path("songData");
                if (!sd.isMissingNode() && sd.has("id")) out.add(segmentFromSong(sd));
            }
        }
        return out;
    }

    private static PrivateDjSegment.Song segmentFromSong(JsonNode s) {
        List<String> artists = new ArrayList<>();
        for (JsonNode a : s.path("artists")) {
            artists.add(a.path("name").asText());
        }
        return new PrivateDjSegment.Song(
                String.valueOf(s.path("id").asLong()),
                s.path("name").asText(),
                artists,
                s.path("duration").asLong(),
                toHttps(s.path("album").path("picUrl").asText())
        );
    }

    /** 网易云 CDN 资源统一升级为 HTTPS，避免 HTTPS 站点上的混合内容被拦截（音乐直链与封面同理） */
    private static String toHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return url.replace("http://", "https://");
        }
        return url;
    }
}
