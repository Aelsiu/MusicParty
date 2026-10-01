package org.thornex.musicparty.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.SettingsSnapshot;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@org.thornex.musicparty.room.RoomScoped
@RequiredArgsConstructor
public class QueuePersistenceService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.thornex.musicparty.room.RoomRepository roomRepository;

    public void ensureLoaded() {}
    public void saveNow() { saveData(); }

    private final MusicQueueManager musicQueueManager;
    private final ChatService chatService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final MusicPlayerService musicPlayerService;
    private final AuthController authController;
    private final LiveStreamService liveStreamService;
    private final RoomConfigFileService roomConfigFileService;

    @PostConstruct
    public void init() {
        loadData();
    }

    @PreDestroy
    public void cleanup() {
        saveData();
    }

    public void scheduledSave() {
        saveData();
    }

    synchronized void saveData() {
        String roomId = org.thornex.musicparty.room.RoomContext.current();
        if (roomRepository != null && roomId != null && !roomRepository.exists(roomId)) return;
        try {
            File file = getPersistenceFile();
            PersistentData data = new PersistentData();
            data.setQueue(musicQueueManager.getQueueSnapshot());
            data.setHistory(musicQueueManager.getHistorySnapshot());
            data.setChatHistory(chatService.getHistoryFull());
            data.setSettings(buildSettingsSnapshot());

            if (roomRepository != null && org.thornex.musicparty.room.RoomContext.current() != null) {
                Object config = appProperties instanceof org.springframework.aop.scope.ScopedObject scoped ? scoped.getTargetObject() : appProperties;
                ObjectNode roomConfig = objectMapper.valueToTree(config);
                ((ObjectNode) roomConfig.get("queue")).remove(List.of("maxSize", "historySize", "maxUserSongs"));
                ((ObjectNode) roomConfig.get("player")).remove(List.of("maxPlaylistImportSize", "pairingIntervalMinutes"));
                ((ObjectNode) roomConfig.get("chat")).remove(List.of("maxHistorySize", "minIntervalMs", "maxMessageLength"));
                ((ObjectNode) roomConfig.get("bilibili")).remove("maxDurationMinutes");
                roomRepository.save(org.thornex.musicparty.room.RoomContext.require(), objectMapper.writeValueAsString(data), objectMapper.writeValueAsString(roomConfig));
            } else objectMapper.writeValue(file, data);
            log.debug("Queue, music history and chat history saved to {}", file.getAbsolutePath());
        } catch (Exception e) {
            if (roomRepository != null && roomId != null && !roomRepository.exists(roomId)) return;
            log.error("Failed to save persistence data", e);
        }
    }

    synchronized void loadData() {
        if (roomRepository != null && org.thornex.musicparty.room.RoomContext.current() != null) {
            try {
                String json = roomRepository.payload(org.thornex.musicparty.room.RoomContext.require(), "payload");
                if (json != null) {
                    PersistentData data = objectMapper.readValue(json, PersistentData.class);
                    musicQueueManager.restore(data.getQueue() != null ? data.getQueue() : Collections.emptyList(), data.getHistory() != null ? data.getHistory() : Collections.emptyList());
                    chatService.restore(data.getChatHistory() != null ? data.getChatHistory() : Collections.emptyList());
                    applySettings(data.getSettings());
                }
                musicPlayerService.pauseForEmptyRoom();
            } catch (Exception e) { throw new IllegalStateException("Room snapshot could not be restored", e); }
            return;
        }
        File file = getPersistenceFile();
        if (!file.exists()) {
            log.info("No persistence file found at {}, starting fresh.", file.getAbsolutePath());
            return;
        }

        try {
            PersistentData data = objectMapper.readValue(file, new TypeReference<PersistentData>() {});
            
            musicQueueManager.restore(
                data.getQueue() != null ? data.getQueue() : Collections.emptyList(),
                data.getHistory() != null ? data.getHistory() : Collections.emptyList()
            );

            chatService.restore(data.getChatHistory() != null ? data.getChatHistory() : Collections.emptyList());

            applySettings(data.getSettings());

            log.info("Restored {} queue items, {} music history items and {} chat messages from {}",
                data.getQueue() != null ? data.getQueue().size() : 0, 
                data.getHistory() != null ? data.getHistory().size() : 0, 
                data.getChatHistory() != null ? data.getChatHistory().size() : 0,
                file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to load persistence data from {}", file.getAbsolutePath(), e);
        }
    }

    private void applySettings(SettingsSnapshot s) {
        if (s == null) {
            log.info("No settings section in persistence file, skipping settings restore.");
            return;
        }

        boolean hasExternalConfig = roomConfigFileService.isConfigured();
        if (s.player() != null) {
            SettingsSnapshot.PlayerSettings player = s.player();
            if (hasExternalConfig) {
                player = new SettingsSnapshot.PlayerSettings(
                        player.playMode(), player.fairShuffle(), player.allowOfflineShuffle(),
                        null, null, null, player.pauseLocked(), player.skipLocked(), player.playModeLocked());
            }
            musicPlayerService.applyPlayerSettings(player);
        }

        if (s.privateDj() != null) {
            AppProperties.PrivateDjConfig c = appProperties.getPrivateDj();
            if (s.privateDj().mode() != null) c.setMode(s.privateDj().mode());
            if (s.privateDj().fillBlankEnabled() != null) c.setFillBlankEnabled(s.privateDj().fillBlankEnabled());
            if (s.privateDj().joinQueueEnabled() != null) c.setJoinQueueEnabled(s.privateDj().joinQueueEnabled());
            if (s.privateDj().custodyEnabled() != null) c.setCustodyEnabled(s.privateDj().custodyEnabled());
        }

        // Docker 的外部配置文件为房间参数的唯一来源，避免旧快照在重启后覆盖它。
        if (s.systemConfig() != null && !hasExternalConfig) {
            SettingsSnapshot.SystemConfigSettings cfg = s.systemConfig();
            // Legacy standalone snapshots are still readable. Room snapshots cannot override global limits.
            if (org.thornex.musicparty.room.RoomContext.current() == null) {
                if (cfg.maxQueueSize() != null) appProperties.getQueue().setMaxSize(cfg.maxQueueSize());
                if (cfg.maxHistorySize() != null) appProperties.getQueue().setHistorySize(cfg.maxHistorySize());
                if (cfg.maxUserSongs() != null) appProperties.getQueue().setMaxUserSongs(cfg.maxUserSongs());
                if (cfg.maxPlaylistImportSize() != null) appProperties.getPlayer().setMaxPlaylistImportSize(cfg.maxPlaylistImportSize());
                if (cfg.maxChatHistorySize() != null) appProperties.getChat().setMaxHistorySize(cfg.maxChatHistorySize());
                if (cfg.minChatIntervalMs() != null) appProperties.getChat().setMinIntervalMs(cfg.minChatIntervalMs());
                if (cfg.bilibiliMaxDurationMinutes() != null) appProperties.getBilibili().setMaxDurationMinutes(cfg.bilibiliMaxDurationMinutes());
                if (cfg.maxChatMessageLength() != null) appProperties.getChat().setMaxMessageLength(cfg.maxChatMessageLength());
            }
            if (cfg.neteaseEnabled() != null) appProperties.getNetease().setEnabled(cfg.neteaseEnabled());
            if (cfg.bilibiliEnabled() != null) appProperties.getBilibili().setEnabled(cfg.bilibiliEnabled());
            if (cfg.neteaseQuality() != null) appProperties.getNetease().setQuality(cfg.neteaseQuality());
            if (cfg.seekPolicy() != null && java.util.Set.of("DISABLED", "OWNER_AND_ENQUEUER", "ALL").contains(cfg.seekPolicy())) appProperties.getPlayer().setSeekPolicy(cfg.seekPolicy());
        }

        if (AuthController.isValidPin(s.roomPassword()) && s.roomName() != null && !s.roomName().isBlank()) {
            authController.restoreRoom(s.roomName(), s.roomPassword());
        } else if (s.roomPassword() != null) {
            log.info("Legacy room password/name requires room creation; queue and chat were retained.");
        }
        if (s.streamEnabled() != null) liveStreamService.setEnabled(s.streamEnabled());

        if (org.thornex.musicparty.room.RoomContext.current() != null) {
            log.info("Restored persisted runtime settings for room {}", org.thornex.musicparty.room.RoomContext.require());
        } else log.info("Restored persisted runtime settings from {}", appProperties.getQueue().getPersistenceFile());
    }

    private SettingsSnapshot buildSettingsSnapshot() {
        boolean roomScoped = org.thornex.musicparty.room.RoomContext.current() != null;
        return new SettingsSnapshot(
                musicPlayerService.getPlayerSettings(),
                authController.getRawPassword(),
                authController.getRoomName(),
                liveStreamService.isEnabled(),
                new SettingsSnapshot.PrivateDjSettings(
                        appProperties.getPrivateDj().getMode(),
                        appProperties.getPrivateDj().isFillBlankEnabled(),
                        appProperties.getPrivateDj().isJoinQueueEnabled(),
                        appProperties.getPrivateDj().isCustodyEnabled()),
                new SettingsSnapshot.SystemConfigSettings(
                        roomScoped ? null : appProperties.getQueue().getMaxSize(),
                        roomScoped ? null : appProperties.getQueue().getHistorySize(),
                        roomScoped ? null : appProperties.getQueue().getMaxUserSongs(),
                        roomScoped ? null : appProperties.getPlayer().getMaxPlaylistImportSize(),
                        roomScoped ? null : appProperties.getChat().getMaxHistorySize(),
                        roomScoped ? null : appProperties.getChat().getMinIntervalMs(),
                        appProperties.getNetease().isEnabled(),
                        appProperties.getBilibili().isEnabled(),
                        roomScoped ? null : appProperties.getBilibili().getMaxDurationMinutes(),
                        roomScoped ? null : appProperties.getChat().getMaxMessageLength(),
                        appProperties.getNetease().getQuality(),
                        appProperties.getPlayer().getSeekPolicy()));
    }

    private File getPersistenceFile() {
        if (roomRepository != null && org.thornex.musicparty.room.RoomContext.current() != null) return new File("data/unused-room-snapshot");
        String path = appProperties.getQueue().getPersistenceFile();
        File file = new File(path);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    @Data
    private static class PersistentData {
        private List<MusicQueueItem> queue;
        private List<Music> history;
        private List<org.thornex.musicparty.dto.ChatMessage> chatHistory;
        private SettingsSnapshot settings;
    }
}
