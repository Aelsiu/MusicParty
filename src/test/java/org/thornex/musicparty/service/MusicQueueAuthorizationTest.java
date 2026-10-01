package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MusicQueueAuthorizationTest {
    @Test void ordinaryUserCanOnlyDeleteOwnSongAndManagersCanDeleteOthers() {
        var queue = new MusicQueueManager(new AppProperties());
        var item = queue.add(new Music("song", "歌曲", List.of("歌手"), 10000, "netease", ""),
                new UserSummary("owner-profile", "session", "点歌者", false), QueueItemStatus.READY);
        assertTrue(queue.removeAuthorized(item.queueId(), "other-profile", false).isEmpty());
        assertEquals(1, queue.getQueueSnapshot().size());
        assertTrue(queue.removeAuthorized(item.queueId(), "owner-profile", false).isPresent());
        var another = queue.add(item.music(), item.enqueuedBy(), QueueItemStatus.READY);
        assertTrue(queue.removeAuthorized(another.queueId(), "manager-profile", true).isPresent());
        assertTrue(queue.getQueueSnapshot().isEmpty());
    }
}
