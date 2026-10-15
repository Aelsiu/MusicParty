package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.ResolvedVideoSource;
import org.thornex.musicparty.dto.VideoDescriptor;
import org.thornex.musicparty.dto.VideoPageDescriptor;
import org.thornex.musicparty.dto.DashVideoSource;
import org.thornex.musicparty.dto.MediaTrackSource;
import org.thornex.musicparty.dto.Mp4VideoSource;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.util.BilibiliApiUtils;
import org.thornex.musicparty.util.VideoSourceExpiry;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Video metadata/source only: no cache, proxy, media download, or FFmpeg dependency. */
@Service
@org.thornex.musicparty.room.RoomScoped
public class BilibiliVideoApiService {
    public static final String PLATFORM = "bilibili-video";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern ID = Pattern.compile("(BV[A-Za-z0-9]{10})(?::p([1-9][0-9]{0,3}))?");
    private static final int MAX_PAGES = 1000;
    private static final Pattern BYTE_RANGE = Pattern.compile("([0-9]{1,18})-([0-9]{1,18})");
    private final WebClient webClient;
    private final AppProperties properties;
    private final BilibiliCookieService cookieService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private BilibiliPgcApiService pgc;
    private record Alias(String canonical,long cookieRevision) {}
    private final java.util.Map<String,Alias> verifiedAliases=new java.util.concurrent.ConcurrentHashMap<>();
    private long credentialRevision() { var snapshot=cookieService.credentialSnapshot();return snapshot==null?0:snapshot.revision(); }
    public String verifiedIdentity(String canonical) {
        var alias=verifiedAliases.get(canonical);return alias!=null && alias.cookieRevision()==credentialRevision()?alias.canonical():canonical;
    }
    public void clearVerifiedAliases() { verifiedAliases.clear(); }

    public enum FailureCode {
        UNSUPPORTED_INPUT, CREDENTIALS_MISSING, CREDENTIALS_INVALID,
        CONTENT_UNAVAILABLE, UNSUPPORTED_SOURCE, INCOMPLETE_SOURCE,
        VIDEO_TOO_LONG, VIDEO_QUALITY_LIMIT, TEMPORARY_SOURCE_FAILURE
    }

    public static class VideoSourceException extends ApiRequestException {
        private final FailureCode code;
        public VideoSourceException(FailureCode code, String message) {
            super(message);
            this.code = code;
        }
        public FailureCode getCode() { return code; }
        public boolean isRetryable() { return code == FailureCode.TEMPORARY_SOURCE_FAILURE; }
    }

    public BilibiliVideoApiService(WebClient webClient, AppProperties properties,
                                  BilibiliCookieService cookieService) {
        this.webClient = webClient;
        this.properties = properties;
        this.cookieService = cookieService;
    }

    public Mono<VideoDescriptor> getVideoDescriptor(String videoId) {
        return getVideoDescriptor(videoId, true);
    }

    /** Selection UI can inspect all pages even when the default page exceeds the room's limit. */
    public Mono<VideoDescriptor> getVideoMetadata(String videoId) {
        return getVideoDescriptor(videoId, false);
    }

    private Mono<VideoDescriptor> getVideoDescriptor(String videoId, boolean enforceSelectedLimit) {
        return Mono.defer(() -> {
            long metadataRevision=credentialRevision();
            if (videoId != null && videoId.startsWith("bilibili:ep")) {
                var identity=org.thornex.musicparty.dto.VideoIdentity.parse(videoId);
                if(pgc==null) return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("PLATFORM_NOT_AVAILABLE"));
                return pgc.season(identity.epId(),true).map(season -> {
                    var descriptor=season.descriptors().get(identity.canonicalId());
                    if(descriptor==null) throw org.thornex.musicparty.dto.VideoFavorites.failure("CONTENT_UNAVAILABLE");
                    var entry=season.episodes().stream().filter(ep -> ep.canonicalId().equals(identity.canonicalId())).findFirst().orElseThrow();
                    if(!entry.selectable()) throw org.thornex.musicparty.dto.VideoFavorites.failure(entry.reasonCode());
                    if(enforceSelectedLimit) checkDuration(descriptor.durationMs(),properties.getPlayer().getVideoMaxDurationMinutes());
                    return descriptor;
                }).flatMap(descriptor -> {
                    // Only an official view redirect can establish a legacy BV:P alias.
                    // Anonymous PGC still works when the old UGC credential path is unavailable.
                    return pgc.legacyView(descriptor.bvid())
                        .flatMap(json -> metadataOrRedirect(json,descriptor.bvid()+":p1",descriptor.aid(),false,metadataRevision))
                        .thenReturn(descriptor).onErrorResume(error -> Mono.just(descriptor));
                });
            }
            String canonicalId = normalizeCanonicalId(videoId);
            String bvid = normalizeBvid(canonicalId);
            return request("/x/web-interface/view", bvid, null, false)
                    .flatMap(json -> metadataOrRedirect(json,canonicalId,null,enforceSelectedLimit,metadataRevision));
        });
    }

    public boolean isCookieConfigured() { return cookieService.hasCookie(); }

    public Mono<VideoDescriptor> getAvMetadata(String aid) {
        if (aid == null || !aid.matches("[1-9][0-9]{0,18}")) return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("INVALID_VIDEO_INPUT"));
        final long expected;
        try { expected = Long.parseLong(aid); } catch (NumberFormatException e) { return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("INVALID_VIDEO_INPUT")); }
        long metadataRevision=credentialRevision();
        return request("/x/web-interface/view", "av" + aid, null, false).flatMap(json -> {
            requireSuccess(json);
            if (!json.path("data").path("aid").canConvertToLong() || json.path("data").path("aid").longValue() != expected)
                throw org.thornex.musicparty.dto.VideoFavorites.failure("CONTENT_UNAVAILABLE");
            String bvid = json.path("data").path("bvid").asText("");
            return metadataOrRedirect(json,normalizeCanonicalId(bvid),expected,false,metadataRevision);
        });
    }
    private Mono<VideoDescriptor> metadataOrRedirect(JsonNode json,String canonical,Long expectedAid,boolean limit,long revision) {
        requireSuccess(json); JsonNode data=json.path("data");
        String redirect=data.path("redirect_url").asText("");
        if(redirect.isBlank()) return Mono.just(parseDescriptor(json,canonical,properties.getPlayer().getVideoMaxDurationMinutes(),limit));
        if(!canonical.endsWith(":p1") || pgc==null) return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("PAGE_CONFLICT"));
        URI redirectUri;
        try { redirectUri=URI.create(redirect); } catch(IllegalArgumentException invalid) { return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("CONTENT_UNAVAILABLE")); }
        if(!"https".equals(redirectUri.getScheme()) || redirectUri.getUserInfo()!=null || redirectUri.getPort()!=-1
            || !Set.of("www.bilibili.com","bilibili.com").contains(redirectUri.getHost())
            || !redirectUri.getRawPath().matches("/bangumi/play/ep[1-9][0-9]*/?")) return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("CONTENT_UNAVAILABLE"));
        var target=VideoInputResolver.classify(redirect);
        if(!target.type().equals("PGC_EPISODE")) return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("CONTENT_UNAVAILABLE"));
        long ep=org.thornex.musicparty.dto.VideoIdentity.positiveId(target.value().substring(2));
        return pgc.season(ep,true).map(season -> {
            var d=season.descriptors().get("bilibili:ep"+ep);
            if(d==null || !d.bvid().equals(normalizeBvid(canonical)) || !data.path("bvid").asText().equals(d.bvid())
                || !data.path("aid").canConvertToLong() || data.path("aid").longValue()!=d.aid()
                || !data.path("cid").canConvertToLong() || data.path("cid").longValue()!=d.cid()
                || expectedAid!=null && !expectedAid.equals(d.aid())) throw org.thornex.musicparty.dto.VideoFavorites.failure("IDENTITY_MISMATCH");
            if(limit) checkDuration(d.durationMs(),properties.getPlayer().getVideoMaxDurationMinutes());
            if(credentialRevision()==revision && (verifiedAliases.size()<2000 || verifiedAliases.containsKey(canonical)))
                verifiedAliases.put(canonical,new Alias(d.canonicalId(),revision));
            return d;
        });
    }

    public Mono<ResolvedVideoSource> resolveSource(VideoDescriptor descriptor) {
        return resolveSource(descriptor, false);
    }

    /** Only a server-owned successfully admitted current instance may use this renewal path. */
    public Mono<ResolvedVideoSource> resolveCurrentInstanceSource(VideoDescriptor descriptor) {
        return resolveSource(descriptor, true);
    }

    private Mono<ResolvedVideoSource> resolveSource(VideoDescriptor descriptor, boolean currentInstance) {
        return Mono.defer(() -> {
            if(descriptor!=null && "PGC".equals(descriptor.contentType())) {
                if(pgc==null) return Mono.error(org.thornex.musicparty.dto.VideoFavorites.failure("PLATFORM_NOT_AVAILABLE"));
                return pgc.source(descriptor,currentInstance);
            }
            if (descriptor == null || descriptor.page() < 1 || descriptor.cid() <= 0
                    || !normalizeCanonicalId(descriptor.canonicalId()).equals(descriptor.bvid() + ":p" + descriptor.page())
                    || descriptor.pages().stream().noneMatch(page -> page.page() == descriptor.page()
                    && page.cid() == descriptor.cid() && page.durationMs() == descriptor.durationMs())) {
                throw failure(FailureCode.UNSUPPORTED_INPUT, "视频分 P 标识与可信元数据不一致");
            }
            if (!currentInstance) checkDuration(descriptor.durationMs(), properties.getPlayer().getVideoMaxDurationMinutes());
            return request("/x/player/playurl", descriptor.bvid(), descriptor.cid(), true)
                    .map(json -> parseSource(json, descriptor, Instant.now()))
                    .flatMap(primary -> {
                        if (primary.dash() == null) return Mono.just(primary);
                        // A separate HTML5 request supplies a genuine muxed fallback, never a DASH video-only URL.
                        return request("/x/player/playurl", descriptor.bvid(), descriptor.cid(), false)
                                .map(json -> parseSource(json, descriptor, Instant.now()))
                                .map(fallback -> withFallback(primary, fallback.mp4()))
                                .onErrorResume(error -> Mono.just(primary));
                    })
                    .onErrorResume(VideoSourceException.class, error -> {
                        if (error.getCode() == FailureCode.CREDENTIALS_MISSING
                                || error.getCode() == FailureCode.CREDENTIALS_INVALID
                                || error.getCode() == FailureCode.CONTENT_UNAVAILABLE) return Mono.error(error);
                        return request("/x/player/playurl", descriptor.bvid(), descriptor.cid(), false)
                                .map(json -> parseSource(json, descriptor, Instant.now()));
                    })
                    .map(source -> {
                        ResolvedVideoSource filtered = org.thornex.musicparty.service.VideoSourcePolicy.filter(source, properties.getPlayer().getVideoMaxResolution());
                        if (filtered == null) throw failure(FailureCode.VIDEO_QUALITY_LIMIT, "没有符合系统分辨率上限的视频源");
                        return filtered;
                    });
        });
    }

    private Mono<JsonNode> request(String path, String bvid, Long cid, boolean dash) {
        if (!cookieService.hasCookie()) {
            return Mono.error(failure(FailureCode.CREDENTIALS_MISSING,
                    "尚未配置本房间 Bilibili Cookie，请 Owner / Root 在房间管理台配置"));
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(properties.getBilibili().getBaseUrl() + path)
                .queryParam(bvid.startsWith("av") ? "aid" : "bvid", bvid.startsWith("av") ? bvid.substring(2) : bvid);
        if (cid != null) {
            builder.queryParam("cid", cid).queryParam("fnval", dash ? 4048 : 1);
            if (dash) builder.queryParam("qn", 80).queryParam("fourk", 1);
            else builder.queryParam("platform", "html5").queryParam("qn", 32);
        }
        return cookieService.getCookieHeader().flatMap(cookie -> webClient.get()
                        .uri(builder.build().toUri())
                        .header("Cookie", cookie).header("User-Agent", USER_AGENT)
                        .header("Referer", "https://www.bilibili.com/video/" + bvid)
                        .retrieve().bodyToMono(JsonNode.class))
                .timeout(Duration.ofSeconds(20))
                .switchIfEmpty(Mono.error(failure(FailureCode.TEMPORARY_SOURCE_FAILURE,
                        "B站响应为空，请稍后重试")))
                .onErrorMap(BilibiliVideoApiService::sanitizeFailure);
    }

    /** Keep case-sensitive BVID intact; callers choosing a page must retain normalizeCanonicalId. */
    public static String normalizeBvid(String input) {
        String canonical = normalizeCanonicalId(input);
        return canonical.substring(0, canonical.indexOf(":p"));
    }

    public static String normalizeCanonicalId(String input) {
        String value = input == null ? "" : input.trim();
        Matcher matcher = ID.matcher(value);
        if (matcher.matches()) {
            return matcher.group(1) + ":p" + parsePage(matcher.group(2));
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || !("www.bilibili.com".equalsIgnoreCase(host)
                    || "bilibili.com".equalsIgnoreCase(host))) {
                throw new IllegalArgumentException();
            }
            Matcher path = Pattern.compile("/video/(BV[A-Za-z0-9]{10})/?").matcher(uri.getPath());
            if (!path.matches()) throw new IllegalArgumentException();
            int selectedPage = 1;
            boolean hasPage = false;
            if (uri.getRawQuery() != null) {
                for (String query : uri.getRawQuery().split("&")) {
                    String[] part = query.split("=", 2);
                    if ("p".equals(URLDecoder.decode(part[0], StandardCharsets.UTF_8))) {
                        if (hasPage) throw new IllegalArgumentException();
                        selectedPage = parsePage(part.length == 2 ? URLDecoder.decode(part[1], StandardCharsets.UTF_8) : "");
                        hasPage = true;
                    }
                }
            }
            return path.group(1) + ":p" + selectedPage;
        } catch (VideoSourceException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw failure(FailureCode.UNSUPPORTED_INPUT, "请输入 BV 标识、BV…:pN 或普通 https://www.bilibili.com/video/BV… 视频地址；不支持 AV、短链接或 CDN 地址");
        }
    }

    private static int parsePage(String value) {
        if (value == null) return 1;
        if (!value.matches("[1-9][0-9]{0,3}")) throw failure(FailureCode.UNSUPPORTED_INPUT, "分 P 编号必须为有效正整数");
        int page = Integer.parseInt(value);
        if (page > MAX_PAGES) throw failure(FailureCode.UNSUPPORTED_INPUT, "分 P 编号超出支持范围");
        return page;
    }

    static VideoDescriptor parseDescriptor(JsonNode json, String videoId, int maxMinutes) {
        return parseDescriptor(json, videoId, maxMinutes, true);
    }

    static VideoDescriptor parseDescriptor(JsonNode json, String videoId, int maxMinutes, boolean enforceSelectedLimit) {
        String canonicalId = normalizeCanonicalId(videoId);
        String bvid = normalizeBvid(canonicalId);
        int selectedPage = parsePage(canonicalId.substring(canonicalId.indexOf(":p") + 2));
        JsonNode data = requireSuccess(json);
        JsonNode pages = data.path("pages");
        if (!pages.isArray() || pages.isEmpty() || pages.size() > MAX_PAGES || !bvid.equals(data.path("bvid").asText())
                || data.path("state").asInt() < 0 || StringUtils.hasText(data.path("redirect_url").asText())
                || flagged(data.path("rights"), "pay", "ugc_pay", "is_stein_gate")
                || flagged(data, "is_upower_exclusive")) {
            throw failure(FailureCode.CONTENT_UNAVAILABLE, "该视频不存在、受限或不是支持的普通公开投稿");
        }
        if (!StringUtils.hasText(data.path("title").asText())) {
            throw failure(FailureCode.INCOMPLETE_SOURCE, "B站未提供可信的视频标题");
        }
        List<VideoPageDescriptor> trustedPages = new ArrayList<>();
        Set<Long> cids = new HashSet<>();
        for (JsonNode part : pages) {
            int page = part.path("page").asInt();
            long cid = part.path("cid").asLong();
            long seconds = part.path("duration").asLong();
            if (!part.path("page").isIntegralNumber() || page != trustedPages.size() + 1
                    || !part.path("cid").isIntegralNumber() || cid <= 0 || !cids.add(cid)
                    || !part.path("duration").isIntegralNumber() || seconds <= 0 || seconds > Long.MAX_VALUE / 1000) {
                throw failure(FailureCode.INCOMPLETE_SOURCE, "B站未提供完整且一致的分 P 元数据");
            }
            long duration = seconds * 1000;
            String title = StringUtils.hasText(part.path("part").asText()) ? part.path("part").asText() : "P" + page;
            trustedPages.add(new VideoPageDescriptor(bvid + ":p" + page, cid, page, title, duration,
                    duration <= (long) maxMinutes * 60000));
        }
        if (selectedPage > trustedPages.size()) throw failure(FailureCode.UNSUPPORTED_INPUT, "该视频不存在所选分 P");
        VideoPageDescriptor selected = trustedPages.get(selectedPage - 1);
        if (enforceSelectedLimit) checkDuration(selected.durationMs(), maxMinutes);
        return new VideoDescriptor(selected.canonicalId(), bvid, selected.cid(), selected.page(), data.path("title").asText(),
                selected.title(), data.path("owner").path("name").asText(), selected.durationMs(),
                BilibiliApiUtils.normalizeCoverUrl(data.path("pic").asText()), trustedPages);
    }

    static ResolvedVideoSource parseSource(JsonNode json, VideoDescriptor descriptor, Instant resolvedAt) {
        JsonNode data = requireSuccess(json);
        String format = data.path("format").asText().toLowerCase(Locale.ROOT);
        if (flagged(data, "is_preview", "is_try_look", "is_trial", "is_drm")
                || flagged(json, "is_preview", "is_try_look", "is_trial", "is_drm")) {
            throw failure(FailureCode.UNSUPPORTED_SOURCE, "不支持试播、DRM 或受限制的视频源");
        }
        if (data.path("dash").isObject()) return parseDash(data, descriptor, resolvedAt);
        // mp4480 is the verified HTML5 response label for a complete muxed 480p MP4.
        if (!("mp4".equals(format) || "mp4480".equals(format))) {
            throw failure(FailureCode.UNSUPPORTED_SOURCE, "B站未提供完整 DASH 双轨或 MP4 回退");
        }
        JsonNode segments = data.path("durl");
        if (!segments.isArray() || segments.size() != 1) {
            throw failure(FailureCode.UNSUPPORTED_SOURCE, "只支持完整单段 MP4 回退；该视频返回多段或缺少 MP4 流");
        }
        JsonNode segment = segments.get(0);
        long timeLength = data.path("timelength").asLong();
        long segmentLength = segment.path("length").asLong();
        long tolerance = durationTolerance(descriptor);
        if (timeLength <= 0 || segmentLength <= 0
                || Math.abs(timeLength - descriptor.durationMs()) > tolerance
                || Math.abs(segmentLength - descriptor.durationMs()) > tolerance
                || Math.abs(segmentLength - timeLength) > tolerance) {
            throw failure(FailureCode.INCOMPLETE_SOURCE, "返回流时长与所选 P 不一致或缺少完整性信息，不能作为完整视频播放");
        }
        String url = segment.path("url").asText();
        validateCdnUrl(url);
        List<String> backups = parseBackups(segment.path("backup_url"));
        Instant expiry = validateExpiry(url, backups, resolvedAt);
        int quality = data.path("quality").asInt();
        Mp4VideoSource mp4 = new Mp4VideoSource(url, backups, format, quality, "q" + quality,
                qualityLabel(data, quality), timeLength, expiry,
                positiveDimension(segment.path("width")), positiveDimension(segment.path("height")));
        return new ResolvedVideoSource(url, backups, format, data.path("quality").asInt(),
                timeLength, resolvedAt, expiry, null, mp4);
    }

    private static ResolvedVideoSource parseDash(JsonNode data, VideoDescriptor descriptor, Instant resolvedAt) {
        JsonNode dash = data.path("dash");
        long total = data.path("timelength").asLong();
        double dashSeconds = dash.path("duration").asDouble();
        if (total <= 0 || !Double.isFinite(dashSeconds) || dashSeconds <= 0
                || Math.abs(total - descriptor.durationMs()) > durationTolerance(descriptor)
                || Math.abs(dashSeconds * 1000 - total) > durationTolerance(descriptor)) {
            throw failure(FailureCode.INCOMPLETE_SOURCE, "DASH 时长与所选 P 不一致或缺少完整性信息");
        }
        List<MediaTrackSource> videos = parseTracks(dash.path("video"), data, true, resolvedAt);
        List<MediaTrackSource> audios = parseTracks(dash.path("audio"), data, false, resolvedAt);
        if (videos.isEmpty() || audios.isEmpty()) {
            throw failure(FailureCode.UNSUPPORTED_SOURCE, "DASH 缺少支持的完整音频或视频轨，需使用 MP4 回退");
        }
        double minBufferTime = first(dash, "minBufferTime", "min_buffer_time").asDouble(1.5);
        if (!Double.isFinite(minBufferTime) || minBufferTime <= 0 || minBufferTime > 30) minBufferTime = 1.5;
        Instant earliest = null;
        for (MediaTrackSource track : videos) earliest = earlier(earliest, track.expiresAt());
        for (MediaTrackSource track : audios) earliest = earlier(earliest, track.expiresAt());
        int quality = videos.stream().mapToInt(MediaTrackSource::quality).max().orElse(0);
        return new ResolvedVideoSource(null, List.of(), "dash", quality, total, resolvedAt, earliest,
                new DashVideoSource(videos, audios, total, minBufferTime), null);
    }

    private static List<MediaTrackSource> parseTracks(JsonNode tracks, JsonNode data, boolean video, Instant resolvedAt) {
        if (!tracks.isArray() || tracks.size() > (video ? 64 : 16)) {
            throw failure(FailureCode.INCOMPLETE_SOURCE, "DASH 轨道列表无效");
        }
        List<MediaTrackSource> result = new ArrayList<>();
        Set<String> representations = new HashSet<>();
        for (JsonNode track : tracks) {
            String mime = first(track, "mimeType", "mime_type").asText();
            String codecs = track.path("codecs").asText();
            if (!(video ? "video/mp4" : "audio/mp4").equals(mime)
                    || !(video ? codecs.matches("(?:avc1|avc3|hev1|hvc1|av01)\\.[A-Za-z0-9.]+")
                    : codecs.matches("mp4a\\.40\\.(?:2|5|29)"))) continue;
            int id = track.path("id").asInt();
            long bandwidth = track.path("bandwidth").asLong();
            int width = track.path("width").asInt();
            int height = track.path("height").asInt();
            if (!track.path("id").isIntegralNumber() || id <= 0 || bandwidth <= 0
                    || (video && (width <= 0 || height <= 0)) || !representations.add(id + ":" + codecs)) {
                throw failure(FailureCode.INCOMPLETE_SOURCE, "DASH 轨道缺少有效编码或带宽信息");
            }
            JsonNode segments = first(track, "SegmentBase", "segment_base");
            String initialization = first(segments, "Initialization", "initialization").asText();
            String indexRange = first(segments, "indexRange", "index_range").asText();
            validateRange(initialization);
            validateRange(indexRange);
            String url = first(track, "baseUrl", "base_url").asText();
            validateCdnUrl(url);
            List<String> backups = parseBackups(first(track, "backupUrl", "backup_url"));
            Instant expiry = validateExpiry(url, backups, resolvedAt);
            result.add(new MediaTrackSource(id, video ? id : 0, video ? "q" + id : "audio-" + id,
                    video ? qualityLabel(data, id) : "音频", mime, codecs, bandwidth, width, height,
                    first(track, "frameRate", "frame_rate").asText(), initialization, indexRange, url, backups, expiry));
        }
        return result;
    }

    private static ResolvedVideoSource withFallback(ResolvedVideoSource primary, Mp4VideoSource fallback) {
        if (fallback == null) return primary;
        return new ResolvedVideoSource(fallback.url(), fallback.backupUrls(), primary.format(), primary.quality(),
                primary.streamDurationMs(), primary.resolvedAt(), earlier(primary.expiresAt(), fallback.expiresAt()),
                primary.dash(), fallback);
    }

    private static Integer positiveDimension(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
    }

    private static JsonNode first(JsonNode node, String camel, String snake) {
        return node.has(camel) ? node.path(camel) : node.path(snake);
    }

    private static long durationTolerance(VideoDescriptor descriptor) {
        return Math.max(2000, Math.min(descriptor.durationMs() / 200, 10000));
    }

    private static List<String> parseBackups(JsonNode nodes) {
        if (nodes.isMissingNode() || nodes.isNull()) return List.of();
        if (!nodes.isArray() || nodes.size() > 16) throw failure(FailureCode.INCOMPLETE_SOURCE, "CDN 备用地址列表无效");
        List<String> backups = new ArrayList<>();
        for (JsonNode node : nodes) {
            String url = node.asText();
            validateCdnUrl(url);
            if (!backups.contains(url)) backups.add(url);
        }
        return backups;
    }

    private static void validateRange(String range) {
        Matcher match = BYTE_RANGE.matcher(range);
        if (!match.matches()) throw failure(FailureCode.INCOMPLETE_SOURCE, "DASH 缺少有效初始化或索引范围");
        long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
        if (end < start) throw failure(FailureCode.INCOMPLETE_SOURCE, "DASH 字节范围顺序无效");
    }

    private static Instant validateExpiry(String url, List<String> backups, Instant resolvedAt) {
        Instant earliest = parseDeadline(url);
        if (earliest != null && !earliest.isAfter(resolvedAt.plusSeconds(5))) {
            throw failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "B站返回的临时视频源已过期或即将过期，请重试");
        }
        for (String backup : backups) {
            Instant deadline = parseDeadline(backup);
            if (deadline != null && !deadline.isAfter(resolvedAt.plusSeconds(5))) {
                throw failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "B站返回的备用视频源已过期或即将过期，请重试");
            }
            earliest = earlier(earliest, deadline);
        }
        return earliest;
    }

    private static Instant earlier(Instant a, Instant b) { return a == null ? b : b == null || a.isBefore(b) ? a : b; }

    private static String qualityLabel(JsonNode data, int quality) {
        for (JsonNode format : data.path("support_formats")) {
            if (format.path("quality").asInt() == quality) {
                String label = first(format, "new_description", "display_desc").asText();
                if (!StringUtils.hasText(label)) label = format.path("description").asText();
                if (StringUtils.hasText(label)) return label;
            }
        }
        return switch (quality) {
            case 16 -> "360p"; case 32 -> "480p"; case 64 -> "720p"; case 74 -> "720p 60帧";
            case 80 -> "1080p"; case 112 -> "1080p 高码率"; case 116 -> "1080p 60帧";
            case 120 -> "4K"; case 125 -> "HDR"; case 126 -> "杜比视界"; case 127 -> "8K";
            default -> "Q" + quality;
        };
    }

    private static boolean flagged(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.asBoolean(false) || value.asLong(0) > 0) return true;
        }
        return false;
    }

    private static JsonNode requireSuccess(JsonNode json) {
        if (!json.path("code").isIntegralNumber()) {
            throw failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "B站响应缺少状态码，请稍后重试");
        }
        int code = json.path("code").asInt();
        if (code == -101 || code == -111) {
            throw failure(FailureCode.CREDENTIALS_INVALID, "本房间 Bilibili Cookie 已失效，请 Owner / Root 更新");
        }
        if (code == -412 || code == -429 || code >= 500) {
            throw failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "B站暂时限制请求或服务不可用，请稍后重试");
        }
        if (code != 0) {
            throw failure(FailureCode.CONTENT_UNAVAILABLE, "B站视频不可访问或受限制（错误码 " + code + "）");
        }
        return json.path("data");
    }

    static void validateCdnUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            // P1 real /playurl returned HTTPS mcdn.bilivideo.cn:8082 and standard bilivideo.com backups.
            boolean standardCdn = host.endsWith(".bilivideo.com");
            boolean mcdn = host.endsWith(".mcdn.bilivideo.cn");
            boolean verifiedPort = uri.getPort() == -1 || uri.getPort() == 443 || (mcdn && uri.getPort() == 8082);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || !verifiedPort || uri.getFragment() != null
                    || !(standardCdn || mcdn) || uri.getPath().isEmpty()) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw failure(FailureCode.UNSUPPORTED_SOURCE, "B站返回了未验证的 CDN 地址或非 HTTPS 源，已拒绝播放");
        }
    }

    static Instant parseDeadline(String url) {
        return VideoSourceExpiry.deadline(url);
    }

    private static void checkDuration(long durationMs, int maxMinutes) {
        if (durationMs <= 0) throw failure(FailureCode.INCOMPLETE_SOURCE, "视频分 P 缺少有效时长");
        if (durationMs > (long) maxMinutes * 60000) {
            throw failure(FailureCode.VIDEO_TOO_LONG, "所选 P 超过视频时长上限（" + maxMinutes + " 分钟）");
        }
    }

    public static Music toMusic(VideoDescriptor descriptor) {
        return new Music(descriptor.canonicalId(), displayTitle(descriptor), List.of(descriptor.ownerName()),
                descriptor.durationMs(), PLATFORM, descriptor.coverUrl());
    }

    public static PlayableMusic toPlayableMusic(VideoDescriptor descriptor, ResolvedVideoSource source) {
        return new PlayableMusic(descriptor.canonicalId(), displayTitle(descriptor), List.of(descriptor.ownerName()),
                descriptor.durationMs(), PLATFORM, source.url(), descriptor.coverUrl(), false,
                (source.dash() == null ? "MP4" : "DASH") + " / Q" + source.quality());
    }
    private static String displayTitle(VideoDescriptor d) {
        return "PGC".equals(d.contentType())?d.title()+" · "+d.episodeTitle()+" "+d.pageTitle():d.title();
    }

    private static Throwable sanitizeFailure(Throwable error) {
        if (error instanceof VideoSourceException) return error;
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401) return failure(FailureCode.CREDENTIALS_INVALID, "本房间 Bilibili Cookie 已失效，请管理员更新");
            if (status == 403 || status == 412 || status == 429 || status >= 500) {
                return failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "B站网络或风控暂时不可用（HTTP " + status + "），请稍后重试");
            }
            return failure(FailureCode.CONTENT_UNAVAILABLE, "B站视频请求失败（HTTP " + status + "）");
        }
        if (error instanceof WebClientRequestException || error instanceof TimeoutException || error instanceof DecodingException) {
            return failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "无法取得 B站视频源，请检查服务器网络 / DNS 后重试");
        }
        return failure(FailureCode.TEMPORARY_SOURCE_FAILURE, "B站视频源解析失败，请稍后重试");
    }

    private static VideoSourceException failure(FailureCode code, String message) {
        return new VideoSourceException(code, message);
    }
}
