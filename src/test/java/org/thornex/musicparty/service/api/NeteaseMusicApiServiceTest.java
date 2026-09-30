package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseMusicApiServiceTest {

    @Test void chorusTimesRemainMillisecondsAndMalformedRowsAreIgnored() throws Exception {
        var mapper=new ObjectMapper();
        var result=NeteaseMusicApiService.parseChorus(mapper.readTree("""
                {"code":200,"chorus":[{"id":2058263032,"startTime":152916,"endTime":178690,"ugcLocked":0}]}
                """),"2058263032");
        assertEquals(java.util.List.of(152916L),result);
        assertEquals(java.util.List.of(1000L,2000L),NeteaseMusicApiService.parseChorus(mapper.readTree("""
                {"data":[{"id":1,"startTime":2000},{"id":1,"startTime":1000},{"id":1,"startTime":1000},
                {"id":2,"startTime":3000},{"id":1,"startTime":-1},{"id":1},{"id":1,"startTime":"bad"}]}
                """),"1"));
        assertTrue(NeteaseMusicApiService.parseChorus(mapper.readTree("{\"data\":null}"),"1").isEmpty());
        assertTrue(service.getChorus("voice-id").block().isEmpty());
    }

    private AppProperties props;
    private NeteaseMusicApiService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getNetease().setQuality("exhigh");
        service = new NeteaseMusicApiService(WebClient.builder().build(), props);
    }

    @Test
    void isCookieConfiguredReflectsCookieState() {
        assertFalse(service.isCookieConfigured(), "未配置应为 false");
        props.getNetease().setCookie("MUSIC_U=abc; __csrf=def");
        assertTrue(service.isCookieConfigured(), "已配置应为 true");
        props.getNetease().setCookie("YOUR_NETEASE_COOKIE_STRING_HERE");
        assertFalse(service.isCookieConfigured(), "占位符不算配置");
    }

    @Test
    void resolveBrMapsLevelToBitrate() {
        assertEquals(320_000, service.resolveBr("exhigh"));
        assertEquals(320_000, service.resolveBr("EXHIGH"));
        assertEquals(128_000, service.resolveBr("standard"));
        assertEquals(192_000, service.resolveBr("higher"));
        assertEquals(999_000, service.resolveBr("lossless"));
        assertEquals(999_000, service.resolveBr("hires"));
        assertEquals(999_000, service.resolveBr("jyeffect"));
        assertEquals(320_000, service.resolveBr("unknown"));
        assertEquals(320_000, service.resolveBr(null));
    }

    @Test
    void songUrlUsesReturnedQualityAndLeavesMissingQualityUnknown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var downgraded = NeteaseMusicApiService.parseSongUrl(mapper.readTree(
                "{\"data\":[{\"url\":\"https://example.com/song.mp3\",\"level\":\"exhigh\"}]}"));
        assertEquals("https://example.com/song.mp3", downgraded.url());
        assertEquals("exhigh", downgraded.actualQuality());

        var missing = NeteaseMusicApiService.parseSongUrl(mapper.readTree(
                "{\"data\":[{\"url\":\"https://example.com/song.mp3\",\"br\":999000}]}"));
        assertNull(missing.actualQuality());

        var unavailable = NeteaseMusicApiService.parseSongUrl(mapper.readTree(
                "{\"data\":[{\"url\":null,\"level\":\"jyeffect\"}]}"));
        assertEquals("", unavailable.url());
    }
}
