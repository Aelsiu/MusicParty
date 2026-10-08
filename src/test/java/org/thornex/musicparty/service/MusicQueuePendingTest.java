package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MusicQueuePendingTest {
    @Test void pendingIsSavedOnceWithIdentityAndPriorityAndQuotaReservedDuringMetadataLookup() {
        AppProperties props = new AppProperties();
        props.getQueue().setMaxSize(2);
        props.getQueue().setMaxUserSongs(2);
        MusicQueueManager queue = new MusicQueueManager(props);
        UserSummary user = new UserSummary("u", "s", "用户", false);
        var first = queue.add(new Music("1", "一", List.of(), 1000, "netease", ""), user, QueueItemStatus.READY);
        queue.top(first.queueId(), PlayMode.SEQUENTIAL);
        var selected = queue.pollNext(PlayMode.SEQUENTIAL, true, false, Map.of("1", QueueItemStatus.READY), Set.of("u"));
        queue.retainPending(selected);
        queue.retainPending(selected);
        assertEquals(1, queue.getPersistenceQueueSnapshot().size());
        assertEquals(first.queueId(), queue.getPersistenceQueueSnapshot().getFirst().queueId());
        assertEquals(Priority.GLOBAL_TOP, queue.getPersistenceQueueSnapshot().getFirst().priority());
        var reservation = queue.reserve("u", 2);
        assertEquals(1, reservation.count());
        assertEquals(0, queue.reserve("u", 1).count());
        queue.clearPendingQueue();
        assertFalse(reservation.release(), "late metadata cannot re-add cleared items");
        assertEquals(2, queue.reserve("u", 2).count());
    }
}
