package org.thornex.musicparty.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.VideoDescriptor;
import org.thornex.musicparty.service.api.BilibiliVideoApiService;
import reactor.core.publisher.Mono;

/** Trusted metadata only. The membership filter runs before this room-scoped controller. */
@RestController
@RequestMapping("/api/video")
@org.thornex.musicparty.room.RoomScoped
public class VideoApiController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.thornex.musicparty.playback.RoomPlaybackCoordinator coordinator;
    private final BilibiliVideoApiService api;
    private final AppProperties properties;
    public VideoApiController(BilibiliVideoApiService api, AppProperties properties) {
        this.api = api; this.properties = properties;
    }
    @GetMapping("/metadata/{bvid}")
    public Mono<VideoDescriptor> metadata(@PathVariable String bvid,
            @RequestHeader(value="playback-protocol",required=false) String protocol) {
        if (!"3".equals(protocol)) throw new ResponseStatusException(HttpStatus.CONFLICT,"CLIENT_UPGRADE_REQUIRED");
        if (!properties.getBilibili().isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,"PLATFORM_NOT_AVAILABLE");
        return api.getVideoMetadata(bvid);
    }
    private void protocol(String value) {
        if (!"3".equals(value)) throw new ResponseStatusException(HttpStatus.CONFLICT,"CLIENT_UPGRADE_REQUIRED");
    }
    private com.fasterxml.jackson.databind.node.ObjectNode context(String epoch,String mode,long modeEpoch,String requestId) {
        var value=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        value.put("expectedPlaybackEpoch",epoch);value.put("expectedMode",mode);value.put("expectedModeEpoch",modeEpoch);value.put("requestId",requestId);return value;
    }
    @GetMapping("/history")
    public java.util.Map<String,Object> history(@RequestHeader(value="playback-protocol",required=false) String version,
            @RequestParam String expectedPlaybackEpoch,@RequestParam String expectedMode,@RequestParam long expectedModeEpoch,
            @RequestParam String requestId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) Long historyRevision) {
        protocol(version);return coordinator.history(context(expectedPlaybackEpoch,expectedMode,expectedModeEpoch,requestId),page,limit,historyRevision);
    }
    @PostMapping("/favorites/preview")
    public Mono<java.util.Map<String,Object>> preview(@RequestHeader(value="playback-protocol",required=false) String version,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body,jakarta.servlet.http.HttpServletRequest request) {
        protocol(version);
        return coordinator.favoritePreview((String)request.getAttribute(org.thornex.musicparty.config.VideoWatcherIdentity.ATTRIBUTE),
                body,body.path("link").asText(null),null,1);
    }
    @GetMapping("/favorites/previews/{previewId}")
    public Mono<java.util.Map<String,Object>> previewPage(@RequestHeader(value="playback-protocol",required=false) String version,
            @PathVariable String previewId,@RequestParam int page,@RequestParam String expectedPlaybackEpoch,
            @RequestParam String expectedMode,@RequestParam long expectedModeEpoch,@RequestParam String requestId,
            jakarta.servlet.http.HttpServletRequest request) {
        protocol(version);return coordinator.favoritePreview((String)request.getAttribute(org.thornex.musicparty.config.VideoWatcherIdentity.ATTRIBUTE),
                context(expectedPlaybackEpoch,expectedMode,expectedModeEpoch,requestId),null,previewId,page);
    }
    @ExceptionHandler({org.thornex.musicparty.dto.VideoFavorites.Failure.class,java.util.concurrent.CompletionException.class,ResponseStatusException.class})
    public org.springframework.http.ResponseEntity<java.util.Map<String,Object>> favoriteError(Exception error) {
        Throwable cause=error;while(cause.getCause()!=null && (cause instanceof java.util.concurrent.CompletionException)) cause=cause.getCause();
        String code=cause instanceof org.thornex.musicparty.dto.VideoFavorites.Failure failure?failure.getCode()
                : cause instanceof ResponseStatusException status?status.getReason():"TEMPORARY_FAILURE";
        if(code==null) code="TEMPORARY_FAILURE";
        var failure=org.thornex.musicparty.dto.VideoFavorites.failure(code);
        HttpStatus status=switch(code) {
            case "INVALID_FAVORITE_LINK","INVALID_SELECTION" -> HttpStatus.BAD_REQUEST;
            case "NAMED_MEMBER_REQUIRED","CONTENT_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "IMPORT_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;
            case "TEMPORARY_FAILURE","ROOM_BUSY" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return org.springframework.http.ResponseEntity.status(status).body(java.util.Map.of("ok",false,"code",code,"message",failure.getMessage(),"retryable",failure.isRetryable()));
    }
}
