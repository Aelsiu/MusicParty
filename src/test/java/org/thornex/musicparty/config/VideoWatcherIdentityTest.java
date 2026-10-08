package org.thornex.musicparty.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class VideoWatcherIdentityTest {
    @Test void reconnectReusesVerifiedIdentityAndTamperedCookieCannotChooseOne() throws Exception {
        var filter = new VideoWatcherIdentity();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res) -> {});
        String header = response.getHeader("Set-Cookie");
        assertTrue(header.contains("HttpOnly; SameSite=Lax"));
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        String id = (String)request.getAttribute(VideoWatcherIdentity.ATTRIBUTE);
        assertEquals(id,filter.verify(value));
        var second = new MockHttpServletRequest();
        second.setCookies(new Cookie("mp_video_watcher",value));
        filter.doFilter(second,new MockHttpServletResponse(),(req,res) -> {});
        assertEquals(id,second.getAttribute(VideoWatcherIdentity.ATTRIBUTE));
        assertNull(filter.verify(value.substring(0,33) + "A".repeat(43)));
        var attributes = new HashMap<String,Object>();
        assertTrue(filter.beforeHandshake(new ServletServerHttpRequest(second),
                new ServletServerHttpResponse(response),null,attributes));
        assertEquals(id,attributes.get(VideoWatcherIdentity.ATTRIBUTE));
        assertFalse(attributes.containsKey("user-token"));
    }
}
