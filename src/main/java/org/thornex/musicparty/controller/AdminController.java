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

    @PostMapping("/lock")
    public ResponseEntity<?> setLock(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminLockRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        
        String type = request.type().toUpperCase();
        if ("ALL".equalsIgnoreCase(type)) {
            musicPlayerService.setAllLocks(request.locked());
            return ResponseEntity.ok(Map.of("message", (request.locked() ? "已开启全频道操作锁定" : "已解除全频道操作锁定")));
        } else {
            musicPlayerService.setLock(type, request.locked());
            String desc = switch (type) {
                case "PAUSE" -> "暂停控制";
                case "SKIP" -> "切歌控制";
                case "SHUFFLE" -> "播放模式";
                default -> type;
            };
            return ResponseEntity.ok(Map.of("message", desc + (request.locked() ? "已锁定" : "已解锁")));
        }
    }

    @PostMapping("/player/action")
    public ResponseEntity<?> playerAction(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminPlayerActionRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String action = request.action().toUpperCase();
        String msg = switch (action) {
            case "PAUSE" -> {
                musicPlayerService.togglePause("SYSTEM");
                yield "播放状态已切换";
            }
            case "SKIP" -> {
                musicPlayerService.skipToNext("SYSTEM");
                yield "已强制跳过当前歌曲";
            }
            case "SHUFFLE" -> {
                musicPlayerService.cyclePlayMode("SYSTEM");
                yield "播放模式已切换";
            }
            case "TOGGLE_FAIR_SHUFFLE" -> {
                musicPlayerService.toggleFairShuffle("SYSTEM");
                yield "随机算法已切换";
            }
            case "TOGGLE_ALLOW_OFFLINE" -> {
                musicPlayerService.toggleAllowOfflineShuffle("SYSTEM");
                yield "离线成员过滤规则已更新";
            }
            default -> null;
        };

        if (msg == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(Map.of("message", msg));
    }

    @PostMapping("/room/clear")
    public ResponseEntity<?> clearData(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminClearRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String target = request.target().toUpperCase();
        if ("CHAT".equalsIgnoreCase(target)) {
            chatService.clearHistoryAndNotify();
            return ResponseEntity.ok(Map.of("message", "聊天历史记录已清空"));
        } else if ("OFFLINE".equalsIgnoreCase(target)) {
            int count = musicPlayerService.clearOfflineSongs();
            return ResponseEntity.ok(Map.of("message", "已清理 " + count + " 首离线成员的点播歌曲"));
        } else {
            musicPlayerService.clearQueue();
            return ResponseEntity.ok(Map.of("message", "播放队列已全部重置"));
        }
    }

    @PostMapping("/system/reset")
    public ResponseEntity<?> resetSystem(@RequestHeader(value = "X-Admin-Password", required = false) String password) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        musicPlayerService.resetSystem();
        return ResponseEntity.ok(Map.of("message", "本房间播放与队列已重置"));
    }

    @PostMapping("/config/cookie")
    public ResponseEntity<?> setCookie(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminCookieRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        if ("netease".equalsIgnoreCase(request.platform())) {
            neteaseMusicApiService.updateCookie(request.value());
            // 首次配置 cookie 后即时广播，刷新控制面板总开关门禁（neteaseCookieConfigured 来自 PlayerState）
            musicPlayerService.broadcastFullPlayerState();
            return ResponseEntity.ok(Map.of("message", "网易云音乐凭据已更新"));
        } else if ("bilibili".equalsIgnoreCase(request.platform())) {
            bilibiliMusicApiService.updateCookie(request.value());
            appProperties.getBilibili().setCookie(request.value());
            return ResponseEntity.ok(Map.of("message", "Bilibili Cookie 已更新"));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/room/stream")
    public ResponseEntity<?> setStream(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminStreamRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        liveStreamService.setEnabled(request.enabled());
        musicPlayerService.broadcastFullPlayerState();
        return ResponseEntity.ok(Map.of("message", request.enabled() ? "直播流同步服务已启动" : "直播流同步服务已停止"));
    }

    @PostMapping("/config/update")
    public ResponseEntity<?> updateConfig(@RequestHeader(value = "X-Admin-Password", required = false) String password, @RequestBody AdminConfigUpdateRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
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
        if (!inRange(r.maxSize(), 1, 10000)) return "队列最大歌曲上限超出范围";
        if (!inRange(r.historySize(), 0, 10000)) return "历史记录歌曲上限超出范围";
        if (!inRange(r.maxUserSongs(), 1, 10000)) return "单人歌曲上限超出范围";
        if (!inRange(r.maxPlaylistImportSize(), 1, 10000)) return "歌单导入上限超出范围";
        if (!inRange(r.maxChatHistorySize(), 0, 100000)) return "消息历史条数超出范围";
        if (!inRange(r.minChatIntervalMs(), 0, 600000)) return "发言间隔超出范围";
        if (!inRange(r.maxChatMessageLength(), 1, 10000)) return "消息最大长度超出范围";
        if (!inRange(r.bilibiliMaxDurationMinutes(), 1, 1440)) return "B站时长上限超出范围";
        if (!inRange(r.voteSkipWaitTime(), 0, 3600)) return "投票等待时间超出范围";
        if (!inRange(r.idleKickMinutes(), 1, 60)) return "空闲踢出时间须为1–60分钟";
        if (r.voteSkipThreshold() != null && (!Double.isFinite(r.voteSkipThreshold()) || r.voteSkipThreshold() < 0.1 || r.voteSkipThreshold() > 1)) return "投票阈值超出范围";
        if (r.neteaseQuality() != null && !Set.of("standard", "higher", "exhigh", "lossless", "hires", "jyeffect").contains(r.neteaseQuality())) return "不支持该解析音质";
        return null;
    }

    private boolean inRange(Number value, long min, long max) {
        return value == null || (value.longValue() >= min && value.longValue() <= max);
    }

    @PostMapping("/private-dj")
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
