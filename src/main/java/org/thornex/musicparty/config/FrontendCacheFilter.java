package org.thornex.musicparty.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** The document must revalidate the playback protocol; fingerprinted assets remain cacheable. */
@Component
public class FrontendCacheFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/".equals(path) || "/index.html".equals(path) || path.matches("/[A-Za-z0-9]{8}/?"))
            response.setHeader("Cache-Control","no-store");
        chain.doFilter(request,response);
    }
}
