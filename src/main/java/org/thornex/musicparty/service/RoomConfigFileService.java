package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.AdminConfigUpdateRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Keeps the externally mounted Spring config in sync with administrator edits. */
@Service
@org.thornex.musicparty.room.RoomScoped
public class RoomConfigFileService {
    private final Path configFile;

    public RoomConfigFileService() {
        this(Path.of("config", "application.properties"));
    }

    RoomConfigFileService(Path configFile) {
        this.configFile = configFile;
    }

    public boolean isConfigured() {
        return org.thornex.musicparty.room.RoomContext.current() == null && Files.isRegularFile(configFile);
    }

    public synchronized void persist(AdminConfigUpdateRequest request) throws IOException {
        // The Windows launcher continues to use queue-data.json when no external file is configured.
        if (!isConfigured()) return;

        Map<String, String> changes = new LinkedHashMap<>();
        put(changes, "app.music-api.netease.quality", request.neteaseQuality());
        put(changes, "app.music-api.queue.max-size", request.maxSize());
        put(changes, "app.music-api.queue.history-size", request.historySize());
        put(changes, "app.music-api.queue.max-user-songs", request.maxUserSongs());
        put(changes, "app.music-api.player.max-playlist-import-size", request.maxPlaylistImportSize());
        put(changes, "app.music-api.chat.max-history-size", request.maxChatHistorySize());
        put(changes, "app.music-api.chat.min-interval-ms", request.minChatIntervalMs());
        put(changes, "app.music-api.chat.max-message-length", request.maxChatMessageLength());
        put(changes, "app.music-api.netease.enabled", request.neteaseEnabled());
        put(changes, "app.music-api.bilibili.enabled", request.bilibiliEnabled());
        put(changes, "app.music-api.bilibili.max-duration-minutes", request.bilibiliMaxDurationMinutes());
        put(changes, "app.music-api.player.vote-skip-enabled", request.voteSkipEnabled());
        put(changes, "app.music-api.player.vote-skip-threshold", request.voteSkipThreshold());
        put(changes, "app.music-api.player.vote-skip-wait-time", request.voteSkipWaitTime());
        if (changes.isEmpty()) return;

        List<String> lines = new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8));
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Pattern propertyLine = Pattern.compile("^\\s*" + Pattern.quote(change.getKey()) + "\\s*[=:].*$");
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (propertyLine.matcher(lines.get(i)).matches()) {
                    lines.set(i, change.getKey() + "=" + change.getValue());
                    found = true;
                }
            }
            if (!found) lines.add(change.getKey() + "=" + change.getValue());
        }

        Path absoluteFile = configFile.toAbsolutePath();
        Path temp = Files.createTempFile(absoluteFile.getParent(), "application-", ".tmp");
        try {
            Files.writeString(temp, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, absoluteFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absoluteFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void put(Map<String, String> changes, String key, Object value) {
        if (value != null) changes.put(key, value.toString());
    }
}
