package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminConfigUpdateRequest;
import org.thornex.musicparty.dto.SystemConfigSnapshot;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.MusicQueueManager;

/** Persists global limits once; room getters read a single, atomically published snapshot. */
@Service
@Slf4j
public class SystemConfigService {
    private final AppProperties defaults;
    private final RoomRepository repository;
    private final ObjectMapper mapper;
    private final RoomScope scope;
    private final ApplicationContext context;
    private volatile SystemConfigSnapshot current;

    public SystemConfigService(@Qualifier("defaultAppProperties") AppProperties defaults,
                               RoomRepository repository, ObjectMapper mapper,
                               RoomScope scope, ApplicationContext context) {
        this.defaults = defaults;
        this.repository = repository;
        this.mapper = mapper;
        this.scope = scope;
        this.context = context;
    }

    @PostConstruct
    public void initialize() throws Exception {
        String saved = repository.systemConfig();
        // Conflicting legacy room overrides cannot define one global value. Seed from deployment defaults.
        current = saved == null ? SystemConfigSnapshot.from(defaults) : mapper.readValue(saved, SystemConfigSnapshot.class);
        if (saved == null) repository.saveSystemConfig(mapper.writeValueAsString(current));
    }

    public SystemConfigSnapshot snapshot() { return current; }

    public synchronized void update(AdminConfigUpdateRequest request) {
        if (request.hasRoomConfig()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "房间播放配置请在管理终端中设置");
        }
        SystemConfigSnapshot next = current.withUpdate(request);
        String invalid = next.validationError();
        if (invalid != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid);
        try {
            repository.saveSystemConfig(mapper.writeValueAsString(next));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "全局系统参数保存失败", e);
        }
        current = next;
        for (String id : scope.activeIds()) {
            if (!scope.active(id) || !repository.exists(id)) continue;
            try (var ignored = RoomContext.enter(id)) {
                context.getBean(MusicQueueManager.class).trimHistoryToLimit();
                context.getBean(ChatService.class).trimHistoryToLimit();
                context.getBean(MusicPlayerService.class).broadcastFullPlayerState();
            } catch (RuntimeException e) {
                if (repository.exists(id) && scope.active(id)) log.warn("Could not refresh room {} after global limits changed", id, e);
            }
        }
    }

    /** Preserve room-owned settings while making legacy or later room-local limit setters ineffective. */
    public void bind(AppProperties properties) {
        AppProperties.QueueConfig oldQueue = properties.getQueue();
        AppProperties.QueueConfig queue = new AppProperties.QueueConfig() {
            @Override public int getMaxSize() { return current.maxQueueSize(); }
            @Override public int getHistorySize() { return current.maxHistorySize(); }
            @Override public int getMaxUserSongs() { return current.maxUserSongs(); }
        };
        queue.setPersistenceFile(oldQueue.getPersistenceFile());
        queue.setPersistenceIntervalMs(oldQueue.getPersistenceIntervalMs());
        properties.setQueue(queue);

        AppProperties.PlayerConfig oldPlayer = properties.getPlayer();
        AppProperties.PlayerConfig player = new AppProperties.PlayerConfig() {
            @Override public int getMaxPlaylistImportSize() { return current.maxPlaylistImportSize(); }
        };
        player.setVoteSkipEnabled(oldPlayer.isVoteSkipEnabled());
        player.setVoteSkipThreshold(oldPlayer.getVoteSkipThreshold());
        player.setVoteSkipWaitTime(oldPlayer.getVoteSkipWaitTime());
        player.setSeekPolicy(oldPlayer.getSeekPolicy());
        player.setSyncBroadcastIntervalMs(oldPlayer.getSyncBroadcastIntervalMs());
        properties.setPlayer(player);

        properties.setChat(new AppProperties.ChatConfig() {
            @Override public int getMaxHistorySize() { return current.maxChatHistorySize(); }
            @Override public long getMinIntervalMs() { return current.minChatIntervalMs(); }
            @Override public int getMaxMessageLength() { return current.maxChatMessageLength(); }
        });

        AppProperties.BilibiliApiConfig oldBilibili = properties.getBilibili();
        AppProperties.BilibiliApiConfig bilibili = new AppProperties.BilibiliApiConfig() {
            @Override public int getMaxDurationMinutes() { return current.bilibiliMaxDurationMinutes(); }
        };
        bilibili.setBaseUrl(oldBilibili.getBaseUrl());
        bilibili.setCookie(oldBilibili.getCookie());
        bilibili.setEnabled(oldBilibili.isEnabled());
        properties.setBilibili(bilibili);
    }
}
