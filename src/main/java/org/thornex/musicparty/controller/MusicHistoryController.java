package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.playback.RoomPlaybackCoordinator;
import org.thornex.musicparty.room.*;
import java.util.Map;

@RestController
@RequestMapping("/api/music/history")
@RoomScoped
public class MusicHistoryController {
    private final RoomPlaybackCoordinator playback;
    private final RoomAccessService access;
    public MusicHistoryController(RoomPlaybackCoordinator playback, RoomAccessService access) {
        this.playback = playback; this.access = access;
    }
    @GetMapping
    public Map<String,Object> history(@RequestHeader(value="playback-protocol",required=false) String protocol,
        @RequestParam String expectedPlaybackEpoch, @RequestParam String expectedMode, @RequestParam long expectedModeEpoch,
        @RequestParam String requestId, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int limit,
        @RequestParam(required=false) Long historyRevision, HttpServletRequest request) {
        if (!"3".equals(protocol)) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"CLIENT_UPGRADE_REQUIRED");
        String room = RoomContext.require(), token = request.getHeader("X-Room-Token"), manager = RoomHttpFilter.managementToken(request);
        var context = new ObjectMapper().createObjectNode().put("expectedPlaybackEpoch",expectedPlaybackEpoch)
            .put("expectedMode",expectedMode).put("expectedModeEpoch",expectedModeEpoch).put("requestId",requestId);
        return playback.musicHistory(context,page,limit,historyRevision,() -> access.member(token,manager,room));
    }
    @ExceptionHandler({ResponseStatusException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> failure(Exception error) {
        String code = error instanceof ResponseStatusException rejected ? rejected.getReason() : "INVALID_REQUEST";
        if (error instanceof ResponseStatusException rejected && rejected.getStatusCode().value() == 403) code = "ACCESS_DENIED";
        if (code == null || !java.util.Set.of("INVALID_REQUEST","INVALID_REQUEST_ID","INVALID_PAGE","ACCESS_DENIED",
            "CLIENT_UPGRADE_REQUIRED","STALE_PLAYBACK_CONTEXT","HISTORY_CHANGED","ROOM_BUSY").contains(code)) code = "ROOM_BUSY";
        int status = switch(code) {
            case "INVALID_REQUEST","INVALID_REQUEST_ID","INVALID_PAGE" -> 400;
            case "ACCESS_DENIED" -> 403; case "ROOM_BUSY" -> 503; default -> 409;
        };
        return ResponseEntity.status(status).body(Map.of("ok",false,"code",code,"message",
            "HISTORY_CHANGED".equals(code) ? "历史已更新，请重新读取" : "音频历史暂不可用，请重试"));
    }
}
