package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ResolvedVideoSource;
import org.thornex.musicparty.dto.VideoDescriptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.thornex.musicparty.service.api.BilibiliVideoApiService.FailureCode.*;

class BilibiliVideoApiServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BV = "BV1GJ411x7h7";
    private static final String URL = "https://upos-sz-estgcos.bilivideo.com/unit-test.mp4";
    private static final VideoDescriptor DESCRIPTOR = new VideoDescriptor(BV + ":p1", BV, 123, 1,
            "Title", "First part", "Owner", 213000, "https://i0.hdslb.com/cover.jpg");

    @Test
    void acceptsBvCanonicalAndOrdinaryPageLinkWithoutChangingCase() {
        assertEquals(BV, BilibiliVideoApiService.normalizeBvid(BV));
        assertEquals(BV, BilibiliVideoApiService.normalizeBvid(BV + ":p1"));
        assertEquals(BV, BilibiliVideoApiService.normalizeBvid("https://www.bilibili.com/video/" + BV + "/?p=1&from=search"));
        assertEquals(BV + ":p2", BilibiliVideoApiService.normalizeCanonicalId(BV + ":p2"));
        assertEquals(BV + ":p2", BilibiliVideoApiService.normalizeCanonicalId("https://www.bilibili.com/video/" + BV + "?p=2"));
        assertEquals(BV + ":p1", BilibiliVideoApiService.normalizeCanonicalId(BV));
    }

    @Test
    void rejectsInvalidPagesAndUnsupportedInputIncludingDisguisedDomains() {
        for (String input : new String[]{BV + ":p0", BV + ":p01", BV + ":p1001", BV + ":p-1",
                "https://www.bilibili.com/video/" + BV + "?p=bad",
                "https://www.bilibili.com/video/" + BV + "?p=1&p=2", "av170001", "https://b23.tv/example",
                URL, "https://www.bilibili.com.evil.example/video/" + BV, "http://www.bilibili.com/video/" + BV}) {
            var ex = assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> BilibiliVideoApiService.normalizeBvid(input), input);
            assertEquals(UNSUPPORTED_INPUT, ex.getCode());
        }
    }

    @Test
    void trustsPagesFirstEntryRatherThanAggregateDurationAndCid() throws Exception {
        var descriptor = BilibiliVideoApiService.parseDescriptor(json("""
                {"code":0,"data":{"bvid":"BV1GJ411x7h7","cid":999,"duration":999999,"title":"Multi",
                 "owner":{"name":"Owner"},"pic":"http://i0.hdslb.com/cover.jpg",
                 "pages":[{"cid":123,"page":1,"duration":213,"part":"First"},
                          {"cid":124,"page":2,"duration":99999,"part":"Second"}]}}
                """), BV, 120);
        assertEquals(123, descriptor.cid());
        assertEquals(213000, descriptor.durationMs());
        assertEquals("First", descriptor.pageTitle());
        assertEquals(2, descriptor.pages().size());
        assertFalse(descriptor.pages().get(1).withinDurationLimit());
        assertEquals("https://i0.hdslb.com/cover.jpg", descriptor.coverUrl());
        assertEquals("bilibili-video", BilibiliVideoApiService.toMusic(descriptor).platform());
    }

    @Test
    void enforcesIndependentVideoLimitAndRejectsIncompleteMetadata() throws Exception {
        String complete = "{\"code\":0,\"data\":{\"bvid\":\"" + BV + "\",\"title\":\"Title\",\"pages\":[{\"cid\":123,\"page\":1,\"duration\":213}]}}";
        assertEquals(VIDEO_TOO_LONG, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseDescriptor(json(complete), BV, 1)).getCode());
        assertEquals(INCOMPLETE_SOURCE, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseDescriptor(json(complete.replace("\"duration\":213", "\"duration\":0")), BV, 120)).getCode());
    }

    @Test
    void validatesFullSingleMp4AndUsesActualQuality() throws Exception {
        ResolvedVideoSource source = BilibiliVideoApiService.parseSource(sourceJson("mp4", 212394, 212394, "[]", ""), DESCRIPTOR, Instant.EPOCH);
        assertEquals(16, source.quality());
        assertEquals(212394, source.streamDurationMs());
        assertNull(source.expiresAt());
        assertFalse(BilibiliVideoApiService.toPlayableMusic(DESCRIPTOR, source).needsProxy());
        assertFalse(source.toString().contains(URL));
        assertEquals("mp4480", BilibiliVideoApiService.parseSource(sourceJson("mp4480", 212394, 212394, "[]", ""),
                DESCRIPTOR, Instant.EPOCH).format());
    }

    @Test
    void rejectsIncompleteDashPreviewMissingLengthShortenedAndMultipleSegments() throws Exception {
        for (JsonNode response : new JsonNode[]{sourceJson("dash", 212394, 212394, "[]", ""),
                sourceJson("mp4", 212394, 212394, "[]", ",\"is_preview\":1"),
                sourceJson("mp4", 212394, 0, "[]", ""), sourceJson("mp4", 60000, 60000, "[]", ""),
                json("{\"code\":0,\"data\":{\"format\":\"mp4\",\"durl\":[{},{}]}}")}) {
            assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.EPOCH));
        }
    }

    @Test
    void rejectsUnverifiedUrlAndBackupRatherThanExposingThem() throws Exception {
        for (String value : new String[]{"http://upos-sz-estgcos.bilivideo.com/test.mp4", "https://evil.example/test.mp4",
                "https://bilivideo.com.evil.example/test.mp4", "https://user@upos-sz-estgcos.bilivideo.com/test.mp4"}) {
            var ex = assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> BilibiliVideoApiService.validateCdnUrl(value));
            assertFalse(ex.getMessage().contains(value));
        }
        assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseSource(sourceJson("mp4", 212394, 212394,
                        "[\"https://evil.example/test.mp4\"]", ""), DESCRIPTOR, Instant.EPOCH));
    }

    @Test
    void understandsOnlyTrustedDeadlineAndDoesNotInventExpiry() {
        assertEquals(Instant.ofEpochSecond(1791086400), BilibiliVideoApiService.parseDeadline(URL + "?deadline=1791086400"));
        assertNull(BilibiliVideoApiService.parseDeadline(URL + "?expires=1791086400"));
        assertNull(BilibiliVideoApiService.parseDeadline(URL + "?deadline=unknown"));
        assertNull(BilibiliVideoApiService.parseDeadline(URL + "?deadline=1791086400&deadline=1791086500"));
    }

    @Test
    void distinguishesMissingInvalidAndTemporaryCredentialsErrors() throws Exception {
        BilibiliCookieService cookies = mock(BilibiliCookieService.class);
        var properties = new AppProperties();
        properties.getBilibili().setBaseUrl("https://api.bilibili.com");
        var api = new BilibiliVideoApiService(WebClient.create(), properties, cookies);
        assertEquals(CREDENTIALS_MISSING, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> api.getVideoDescriptor(BV).block()).getCode());
        assertEquals(CREDENTIALS_INVALID, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseSource(json("{\"code\":-101}"), DESCRIPTOR, Instant.EPOCH)).getCode());
        var temporary = assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseSource(json("{\"code\":-412}"), DESCRIPTOR, Instant.EPOCH));
        assertTrue(temporary.isRetryable());
    }

    @Test
    void requestsDashAndSeparateMuxedFallbackAndNeverRequestsMedia() throws Exception {
        List<java.net.URI> requested = new ArrayList<>();
        var response = sourceJson("mp4", 212394, 212394, "[]", "").toString();
        var dash = dashJson("").toString();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requested.add(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body(request.url().getQuery().contains("fnval=4048") ? dash : response).build());
        }).build();
        BilibiliCookieService cookies = mock(BilibiliCookieService.class);
        when(cookies.hasCookie()).thenReturn(true);
        when(cookies.getCookieHeader()).thenReturn(Mono.just(""));
        var properties = new AppProperties();
        properties.getBilibili().setBaseUrl("https://api.bilibili.com");
        var source = new BilibiliVideoApiService(client, properties, cookies).resolveSource(DESCRIPTOR).block();
        assertEquals(2, requested.size());
        assertTrue(requested.stream().allMatch(uri -> "api.bilibili.com".equals(uri.getHost()) && "/x/player/playurl".equals(uri.getPath())));
        assertEquals("bvid=" + BV + "&cid=123&fnval=4048&qn=80&fourk=1", requested.get(0).getQuery());
        assertEquals("bvid=" + BV + "&cid=123&fnval=1&platform=html5&qn=32", requested.get(1).getQuery());
        assertNotNull(source.dash());
        assertNotNull(source.mp4());
        assertEquals(URL, source.url());
    }

    @Test
    void emptyResponseFailsBothMetadataAndSourceInsteadOfLeakingTheirCompletionTickets() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.OK, HttpStatus.NO_CONTENT}) {
            WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                    ClientResponse.create(status).header("Content-Type", "application/json").build())).build();
            BilibiliCookieService cookies = mock(BilibiliCookieService.class);
            when(cookies.hasCookie()).thenReturn(true);
            when(cookies.getCookieHeader()).thenReturn(Mono.just(""));
            AppProperties properties = new AppProperties();
            properties.getBilibili().setBaseUrl("https://api.bilibili.com");
            BilibiliVideoApiService api = new BilibiliVideoApiService(client, properties, cookies);

            var metadataFailure = assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> api.getVideoDescriptor(BV).block(), status.toString());
            var sourceFailure = assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> api.resolveSource(DESCRIPTOR).block(), status.toString());
            assertEquals(TEMPORARY_SOURCE_FAILURE, metadataFailure.getCode());
            assertEquals(TEMPORARY_SOURCE_FAILURE, sourceFailure.getCode());
        }
    }

    @Test
    void selectsTrustedSecondPageWithoutUsingAggregateCidAndValidatesEachPagesLimit() throws Exception {
        JsonNode metadata = json("""
                {"code":0,"data":{"bvid":"BV1GJ411x7h7","cid":999,"duration":999999,"title":"Multi",
                 "pages":[{"cid":123,"page":1,"duration":99999,"part":"Long"},
                          {"cid":124,"page":2,"duration":213,"part":"Selected"}]}}
                """);
        var descriptor = BilibiliVideoApiService.parseDescriptor(metadata, BV + ":p2", 120);
        assertEquals(BV + ":p2", descriptor.canonicalId());
        assertEquals(124, descriptor.cid());
        assertEquals(2, descriptor.page());
        assertEquals(213000, descriptor.durationMs());
        assertFalse(descriptor.pages().getFirst().withinDurationLimit());
        assertTrue(descriptor.pages().get(1).withinDurationLimit());
        assertEquals(VIDEO_TOO_LONG, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseDescriptor(metadata, BV, 120)).getCode());
        assertEquals(UNSUPPORTED_INPUT, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseDescriptor(metadata, BV + ":p3", 120)).getCode());
        var selectable = BilibiliVideoApiService.parseDescriptor(metadata, BV, 120, false);
        assertEquals(2, selectable.pages().size());
        assertFalse(selectable.pages().getFirst().withinDurationLimit());
    }

    @Test
    void rejectsMissingOrDuplicateTrustedPagesRatherThanGuessingCid() throws Exception {
        for (String parts : List.of(
                "{\"cid\":1,\"page\":1,\"duration\":2},{\"cid\":2,\"page\":3,\"duration\":2}",
                "{\"cid\":1,\"page\":1,\"duration\":2},{\"cid\":1,\"page\":2,\"duration\":2}",
                "{\"cid\":1,\"page\":1,\"duration\":2},{\"cid\":2,\"page\":2,\"duration\":0}")) {
            JsonNode data = json("{\"code\":0,\"data\":{\"bvid\":\"" + BV + "\",\"title\":\"Title\",\"pages\":[" + parts + "]}}");
            assertEquals(INCOMPLETE_SOURCE, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> BilibiliVideoApiService.parseDescriptor(data, BV, 120)).getCode());
        }
    }

    @Test
    void parsesCompleteDashAudioVideoRangesCodecsAndActualQualityOptions() throws Exception {
        var source = BilibiliVideoApiService.parseSource(dashJson(""), DESCRIPTOR, Instant.EPOCH);
        assertEquals("dash", source.format());
        assertNull(source.url(), "A DASH video-only URL is not a muxed fallback");
        assertNull(source.mp4());
        assertEquals(212394, source.dash().durationMs());
        assertEquals(1.5, source.dash().minBufferTime());
        assertEquals(2, source.dash().videoTracks().size());
        assertEquals(1, source.dash().audioTracks().size());
        var video = source.dash().videoTracks().getFirst();
        assertEquals("q80", video.qualityId());
        assertEquals("1080p", video.qualityLabel());
        assertEquals("avc1.640028", video.codecs());
        assertEquals("0-1000", video.initialization());
        assertEquals("1001-2000", video.indexRange());
        assertEquals(1920, video.width());
        assertEquals("audio/mp4", source.dash().audioTracks().getFirst().mimeType());
        assertFalse(source.toString().contains(URL));
        assertFalse(source.dash().toString().contains(URL));
    }

    @Test
    void supportsBilibiliSnakeCaseSegmentBaseAliases() throws Exception {
        String response = dashJson("").toString().replace("baseUrl", "base_url").replace("backupUrl", "backup_url")
                .replace("mimeType", "mime_type").replace("frameRate", "frame_rate")
                .replace("SegmentBase", "segment_base").replace("Initialization", "initialization")
                .replace("indexRange", "index_range").replace("minBufferTime", "min_buffer_time");
        var source = BilibiliVideoApiService.parseSource(json(response), DESCRIPTOR, Instant.EPOCH);
        assertEquals(2, source.dash().videoTracks().size());
        assertEquals("0-1000", source.dash().audioTracks().getFirst().initialization());
    }

    @Test
    void rejectsMissingAudioMalformedRangesAndMismatchedDashDuration() throws Exception {
        String complete = dashJson("").toString();
        for (String malformed : List.of(complete.replace("\"audio\":[", "\"missingAudio\":["),
                complete.replace("0-1000", "1000-0"), complete.replace("1001-2000", "garbage"),
                complete.replace("\"duration\":213", "\"duration\":60"),
                complete.replace("mp4a.40.2", "unknown-codec"))) {
            assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                    () -> BilibiliVideoApiService.parseSource(json(malformed), DESCRIPTOR, Instant.EPOCH));
        }
    }

    @Test
    void expiryCoversBothTracksAndBackupHostsAndRejectsNearExpiredSources() throws Exception {
        var response = dashJson("");
        var dash = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("data").path("dash");
        ((com.fasterxml.jackson.databind.node.ObjectNode) dash.path("video").get(0)).put("baseUrl", URL + "?deadline=1791086400");
        ((com.fasterxml.jackson.databind.node.ObjectNode) dash.path("audio").get(0)).putArray("backupUrl")
                .add(URL + "?deadline=1791086300");
        var source = BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.ofEpochSecond(1791080000));
        assertEquals(Instant.ofEpochSecond(1791086300), source.expiresAt());
        assertTrue(source.hasUnknownExpiry(), "Other provided tracks without deadline still require soft freshness");
        assertEquals(Instant.ofEpochSecond(1791086400), source.dash().videoTracks().getFirst().expiresAt());
        assertEquals(TEMPORARY_SOURCE_FAILURE, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.ofEpochSecond(1791086295))).getCode());
    }

    @Test
    void dashResolutionFallsBackToFullMp4WhenDashAudioIsUnavailable() throws Exception {
        String badDash = dashJson("").toString().replace("mp4a.40.2", "unknown");
        String mp4 = sourceJson("mp4", 212394, 212394, "[]", "").toString();
        var api = stubApi(request -> request.url().getQuery().contains("fnval=4048") ? badDash : mp4);
        var source = api.resolveSource(DESCRIPTOR).block();
        assertNull(source.dash());
        assertNotNull(source.mp4());
        assertEquals(URL, source.url());
    }

    @Test
    void goodDashSurvivesUnavailableOptionalMp4Fallback() throws Exception {
        String dash = dashJson("").toString();
        var api = stubApi(request -> request.url().getQuery().contains("fnval=4048") ? dash : "{\"code\":-412}");
        var source = api.resolveSource(DESCRIPTOR).block();
        assertNotNull(source.dash());
        assertNull(source.mp4());
        assertNull(source.url());
    }

    @Test
    void onlyPermitsObservedMcdnNamespaceAndPortWithoutExpandingArbitraryCnHosts() {
        assertDoesNotThrow(() -> BilibiliVideoApiService.validateCdnUrl("https://xy221x204x173x170xy.mcdn.bilivideo.cn:8082/video.m4s"));
        assertDoesNotThrow(() -> BilibiliVideoApiService.validateCdnUrl("https://upos-sz-mirrorcos.bilivideo.com:443/video.m4s"));
        for (String url : List.of("https://evil.bilivideo.cn/video.m4s", "https://xy.mcdn.bilivideo.cn.evil.example/video.m4s",
                "http://xy.mcdn.bilivideo.cn:8082/video.m4s", "https://xy.mcdn.bilivideo.cn:8081/video.m4s",
                "https://upos-sz-mirrorcos.bilivideo.com:8082/video.m4s")) {
            assertThrows(BilibiliVideoApiService.VideoSourceException.class, () -> BilibiliVideoApiService.validateCdnUrl(url));
        }
    }

    @Test
    void knownMp4ExpiryDoesNotHideUnknownBackupExpiry() throws Exception {
        var known = sourceJson("mp4", 212394, 212394, "[]", "").toString()
                .replace(URL, URL + "?deadline=1791086400");
        var source = BilibiliVideoApiService.parseSource(json(known), DESCRIPTOR, Instant.EPOCH);
        assertFalse(source.hasUnknownExpiry());
        var mixed = (com.fasterxml.jackson.databind.node.ObjectNode) json(known).path("data").path("durl").get(0);
        mixed.putArray("backup_url").add(URL);
        var response = sourceJson("mp4", 212394, 212394, "[]", "");
        ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("data").path("durl")).set(0, mixed);
        var unknownBackup = BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.EPOCH);
        assertEquals(Instant.ofEpochSecond(1791086400), unknownBackup.expiresAt());
        assertTrue(unknownBackup.hasUnknownExpiry());
    }

    private static BilibiliVideoApiService stubApi(java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest, String> response) {
        return stubApi(response, new AppProperties());
    }

    @Test void currentInstanceRenewalRetainsDurationAdmissionButStillEnforcesSourcePolicy() throws Exception {
        var properties = new AppProperties();
        properties.getPlayer().setVideoMaxDurationMinutes(1);
        String response = sourceJson("mp4", 212394, 212394, "[]", "").toString();
        var api = stubApi(request -> response, properties);
        assertEquals(VIDEO_TOO_LONG, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> api.resolveSource(DESCRIPTOR).block()).getCode());
        assertNotNull(api.resolveCurrentInstanceSource(DESCRIPTOR).block());
        properties.getPlayer().setVideoMaxResolution("1080P");
        assertEquals(VIDEO_QUALITY_LIMIT, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> api.resolveCurrentInstanceSource(DESCRIPTOR).block()).getCode());
        var broken = new VideoDescriptor(BV + ":p2", BV, 123, 1, "Title", "Part", "Owner", 213000, "");
        assertEquals(UNSUPPORTED_INPUT, assertThrows(BilibiliVideoApiService.VideoSourceException.class,
                () -> api.resolveCurrentInstanceSource(broken).block()).getCode());
    }

    @Test void mp4DimensionsStayUnknownUnlessExactReturnedSegmentProvidesThem() throws Exception {
        var response = sourceJson("mp4480", 212394, 212394, "[]", "");
        assertNull(BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.EPOCH).mp4().width());
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("data")).put("width", 854).put("height", 480);
        assertNull(BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.EPOCH).mp4().width(),
                "Top-level/original dimensions cannot stand in for selected stream evidence");
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("data").path("durl").get(0)).put("width", 854).put("height", 480);
        var source = BilibiliVideoApiService.parseSource(response, DESCRIPTOR, Instant.EPOCH);
        assertEquals(854, source.mp4().width()); assertEquals(480, source.mp4().height());
    }

    private static BilibiliVideoApiService stubApi(java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest, String> response,
                                                AppProperties properties) {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body(response.apply(request)).build())).build();
        BilibiliCookieService cookies = mock(BilibiliCookieService.class);
        when(cookies.hasCookie()).thenReturn(true);
        when(cookies.getCookieHeader()).thenReturn(Mono.just(""));
        properties.getBilibili().setBaseUrl("https://api.bilibili.com");
        return new BilibiliVideoApiService(client, properties, cookies);
    }

    private static JsonNode dashJson(String extra) throws Exception {
        return json("{\"code\":0,\"data\":{\"format\":\"dash\",\"quality\":80,\"timelength\":212394,\"dash\":{\"duration\":213,\"minBufferTime\":1.5,"
                + "\"video\":[" + track(80, "video/mp4", "avc1.640028") + "," + track(64, "video/mp4", "hev1.1.6.L120.90") + "],"
                + "\"audio\":[" + track(30280, "audio/mp4", "mp4a.40.2") + "]}" + extra + "}}");
    }

    private static String track(int id, String mime, String codecs) {
        return "{\"id\":" + id + ",\"baseUrl\":\"" + URL + "\",\"backupUrl\":[],\"mimeType\":\"" + mime + "\","
                + "\"codecs\":\"" + codecs + "\",\"bandwidth\":1000000,\"width\":1920,\"height\":1080,\"frameRate\":\"30000/1001\","
                + "\"SegmentBase\":{\"Initialization\":\"0-1000\",\"indexRange\":\"1001-2000\"}}";
    }

    private static JsonNode json(String value) throws Exception { return JSON.readTree(value); }
    private static JsonNode sourceJson(String format, long total, long segment, String backups, String extra) throws Exception {
        return json("{\"code\":0,\"data\":{\"format\":\"" + format + "\",\"quality\":16,\"timelength\":" + total
                + ",\"durl\":[{\"url\":\"" + URL + "\",\"length\":" + segment + ",\"backup_url\":" + backups + "}]" + extra + "}}");
    }
}
