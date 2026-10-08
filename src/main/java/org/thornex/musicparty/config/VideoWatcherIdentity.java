package org.thornex.musicparty.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** An opaque server-signed browser identity, absent from STOMP bodies and public snapshots. */
@Component
public class VideoWatcherIdentity extends OncePerRequestFilter implements HandshakeInterceptor {
    public static final String ATTRIBUTE = "musicParty.verifiedWatcher";
    private static final String COOKIE = "mp_video_watcher";
    private final byte[] signingKey = new byte[32];
    public VideoWatcherIdentity() { new SecureRandom().nextBytes(signingKey); }
    private String signature(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    String verify(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}")) return null;
        String id = value.substring(0, 32);
        return MessageDigest.isEqual(signature(id).getBytes(StandardCharsets.US_ASCII),
                value.substring(33).getBytes(StandardCharsets.US_ASCII)) ? id : null;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies())
            if (COOKIE.equals(cookie.getName())) { id = verify(cookie.getValue()); break; }
        if (id == null) {
            byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            response.addHeader("Set-Cookie", COOKIE + "=" + id + "." + signature(id)
                    + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax" + (request.isSecure() ? "; Secure" : ""));
        }
        request.setAttribute(ATTRIBUTE, id);
        chain.doFilter(request, response);
    }
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet) {
            Object identity = servlet.getServletRequest().getAttribute(ATTRIBUTE);
            if (identity instanceof String id) attributes.put(ATTRIBUTE, id);
        }
        return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {}
}
