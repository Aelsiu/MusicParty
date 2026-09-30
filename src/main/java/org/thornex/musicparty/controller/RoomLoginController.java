package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.room.*;
import java.util.Map;

@RestController @RequestMapping("/api/admin/netease-login")
public class RoomLoginController {
    private final RoomQrLoginService login;
    public RoomLoginController(RoomQrLoginService login) { this.login=login; }
    @PostMapping public Map<String,Object> create(HttpServletRequest request) { return login.create(RoomHttpFilter.managementToken(request)); }
    @GetMapping("/{task}") public Map<String,String> check(@PathVariable String task,HttpServletRequest request) { return login.check(task,RoomHttpFilter.managementToken(request)); }
    @DeleteMapping("/{task}") public void cancel(@PathVariable String task,HttpServletRequest request) { login.cancel(task,RoomHttpFilter.managementToken(request)); }
}
