package org.thornex.musicparty.room;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thornex.musicparty.controller.AdminController;
import org.thornex.musicparty.controller.RoomLoginController;

@Configuration
public class RoomMvcSecurityConfig implements WebMvcConfigurer {
    private final RoomAccessService access;
    public RoomMvcSecurityConfig(RoomAccessService access) { this.access=access; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if(handler instanceof HandlerMethod method && (AdminController.class.isAssignableFrom(method.getBeanType()) || RoomLoginController.class.isAssignableFrom(method.getBeanType()))) {
                    String room=RoomContext.current();
                    if(room==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请选择房间");
                    // Authorize by resolved controller too, including encoded and matrix-variable paths.
                    access.own(RoomHttpFilter.managementToken(request),room);
                }
                return true;
            }
        });
    }
}
