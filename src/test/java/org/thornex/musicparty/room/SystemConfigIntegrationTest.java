package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.*;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SystemConfigIntegrationTest {
    private static final Path DIRECTORY = Path.of("target", "system-config-" + UUID.randomUUID());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.rooms.root-key", () -> "GlobalRoot9");
        r.add("app.rooms.database", () -> DIRECTORY.resolve("rooms.sqlite").toString());
        r.add("app.rooms.license-file", () -> DIRECTORY.resolve("licenses.json").toString());
        r.add("app.music-api.ffmpeg-path", () -> "unavailable-test-ffmpeg");
        r.add("app.music-api.queue.max-size", () -> 37);
    }
    @Autowired RoomRepository repository;
    @Autowired RoomAccessService access;
    @Autowired RoomScope scope;
    @Autowired ApplicationContext context;
    @Autowired SystemConfigService systemConfig;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc http;
    private final List<String> roomIds = new ArrayList<>();
    private SystemConfigSnapshot original;
    private String rootToken;

    @BeforeEach void before() {
        original = systemConfig.snapshot();
        rootToken = access.login("GlobalRoot9", "system-config-" + UUID.randomUUID()).token();
    }
    @AfterEach void after() {
        for (String id : roomIds) {
            if (repository.exists(id)) repository.deleteRoom(id);
            scope.destroy(id);
        }
        systemConfig.update(request(original));
    }
    private RoomRepository.Room room(String owner) {
        var room = repository.create(owner, "全局参数🎵", UUID.randomUUID().toString());
        roomIds.add(room.id());
        return room;
    }
    private AdminConfigUpdateRequest request(SystemConfigSnapshot snapshot) {
        return new AdminConfigUpdateRequest(snapshot.maxQueueSize(), snapshot.maxHistorySize(), snapshot.maxUserSongs(),
                snapshot.maxPlaylistImportSize(), snapshot.maxChatHistorySize(), snapshot.minChatIntervalMs(),
                snapshot.maxChatMessageLength(), null, null, null, snapshot.bilibiliMaxDurationMinutes(),
                null, null, null, null);
    }

    @Test void onlyRootCanReadAndUpdateGlobalSettingsAndRoomEndpointsRejectAllEightFields() throws Exception {
        var license = repository.addLicense("Owner" + UUID.randomUUID().toString().substring(0, 8));
        String ownerToken = access.login(license.key(), "owner-test").token();
        var room = room(license.id());
        String guestToken = access.join(room.pairingCode(), "guest-test").token();
        http.perform(get("/api/rooms/system-config").header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxQueueSize").value(37));
        for (String token : List.of(ownerToken, guestToken, "invalid")) {
            http.perform(get("/api/rooms/system-config").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            http.perform(post("/api/rooms/system-config").header("Authorization", "Bearer " + token)
                            .contentType("application/json").content("{\"maxSize\":7}"))
                    .andExpect(status().isForbidden());
        }
        http.perform(get("/api/rooms/system-config")).andExpect(status().isForbidden());
        for (String field : List.of("maxSize", "historySize", "maxUserSongs", "maxPlaylistImportSize",
                "maxChatHistorySize", "minChatIntervalMs", "maxChatMessageLength", "bilibiliMaxDurationMinutes")) {
            for (String token : List.of(rootToken, ownerToken)) {
                http.perform(post("/api/admin/config/update").header("Authorization", "Bearer " + token)
                                .header("X-Room-ID", room.id()).contentType("application/json")
                                .content("{\"" + field + "\":1,\"neteaseQuality\":\"lossless\"}"))
                        .andExpect(status().isForbidden());
            }
        }
        try (var ignored = RoomContext.enter(room.id())) {
            assertEquals(original, SystemConfigSnapshot.from(context.getBean(AppProperties.class)));
            assertEquals("exhigh", context.getBean(AppProperties.class).getNetease().getQuality());
        }
        http.perform(post("/api/admin/config/update").header("Authorization", "Bearer " + ownerToken)
                        .header("X-Room-ID", room.id()).contentType("application/json").content("{\"neteaseQuality\":\"lossless\"}"))
                .andExpect(status().isOk());
    }

    @Test void updateIsHotForAllActiveAndFutureRoomsAndFullyTrimsHistoriesWithoutDeletingQueuedSongs() throws Exception {
        var first = room("ROOT");
        var second = room("ROOT");
        List<AppProperties> bound = new ArrayList<>();
        for (var room : List.of(first, second)) try (var ignored = RoomContext.enter(room.id())) {
            context.getBean(QueuePersistenceService.class).ensureLoaded();
            bound.add((AppProperties) ((org.springframework.aop.scope.ScopedObject) context.getBean(AppProperties.class)).getTargetObject());
            MusicQueueManager queue = context.getBean(MusicQueueManager.class);
            ChatService chat = context.getBean(ChatService.class);
            for (int i = 0; i < 8; i++) {
                Music music = music(i);
                queue.add(music, new UserSummary("user", "session", "测试", false), QueueItemStatus.READY);
                queue.addToHistory(music);
                chat.addMessage(new ChatMessage("chat" + i, "user", "测试", "内容", i, MessageType.CHAT));
            }
        }
        SystemConfigSnapshot next = new SystemConfigSnapshot(3, 2, 4, 5, 3, 600000, 5, 8);
        http.perform(post("/api/rooms/system-config").header("Authorization", "Bearer " + rootToken)
                        .contentType("application/json").content(mapper.writeValueAsString(request(next))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("全局系统参数已更新"));
        for (AppProperties properties : bound) {
            assertEquals(next, SystemConfigSnapshot.from(properties));
            properties.getQueue().setMaxSize(999); // Old room-local setters cannot override ROOT.
            assertEquals(3, properties.getQueue().getMaxSize());
        }
        for (var room : List.of(first, second)) try (var ignored = RoomContext.enter(room.id())) {
            MusicQueueManager queue = context.getBean(MusicQueueManager.class);
            ChatService chat = context.getBean(ChatService.class);
            assertEquals(8, queue.getQueueSnapshot().size());
            assertNull(queue.add(music(99), new UserSummary("other", "session", "测试", false), QueueItemStatus.READY));
            assertEquals(List.of("song7", "song6"), queue.getHistorySnapshot().stream().map(Music::id).toList());
            assertEquals(List.of("chat5", "chat6", "chat7"), chat.getHistoryFull().stream().map(ChatMessage::id).toList());
            assertFalse(chat.isMessageLengthValid("123456"));
            assertTrue(chat.canUserSendMessage("fresh"));
            assertFalse(chat.canUserSendMessage("fresh"));
            context.getBean(QueuePersistenceService.class).saveNow();
            var savedConfig = mapper.readTree(repository.payload(room.id(), "config"));
            assertFalse(savedConfig.get("queue").has("maxSize"));
            assertFalse(savedConfig.get("player").has("maxPlaylistImportSize"));
            assertFalse(savedConfig.get("chat").has("maxMessageLength"));
            assertFalse(savedConfig.get("bilibili").has("maxDurationMinutes"));
            assertTrue(mapper.readTree(repository.payload(room.id(), "payload")).at("/settings/systemConfig/maxQueueSize").isNull());
        }
        var future = room("ROOT");
        try (var ignored = RoomContext.enter(future.id())) {
            assertEquals(next, SystemConfigSnapshot.from(context.getBean(AppProperties.class)));
        }
    }

    @Test void dormantLegacyRoomSnapshotsCannotOverrideGlobalSettingsAndCentralValuesSurviveServiceReload() throws Exception {
        var dormant = room("ROOT");
        AppProperties legacy = new AppProperties();
        legacy.getQueue().setMaxSize(999);
        legacy.getBilibili().setMaxDurationMinutes(999);
        legacy.getNetease().setQuality("lossless");
        repository.save(dormant.id(), "{\"settings\":{\"systemConfig\":{\"maxQueueSize\":888,\"maxHistorySize\":888,\"maxUserSongs\":888,\"maxPlaylistImportSize\":888,\"maxChatHistorySize\":888,\"minChatIntervalMs\":888,\"maxChatMessageLength\":888,\"bilibiliMaxDurationMinutes\":888,\"neteaseQuality\":\"hires\"}}}", mapper.writeValueAsString(legacy));
        SystemConfigSnapshot next = new SystemConfigSnapshot(12, 0, 13, 14, 0, 16, 17, 18);
        systemConfig.update(request(next));
        try (var ignored = RoomContext.enter(dormant.id())) {
            context.getBean(QueuePersistenceService.class).ensureLoaded();
            assertEquals(next, SystemConfigSnapshot.from(context.getBean(AppProperties.class)));
            assertEquals("hires", context.getBean(AppProperties.class).getNetease().getQuality());
            assertTrue(context.getBean(MusicQueueManager.class).getHistorySnapshot().isEmpty());
            assertTrue(context.getBean(ChatService.class).getHistoryFull().isEmpty());
        }
        SystemConfigService reloaded = new SystemConfigService(new AppProperties(), repository, mapper, scope, context);
        reloaded.initialize();
        assertEquals(next, reloaded.snapshot());
    }

    @Test void invalidBatchesDoNotPersistOrApplyAnyFieldAndRoomFieldsAreRejectedGlobally() throws Exception {
        String persisted = repository.systemConfig();
        for (String body : List.of("{\"maxSize\":8,\"maxChatMessageLength\":0}",
                "{\"minChatIntervalMs\":-1}", "{\"bilibiliMaxDurationMinutes\":1441}",
                "{\"maxSize\":8,\"neteaseQuality\":\"lossless\"}")) {
            http.perform(post("/api/rooms/system-config").header("Authorization", "Bearer " + rootToken)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
            assertEquals(original, systemConfig.snapshot());
            assertEquals(persisted, repository.systemConfig());
        }
    }

    private Music music(int id) { return new Music("song" + id, "歌曲", List.of("歌手"), 10000, "netease", ""); }
}
