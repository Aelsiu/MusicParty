package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminPrivateDjUpdateRequest;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.PrivateDjService;
import org.thornex.musicparty.service.RoomConfigFileService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.util.Map;
import java.util.Set;
import java.io.IOException;

@RestController
@RequestMapping("/api/admin")
@org.thornex.musicparty.room.RoomScoped
public class AdminController {

    private final MusicPlayerService musicPlayerService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.thornex.musicparty.playback.RoomPlaybackCoordinator coordinator;

    private final ChatService chatService;
    private final AppProperties appProperties;
    private final String adminPassword;
    private final AuthController authController;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final BilibiliMusicApiService bilibiliMusicApiService;
    private final LiveStreamService liveStreamService;
    private final PrivateDjService privateDjService;
    private final RoomConfigFileService roomConfigFileService;

    public AdminController(MusicPlayerService musicPlayerService, ChatService chatService, AppProperties appProperties, AuthController authController, NeteaseMusicApiService neteaseMusicApiService, BilibiliMusicApiService bilibiliMusicApiService, LiveStreamService liveStreamService, PrivateDjService privateDjService, RoomConfigFileService roomConfigFileService) {
        this.musicPlayerService = musicPlayerService;
        this.chatService = chatService;
        this.adminPassword = appProperties.getAdminPassword();
        this.appProperties = appProperties;
        this.authController = authController;
        this.neteaseMusicApiService = neteaseMusicApiService;
        this.bilibiliMusicApiService = bilibiliMusicApiService;
        this.liveStreamService = liveStreamService;
        this.privateDjService = privateDjService;
        this.roomConfigFileService = roomConfigFileService;
    }

    private boolean isValid(String password) {
        return true; // RoomHttpFilter validates the revocable management session and ownership.
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody AdminVerifyRequest request) {
        if (isValid(request.password())) {
            return ResponseEntity.ok(Map.of("message", "VERIFIED"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
    }

    private ResponseEntity<?> playbackResponse(Map<String,Object> result) {
        return Boolean.TRUE.equals(result.get("ok")) ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    @PostMapping("/lock")
    public ResponseEntity<?> setLock(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return playbackResponse(coordinator.http(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"lock",body));
    }
    @PostMapping("/player/action")
    public ResponseEntity<?> playerAction(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return playbackResponse(coordinator.http(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),body.path("action").asText(),body));
    }
    @PostMapping("/media-mode")
    public ResponseEntity<?> mediaMode(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return playbackResponse(coordinator.http(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"mode",body));
    }
    @PostMapping("/room/clear")
    public ResponseEntity<?> clearData(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        if ("CHAT".equals(body.path("target").asText())) {
            coordinator.manage(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),() -> { chatService.clearHistoryAndNotify(); return null; });
            return ResponseEntity.ok(Map.of("message","聊天历史记录已清空"));
        }
        return playbackResponse(coordinator.http(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"clear",body));
    }
    @PostMapping("/system/reset")
    public ResponseEntity<?> resetSystem(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return playbackResponse(coordinator.http(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"reset",body));
    }

    @PostMapping("/config/cookie")
    public ResponseEntity<?> setCookieHttp(jakarta.servlet.http.HttpServletRequest http, @RequestBody AdminCookieRequest request) {
        return coordinator.manage(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http), () -> setCookieInMailbox(request));
    }
    private ResponseEntity<?> setCookieInMailbox(AdminCookieRequest request) {

        if ("netease".equalsIgnoreCase(request.platform())) {
            neteaseMusicApiService.updateCookie(request.value());
            // 首次配置 cookie 后即时广播，刷新控制面板总开关门禁（neteaseCookieConfigured 来自 PlayerState）
            if (coordinator == null) musicPlayerService.broadcastFullPlayerState(); else coordinator.changed();
            return ResponseEntity.ok(Map.of("message", "网易云音乐凭据已更新"));
        } else if ("bilibili".equalsIgnoreCase(request.platform())) {
            bilibiliMusicApiService.updateCookie(request.value());
            appProperties.getBilibili().setCookie(request.value());
            if (coordinator != null) coordinator.cookieChanged();
            return ResponseEntity.ok(Map.of("message", "Bilibili Cookie 已更新"));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    public ResponseEntity<?> setCookie(String password, AdminCookieRequest request) { return setCookieInMailbox(request); }

    @PostMapping("/room/stream")
    public ResponseEntity<?> setStream(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return playbackResponse(coordinator.httpAction(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"stream",body, () -> {
            boolean enabled = body.path("enabled").asBoolean();
            if (enabled) coordinator.requireMusic();
            liveStreamService.setEnabled(enabled); coordinator.changed();
        }));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @PostMapping("/config/update")
    public ResponseEntity<?> updateConfigHttp(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        if (mapper.convertValue(body,AdminConfigUpdateRequest.class).hasGlobalSystemConfig())
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message","系统参数仅允许 ROOT 在 ROOM MANAGER 中全局设置"));
        return playbackResponse(coordinator.httpAction(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"config",body, () -> {
            ResponseEntity<?> result = updateConfig(null,mapper.convertValue(body,AdminConfigUpdateRequest.class));
            if (!result.getStatusCode().is2xxSuccessful()) throw new IllegalArgumentException(String.valueOf(result.getBody()));
            coordinator.changed();
        }));
    }

    public ResponseEntity<?> updateConfig(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminConfigUpdateRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (request.hasGlobalSystemConfig()) return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "系统参数仅允许 ROOT 在 ROOM MANAGER 中全局设置"));
        String invalid = validateConfig(request);
        if (invalid != null) return ResponseEntity.badRequest().body(Map.of("message", invalid));
        try {
            roomConfigFileService.persist(request);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "配置文件保存失败"));
        }
        musicPlayerService.updateConfig(request);
        return ResponseEntity.ok(Map.of("message", "系统配置已刷新"));
    }

    private String validateConfig(AdminConfigUpdateRequest r) {
        if (!inRange(r.voteSkipWaitTime(), 0, 3600)) return "投票等待时间超出范围";
        if (r.voteSkipThreshold() != null && (!Double.isFinite(r.voteSkipThreshold()) || r.voteSkipThreshold() < 0.1 || r.voteSkipThreshold() > 1)) return "投票阈值超出范围";
        if (r.neteaseQuality() != null && !Set.of("standard", "higher", "exhigh", "lossless", "hires", "jyeffect").contains(r.neteaseQuality())) return "不支持该解析音质";
        if (r.seekPolicy() != null && !Set.of("DISABLED", "OWNER_AND_ENQUEUER", "ALL").contains(r.seekPolicy())) return "不支持该进度跳转权限";
        return null;
    }

    private boolean inRange(Number value, long min, long max) {
        return value == null || (value.longValue() >= min && value.longValue() <= max);
    }

    @PostMapping("/private-dj")
    public ResponseEntity<?> updatePrivateDjHttp(jakarta.servlet.http.HttpServletRequest http, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return playbackResponse(coordinator.httpAction(org.thornex.musicparty.room.RoomHttpFilter.managementToken(http),http.getHeader("playback-protocol"),"private-dj",body, () -> {
            AdminPrivateDjUpdateRequest request = mapper.convertValue(body,AdminPrivateDjUpdateRequest.class);
            if (coordinator.isVideo() && (!"OFF".equals(request.mode()) || request.fillBlankEnabled() != null || request.joinQueueEnabled() != null || request.custodyEnabled() != null))
                throw new IllegalArgumentException("视频模式仅允许关闭私人电台/DJ");
            ResponseEntity<?> result = updatePrivateDj(null,request);
            if (!result.getStatusCode().is2xxSuccessful()) throw new IllegalArgumentException(String.valueOf(result.getBody()));
            coordinator.changed();
        }));
    }

    public ResponseEntity<?> updatePrivateDj(@RequestHeader(value = "X-Admin-Password", required = false) String password,
                                             @RequestBody AdminPrivateDjUpdateRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        AppProperties.PrivateDjConfig c = appProperties.getPrivateDj();

        // 模式即开关：OFF=关闭 / FM=私人FM / DJ=私人DJ
        if (request.mode() != null) {
            if (!Set.of("OFF", "FM", "DJ").contains(request.mode())) {
                return ResponseEntity.badRequest().body(Map.of("message", "模式仅支持 关闭/私人FM/私人DJ"));
            }
            // 切到 FM/DJ 视为开启私人电台，需先配置网易云 Cookie；切到 OFF 关闭则无需校验
            if (!"OFF".equals(request.mode()) && !neteaseMusicApiService.isCookieConfigured()) {
                return ResponseEntity.badRequest().body(Map.of("message", "需先配置网易云 Cookie 才能开启私人电台"));
            }
            c.setMode(request.mode());
        }
        if (request.fillBlankEnabled() != null) c.setFillBlankEnabled(request.fillBlankEnabled());
        if (request.joinQueueEnabled() != null) c.setJoinQueueEnabled(request.joinQueueEnabled());
        if (request.custodyEnabled() != null) c.setCustodyEnabled(request.custodyEnabled());

        privateDjService.invalidate();
        musicPlayerService.broadcastFullPlayerState();
        return ResponseEntity.ok(Map.of("message", "私人电台/私人DJ 配置已更新"));
    }

    // Keep compatibility for now or remove if sure
    @Deprecated
    @PostMapping("/command")
    public ResponseEntity<?> handleAdminCommand(@RequestBody AdminCommandRequest request) {
        if (!isValid(request.password())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
        }
        // ... (original implementation if still needed, but I'll remove it since we are refactoring)
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "This endpoint is deprecated. Use structured endpoints."));
    }
}
