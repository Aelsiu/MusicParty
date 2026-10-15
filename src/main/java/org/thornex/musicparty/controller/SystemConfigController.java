package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.dto.AdminConfigUpdateRequest;
import org.thornex.musicparty.dto.SystemConfigSnapshot;
import org.thornex.musicparty.room.RoomAccessService;
import org.thornex.musicparty.room.RoomHttpFilter;
import org.thornex.musicparty.room.SystemConfigService;

import java.util.Map;

@RestController
@RequestMapping("/api/rooms/system-config")
public class SystemConfigController {
    private final RoomAccessService access;
    private final SystemConfigService systemConfig;

    public SystemConfigController(RoomAccessService access, SystemConfigService systemConfig) {
        this.access = access;
        this.systemConfig = systemConfig;
    }

    @GetMapping
    public SystemConfigSnapshot get(HttpServletRequest request) {
        access.root(RoomHttpFilter.managementToken(request));
        return systemConfig.snapshot();
    }

    @PostMapping
    public Map<String, String> update(@RequestBody AdminConfigUpdateRequest body, HttpServletRequest request) {
        access.root(RoomHttpFilter.managementToken(request));
        systemConfig.update(body);
        return Map.of("message", "全局系统参数已更新");
    }
}
