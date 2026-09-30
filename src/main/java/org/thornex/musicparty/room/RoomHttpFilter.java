package org.thornex.musicparty.room;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@Component
public class RoomHttpFilter extends OncePerRequestFilter {
    private final RoomAccessService access;
    private final RoomRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    private org.thornex.musicparty.service.QueuePersistenceService persistence;
    public RoomHttpFilter(RoomAccessService access,RoomRepository repository) { this.access=access;this.repository=repository; }
    public static String managementToken(HttpServletRequest request) {
        String header=request.getHeader("Authorization"); return header!=null && header.startsWith("Bearer ")?header.substring(7):null;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean scoped=(path.startsWith("/api/") && !path.startsWith("/api/rooms") && !path.startsWith("/api/auth/") && !path.equals("/api/config")) || path.startsWith("/media/") || path.startsWith("/radio/");
        if(!scoped) { chain.doFilter(request,response);return; }
        String id=request.getHeader("X-Room-ID");
        if(path.startsWith("/media/rooms/")) { String[] parts=path.split("/"); id=parts.length>3?parts[3]:null; }
        if(path.startsWith("/radio/")) id=request.getParameter("roomId");
        try {
            if(id==null || !id.matches("[A-Za-z0-9]{8}")) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"请选择房间");
            repository.room(id);
            if(path.startsWith("/api/admin/")) access.own(managementToken(request),id);
            else if(!path.startsWith("/radio/")) access.member(request.getHeader("X-Room-Token")!=null?request.getHeader("X-Room-Token"):request.getParameter("roomToken"),managementToken(request)!=null?managementToken(request):request.getParameter("managerToken"),id);
            try(var ignored=RoomContext.enter(id)) {
                chain.doFilter(request,response);
                if(path.startsWith("/api/admin/") && !request.getMethod().equals("GET") && response.getStatus()<400) persistence.saveNow();
            }
        } catch(ResponseStatusException e) { response.setStatus(e.getStatusCode().value());response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"message\":\""+e.getReason()+"\"}"); }
    }
}
