package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.Priority;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.room.RoomContext;
import org.thornex.musicparty.room.RoomRepository;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueuePersistenceProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AppProperties props = new AppProperties();
    private final MusicQueueManager queue = new MusicQueueManager(props);
    private final MusicPlayerService music = mock(MusicPlayerService.class);
    private final VideoPlayerService video = mock(VideoPlayerService.class);
    private final RoomRepository repository = mock(RoomRepository.class);
    private final RoomConfigFileService fileConfig = mock(RoomConfigFileService.class);
    private QueuePersistenceService persistence;

    @BeforeEach void setup() {
        props.getPlayer().setVoteSkipEnabled(false);
        props.getPlayer().setVoteSkipThreshold(.4);
        props.getPlayer().setVoteSkipWaitTime(9);
        props.getQueue().setMaxSize(21);
        props.getQueue().setHistorySize(22);
        props.getQueue().setMaxUserSongs(7);
        props.getPlayer().setVideoMaxDurationMinutes(123);
        when(repository.exists("room")).thenReturn(true);
        when(music.getPersistenceQueueSnapshot()).thenAnswer(invocation -> queue.getPersistenceQueueSnapshot());
        when(music.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings("SHUFFLE", true, false,
                true, .9, 30, true, false, false));
        when(video.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings("SEQUENTIAL", false, true,
                false, .2, 99, false, true, false));
        persistence = new QueuePersistenceService(queue, mock(ChatService.class), props, mapper, music,
                mock(AuthController.class), mock(LiveStreamService.class), fileConfig);
        ReflectionTestUtils.setField(persistence, "roomRepository", repository);
        ReflectionTestUtils.setField(persistence, "videoPlayer", video);
    }

    private String snapshot(int version) {
        return "{\"snapshotVersion\":" + version + ",\"queue\":[],\"history\":[],\"chatHistory\":[],\"videoQueue\":[],\"videoHistory\":[],\"unknownFutureField\":true,"
                + "\"settings\":{\"mediaMode\":\"VIDEO\",\"player\":{\"playMode\":\"SHUFFLE\",\"voteSkipEnabled\":true,\"voteSkipThreshold\":0.9,\"voteSkipWaitTime\":30},"
                + "\"videoPlayer\":{\"playMode\":\"SEQUENTIAL\",\"voteSkipEnabled\":true,\"voteSkipThreshold\":0.8,\"voteSkipWaitTime\":55},"
                + "\"systemConfig\":{\"maxQueueSize\":999,\"maxHistorySize\":999,\"maxUserSongs\":999,\"bilibiliMaxDurationMinutes\":999}}}";
    }
    private void restore(int version, String config) {
        when(repository.payload("room", "payload")).thenReturn(snapshot(version));
        when(repository.payload("room", "config")).thenReturn(config);
        try (var ignored = RoomContext.enter("room")) { persistence.ensureLoaded(); }
    }

    @Test void v2RestoresBothModesWithoutOverwritingSharedVoteSettingsOrGlobalLimits() {
        restore(2, "{\"player\":{\"voteSkipEnabled\":false,\"voteSkipThreshold\":0.4,\"voteSkipWaitTime\":9}}");
        assertFalse(props.getPlayer().isVoteSkipEnabled());
        assertEquals(.4, props.getPlayer().getVoteSkipThreshold());
        assertEquals(9, props.getPlayer().getVoteSkipWaitTime());
        assertEquals(21, props.getQueue().getMaxSize());
        assertEquals(22, props.getQueue().getHistorySize());
        assertEquals(7, props.getQueue().getMaxUserSongs());
        assertEquals(123, props.getPlayer().getVideoMaxDurationMinutes());
        assertEquals("VIDEO", persistence.restoredMode());
        verify(music).applyPlayerSettings(argThat(settings -> "SHUFFLE".equals(settings.playMode()) && settings.voteSkipEnabled() == null
                && settings.voteSkipThreshold() == null && settings.voteSkipWaitTime() == null));
        verify(video).applyPlayerSettings(argThat(settings -> "SEQUENTIAL".equals(settings.playMode()) && settings.voteSkipEnabled() == null
                && settings.voteSkipThreshold() == null && settings.voteSkipWaitTime() == null));
        verify(music).refreshSharedPlayerConfig();
        verify(music).pauseForEmptyRoom();
        verify(video).restore(List.of(), List.of());
    }

    @Test void legacyMigrationFillsOnlyAbsentFieldsInRoomConfig() {
        restore(0, "{\"player\":{\"voteSkipThreshold\":0.4}}");
        assertTrue(props.getPlayer().isVoteSkipEnabled());
        assertEquals(.4, props.getPlayer().getVoteSkipThreshold());
        assertEquals(30, props.getPlayer().getVoteSkipWaitTime());
    }

    @Test void legacySnapshotNeverOverridesAuthoritativeRoomConfigOrExternalConfig() {
        restore(0, "{\"player\":{\"voteSkipEnabled\":false,\"voteSkipThreshold\":0.4,\"voteSkipWaitTime\":9}}");
        assertFalse(props.getPlayer().isVoteSkipEnabled());
        assertEquals(.4, props.getPlayer().getVoteSkipThreshold());
        assertEquals(9, props.getPlayer().getVoteSkipWaitTime());
    }

    @Test void externalConfigPreventsLegacyMigrationEvenWhenRoomConfigFieldsAreAbsent() {
        when(fileConfig.isConfigured()).thenReturn(true);
        restore(0, null);
        assertFalse(props.getPlayer().isVoteSkipEnabled());
        assertEquals(.4, props.getPlayer().getVoteSkipThreshold());
        assertEquals(9, props.getPlayer().getVoteSkipWaitTime());
    }

    @Test void strictSavePersistsBothPendingQueuesOnceAndKeepsSharedSettingsOnlyInConfig() throws Exception {
        UserSummary user = new UserSummary("u", "s", "用户", false);
        MusicQueueItem musicItem = queue.add(new Music("song", "音乐", List.of(), 1000, "netease", ""), user, QueueItemStatus.READY);
        queue.retainPending(musicItem.withPriority(Priority.GLOBAL_TOP));
        MusicQueueItem videoItem = new MusicQueueItem("video-queue-id", new Music("BV123:p1", "视频", List.of(), 1000,
                "bilibili-video", ""), user, QueueItemStatus.PENDING, Priority.USER_TOP);
        when(video.getPersistenceQueueSnapshot()).thenReturn(List.of(videoItem));
        try (var ignored = RoomContext.enter("room")) { persistence.saveStrict("VIDEO"); }
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class), config = ArgumentCaptor.forClass(String.class);
        verify(repository).save(eq("room"), payload.capture(), config.capture());
        JsonNode saved = mapper.readTree(payload.getValue()), savedConfig = mapper.readTree(config.getValue());
        assertEquals(2, saved.path("snapshotVersion").asInt());
        assertEquals("VIDEO", saved.path("settings").path("mediaMode").asText());
        assertEquals(1, saved.path("queue").size());
        assertEquals(musicItem.queueId(), saved.path("queue").get(0).path("queueId").asText());
        assertEquals("GLOBAL_TOP", saved.path("queue").get(0).path("priority").asText());
        assertEquals(1, saved.path("videoQueue").size());
        assertEquals("video-queue-id", saved.path("videoQueue").get(0).path("queueId").asText());
        for (String mode : List.of("player", "videoPlayer")) {
            assertFalse(saved.path("settings").path(mode).hasNonNull("voteSkipEnabled"));
            assertFalse(saved.path("settings").path(mode).hasNonNull("voteSkipThreshold"));
            assertFalse(saved.path("settings").path(mode).hasNonNull("voteSkipWaitTime"));
        }
        assertFalse(savedConfig.path("queue").has("maxSize"));
        assertFalse(savedConfig.path("player").has("videoMaxDurationMinutes"));
        assertFalse(savedConfig.path("bilibili").has("maxDurationMinutes"));
        assertFalse(savedConfig.path("player").path("voteSkipEnabled").asBoolean());
        assertEquals(.4, savedConfig.path("player").path("voteSkipThreshold").asDouble());
        assertFalse(saved.has("nowPlaying"), "the running media scene is never persisted");
    }

    @Test void roomConstructionDoesNotRestoreInsideScopeMonitorAndRestorationRunsOnlyOnce() {
        when(repository.payload("room", "payload")).thenReturn(snapshot(2));
        try (var ignored = RoomContext.enter("room")) {
            persistence.init();
            verify(repository, never()).payload(anyString(), anyString());
            persistence.ensureLoaded();
            persistence.ensureLoaded();
        }
        verify(repository, times(1)).payload("room", "payload");
    }

    @Test void failedStrictSaveDoesNotAdvanceSavedModeAndDeletedRoomCannotBeResaved() {
        doThrow(new IllegalStateException("SQLite write failed")).when(repository).save(anyString(), anyString(), anyString());
        try (var ignored = RoomContext.enter("room")) {
            assertThrows(IllegalStateException.class, () -> persistence.saveStrict("VIDEO"));
            assertEquals("MUSIC", persistence.restoredMode());
            when(repository.exists("room")).thenReturn(false);
            assertThrows(java.io.IOException.class, () -> persistence.saveStrict("VIDEO"));
        }
        verify(repository, times(1)).save(anyString(), anyString(), anyString());
    }
}
