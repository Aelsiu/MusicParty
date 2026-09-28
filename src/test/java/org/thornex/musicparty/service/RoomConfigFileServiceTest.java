package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.dto.AdminConfigUpdateRequest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomConfigFileServiceTest {
    @TempDir Path tempDir;

    @Test
    void updatesOnlyRequestedPropertiesAndPreservesCredentials() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "# local settings\napp.music-api.admin-password=private-value\n"
                + "app.music-api.queue.max-size=1000\napp.music-api.netease.quality=exhigh\n");
        RoomConfigFileService service = new RoomConfigFileService(file);

        service.persist(new AdminConfigUpdateRequest(
                250, null, null, null, null, null, null, "lossless",
                null, null, null, null, null, null, true, 30));

        String saved = Files.readString(file);
        assertTrue(saved.contains("# local settings"));
        assertTrue(saved.contains("app.music-api.admin-password=private-value"));
        assertTrue(saved.contains("app.music-api.queue.max-size=250"));
        assertTrue(saved.contains("app.music-api.netease.quality=lossless"));
        assertTrue(saved.contains("app.music-api.player.idle-kick-enabled=true"));
        assertTrue(saved.contains("app.music-api.player.idle-kick-minutes=30"));
    }
}
