// File Path: src\main\java\org\thornex\musicparty\controller\AuthController.java

package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // null 表示未创建；已创建的房间使用四位数字密码。
    private final AtomicReference<String> roomPassword = new AtomicReference<>(null);
    private final AtomicReference<String> roomName = new AtomicReference<>(null);
    private final AppProperties.AuthConfig authConfig;

    // IP限流记录
    private final ConcurrentHashMap<String, FailedAttempt> ipAttempts = new ConcurrentHashMap<>();

    private static class FailedAttempt {
        int count;
        Instant firstAttemptTime;
        Instant blockedUntil;

        FailedAttempt() {
            this.count = 1;
            this.firstAttemptTime = Instant.now();
        }
    }

    public AuthController(AppProperties appProperties) {
        this.authConfig = appProperties.getAuth();
    }

    public void resetRoomPassword() {
        roomPassword.set(null); // 恢复到未初始化状态
        roomName.set(null);
    }

    // 管理员修改密码；入口会先验证格式。
    public void forceSetPassword(String newPassword) {
        roomPassword.set(newPassword);
    }

    public void restoreRoom(String name, String password) {
        roomName.set(name);
        roomPassword.set(password);
    }

    public String getRoomName() {
        return roomName.get();
    }

    /**
     * 检查房间状态
     * isSetup: 是否已经完成了初始化设置
     * roomName: 创建时设置的房间名
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        String current = roomPassword.get();
        boolean isSetup = isValidPin(current) && roomName.get() != null;

        return ResponseEntity.ok(Map.of(
                "isSetup", isSetup,
                "hasProtection", isSetup,
                "roomName", isSetup ? roomName.get() : ""
        ));
    }

    /**
     * 创建房间 (只有当前未初始化时才允许)
     */
    @PostMapping("/setup")
    public synchronized ResponseEntity<?> setupPassword(@RequestBody Map<String, String> body) {
        // 如果已经设置过密码，禁止再次设置（防止并发重置）
        if (isValidPin(roomPassword.get()) && roomName.get() != null) {
            return ResponseEntity.status(403).body("Password already set");
        }

        String newPassword = body.get("password");
        String requestedName = body.get("roomName");
        String newRoomName = requestedName == null ? "" : requestedName.trim();
        if (!isValidPin(newPassword) || newRoomName.isEmpty() || newRoomName.length() > 40) {
            return ResponseEntity.badRequest().body(Map.of("message", "房间名须为1–40字，密码须为4位数字"));
        }

        roomName.set(newRoomName);
        roomPassword.set(newPassword);
        return ResponseEntity.ok(Map.of("message", "Password set successfully"));
    }

    /**
     * 验证密码
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyPassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String clientIp = getClientIp(request);

        // 检查限流
        if (authConfig.isRateLimitEnabled() && isBlocked(clientIp)) {
            return ResponseEntity.status(429).body(Map.of("valid", false, "message", "尝试次数过多，请稍后再试"));
        }

        String inputPassword = body.getOrDefault("password", "");
        String currentPassword = roomPassword.get();

        if (!isValidPin(currentPassword) || roomName.get() == null) {
            return ResponseEntity.status(409).body(Map.of("valid", false, "message", "房间尚未创建"));
        }

        // 仅允许四位数字房间密码进入。
        if (isValidPin(inputPassword) && currentPassword.equals(inputPassword)) {
            clearAttempts(clientIp);
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            recordFailure(clientIp);
            return ResponseEntity.status(401).body(Map.of("valid", false));
        }
    }

    private void recordFailure(String ip) {
        if (!authConfig.isRateLimitEnabled()) return;

        ipAttempts.compute(ip, (k, attempt) -> {
            Instant now = Instant.now();
            if (attempt == null) {
                return new FailedAttempt();
            }

            // 检查窗口是否已过，如果过了，重置
            if (now.isAfter(attempt.firstAttemptTime.plusSeconds(authConfig.getWindowSeconds()))) {
                return new FailedAttempt();
            }

            // 增加计数
            attempt.count++;
            
            // 检查是否达到封锁阈值
            if (attempt.count >= authConfig.getMaxAttempts()) {
                attempt.blockedUntil = now.plusSeconds(authConfig.getBlockDurationSeconds());
            }
            return attempt;
        });
    }

    private void clearAttempts(String ip) {
        ipAttempts.remove(ip);
    }

    private boolean isBlocked(String ip) {
        FailedAttempt attempt = ipAttempts.get(ip);
        if (attempt == null) return false;
        
        if (attempt.blockedUntil != null) {
            if (Instant.now().isBefore(attempt.blockedUntil)) {
                return true;
            } else {
                // 封锁时间已过，移除记录
                ipAttempts.remove(ip);
                return false;
            }
        }
        return false;
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

    public String getRawPassword() {
        return roomPassword.get();
    }

    public static boolean isValidPin(String password) {
        return password != null && password.matches("[0-9]{4}");
    }
}
