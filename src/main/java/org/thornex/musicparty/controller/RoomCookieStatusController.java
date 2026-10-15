package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.room.RoomCookieStatusService;
import org.thornex.musicparty.room.RoomHttpFilter;
import java.util.Map;

@RestController @RequestMapping("/api/admin/cookie-status")
public class RoomCookieStatusController {
    private final RoomCookieStatusService status;
    public RoomCookieStatusController(RoomCookieStatusService status) {this.status=status;}
    @GetMapping public Map<String,String> get(HttpServletRequest request) {return status.status(RoomHttpFilter.managementToken(request));}
}
