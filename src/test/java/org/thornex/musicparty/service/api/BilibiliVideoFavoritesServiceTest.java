package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.VideoFavorites.Failure;
import static org.junit.jupiter.api.Assertions.*;

class BilibiliVideoFavoritesServiceTest {
    @Test void strictShareFormats() {
        assertEquals("123",BilibiliFavoriteLinkParser.parse("https://space.bilibili.com/12/favlist?fid=123&from=share"));
        assertEquals("123",BilibiliFavoriteLinkParser.parse("https://www.bilibili.com/medialist/play/12?business=space&business_id=123"));
        for(String link:new String[]{"https://space.bilibili.com/12/favlist?fid=01","https://space.bilibili.com/12/favlist?fid=1&fid=2","https://user@space.bilibili.com/12/favlist?fid=1","https://space.bilibili.com.evil/12/favlist?fid=1","https://space.bilibili.com:444/12/favlist?fid=1","https://space.bilibili.com/12/favlist?fid=1#x","https://b23.tv/abc","https://www.bilibili.com/medialist/play/12?business=other&business_id=1"}) assertThrows(Failure.class,()->BilibiliFavoriteLinkParser.parse(link),link);
    }
    @Test void preservesInvalidAndUnsupportedItemsAndUpstreamHasMore() throws Exception {
        var page=BilibiliVideoFavoritesService.parse(new ObjectMapper().readTree("""
            {"code":0,"data":{"info":{"title":"Fixture"},"has_more":true,"medias":[
            {"id":1,"type":2,"bvid":"BV1GJ411x7h7","title":"ok","duration":12},
            {"id":2,"type":2,"bvid":"BV1GJ411x7h7","title":"已失效视频"},
            {"id":3,"type":12,"title":"audio"}]}}
            """),"123",2);
        assertEquals(3,page.items().size()); assertTrue(page.hasMore());
        assertEquals("21:1",page.items().getFirst().itemId()); assertEquals(12000,page.items().getFirst().durationMs());
        assertFalse(page.items().get(1).selectable()); assertEquals("UNSUPPORTED_RESOURCE",page.items().get(2).reasonCode());
    }
    @Test void distinguishesEmptyFromDeniedOrMalformed() throws Exception {
        ObjectMapper json=new ObjectMapper();
        assertTrue(BilibiliVideoFavoritesService.parse(json.readTree("{\"code\":0,\"data\":{\"info\":{},\"has_more\":false,\"medias\":null}}"),"1",1).items().isEmpty());
        assertEquals("CONTENT_UNAVAILABLE",assertThrows(Failure.class,()->BilibiliVideoFavoritesService.parse(json.readTree("{\"code\":-404}"),"1",1)).getCode());
        assertEquals("CREDENTIALS_INVALID",assertThrows(Failure.class,()->BilibiliVideoFavoritesService.parse(json.readTree("{\"code\":-101}"),"1",1)).getCode());
        assertThrows(Failure.class,()->BilibiliVideoFavoritesService.parse(json.readTree("{\"code\":0,\"data\":{}}"),"1",1));
    }
}
