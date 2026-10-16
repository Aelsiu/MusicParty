package org.thornex.musicparty.controller;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;

import java.util.List;

@Controller
@org.thornex.musicparty.room.RoomScoped
public class MusicSocketController {

    private final org.thornex.musicparty.playback.RoomPlaybackCoordinator coordinator;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatService chatService;
    private final org.thornex.musicparty.room.RoomAccessService roomAccess;

    public MusicSocketController(org.thornex.musicparty.playback.RoomPlaybackCoordinator coordinator, UserService userService, SimpMessagingTemplate messagingTemplate, ChatService chatService, org.thornex.musicparty.room.RoomAccessService roomAccess) {
        this.coordinator = coordinator;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
        this.chatService = chatService;
        this.roomAccess = roomAccess;
    }

    @MessageMapping("/player/resync")
    public void requestResync(@Header("simpSessionId") String session) { coordinator.resync(session); }

    @MessageMapping("/player/clock")
    public void clock(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.clock(session,request); }

    @MessageMapping("/control/media-mode")
    public void mediaMode(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"mode",request); }

    @MessageMapping("/enqueue")
    public void enqueue(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"enqueue",request); }

    @MessageMapping("/enqueue/playlist")
    public void enqueuePlaylist(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"playlist",request); }

    @MessageMapping("/control/next")
    public void nextSong(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"next",request); }

    @MessageMapping("/control/toggle-shuffle")
    public void cyclePlayMode(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"shuffle",request); }

    @MessageMapping("/control/seek")
    public void seek(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"seek",request); }

    @MessageMapping("/control/toggle-pause")
    public void togglePause(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"pause",request); }

    @MessageMapping("/queue/top")
    public void topSong(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"top",request); }

    @MessageMapping("/queue/cancel-top")
    public void cancelTop(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.cancelTop(session,request); }

    @MessageMapping("/queue/remove")
    public void removeSong(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"remove",request); }

    @MessageMapping("/control/like")
    public void likeSong(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"like",request); }

    @MessageMapping("/video/source/refresh")
    public void refreshVideo(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"refresh",request); }

    @MessageMapping("/video/watch/status")
    public void watchVideo(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.watchStatus(session,request); }

    @MessageMapping("/video/control/rate")
    public void videoRate(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"rate",request); }

    @MessageMapping("/video/control/page")
    public void videoPage(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"page",request); }

    @MessageMapping("/video/control/recover")
    public void recoverVideo(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.command(session,"recover",request); }
    @MessageMapping("/video/import/favorites")
    public void importFavorites(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.favoriteImport(session,request); }
    @MessageMapping("/video/import/parts")
    public void importParts(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.partsImport(session,request); }
    @MessageMapping("/video/import/xifan")
    public void importXifan(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.xifanImport(session,request); }
    @MessageMapping("/video/import/episodes")
    public void importEpisodes(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.episodesImport(session,request); }
    @MessageMapping("/video/import/status")
    public void importStatus(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.importStatus(session,request); }
    @MessageMapping("/video/interaction/resync")
    public void interactionResync(com.fasterxml.jackson.databind.JsonNode request, @Header("simpSessionId") String session) { coordinator.interactionResync(session,request); }

    @MessageMapping("/user/rename")
    public void rename(RenameRequest request, @Header("simpSessionId") String sessionId) {
        coordinator.internal(() -> {
        if (userService.renameUser(sessionId, request.newName())) {
            coordinator.changed();
            messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/users/online"),userService.getOnlineUserSummaries());
            // PUSH updated user info to the user
            userService.getUser(sessionId).ifPresent(user -> {
                UserSummary summary = new UserSummary(user.getToken(), sessionId, user.getName(), user.isGuest());
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/me", summary, createSessionHeaders(sessionId));
            });
        } else {
            // RENAME_FAILED
            userService.getUser(sessionId).ifPresent(user -> {
                PlayerEvent errorEvent = new PlayerEvent("ERROR", "RENAME_FAILED", user.getToken(), "该名称已被占用或包含非法字符，请更换", null);
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/events", errorEvent, createSessionHeaders(sessionId));
            });
        }
        });
    }

    @SubscribeMapping("/topic/player/state")
    public PlayerState getInitialPlayerState() {
        return coordinator.read();
    }

    @SubscribeMapping("/topic/users/online")
    public List<UserSummary> getInitialOnlineUsers() {
        return userService.getOnlineUserSummaries();
    }

    @SubscribeMapping("/user/me")
    public UserSummary getMyUserInfo(@Header("simpSessionId") String sessionId) {
        return coordinator.readMember(sessionId, () -> userService.getUser(sessionId)
                .map(u -> new UserSummary(u.getToken(), sessionId, u.getName(), u.isGuest()))
                .orElse(new UserSummary(sessionId, sessionId, "Unknown", true)));
    }

    private boolean isGuest(String sessionId) {
        return userService.getUser(sessionId).map(User::isGuest).orElse(true);
    }

    @SubscribeMapping("/user/profile")
    public java.util.Map<String,Object> getSharedProfile(@Header("simpSessionId") String sessionId) {
        return coordinator.readMember(sessionId, () -> userService.getUser(sessionId).map(user -> java.util.Map.<String,Object>of("name",user.getName())).orElse(java.util.Map.of()));
    }

    // 聊天消息处理
    @MessageMapping("/chat")
    public void handleChat(com.fasterxml.jackson.databind.JsonNode body, @Header("simpSessionId") String sessionId) {
        ChatRequest request = new ChatRequest(body.path("content").asText(""));
        if (request.content().trim().isEmpty() || !chatService.isMessageLengthValid(request.content())) return;
        String content = request.content().trim();
        if (isGuest(sessionId) && !content.matches("(?is)^//help(\\s.*)?$")) return;
        boolean playbackCommand = content.matches("(?i)^//(clear|jump|stream)(\\s.*)?$") && !content.matches("(?i)^//clear\\s+chat\\s*$");
        if (playbackCommand) {
            coordinator.chat(sessionId,body,() -> chatService.processIncomingMessage(sessionId,content));
            return;
        }
        if (chatService.processIncomingMessage(sessionId,content)) return;

        userService.getUser(sessionId).ifPresent(user -> {
            // 4. Rate Limit Check
            if (!chatService.canUserSendMessage(user.getToken())) return;

            ChatMessage message = new ChatMessage(
                    java.util.UUID.randomUUID().toString(),
                    user.getToken(),
                    user.getName(), 
                    request.content().trim(),
                    System.currentTimeMillis(),
                    MessageType.CHAT
            );

            // 保存到历史
            chatService.addMessage(message);

            messagingTemplate.convertAndSend(org.thornex.musicparty.room.RoomContext.topic("/chat"), message);
        });
    }

    // 订阅时获取历史记录
    @SubscribeMapping("/chat/history")
    public List<ChatMessage> getChatHistory() {
        return chatService.getHistory(0, 50);
    }

    // 处理分页获取历史记录的请求
    @MessageMapping("/chat/history/fetch")
    public void fetchChatHistory(@Payload ChatHistoryFetchRequest request, @Header("simpSessionId") String sessionId) {
        List<ChatMessage> history = chatService.getHistory(request.offset(), request.limit());
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/chat/history",
                history,
                createSessionHeaders(sessionId)
        );
    }

    private MessageHeaders createSessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor.getMessageHeaders();
    }
}
