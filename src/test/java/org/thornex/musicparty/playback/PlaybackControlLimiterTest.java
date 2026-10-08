package org.thornex.musicparty.playback;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackControlLimiterTest {
    @Test void sameRoomCooldownSurvivesAnEngineChange() {
        AtomicLong now = new AtomicLong(10_000_000_000L);
        PlaybackControlLimiter limiter = new PlaybackControlLimiter(now::get);
        assertTrue(limiter.tryAcquire());
        now.addAndGet(999_000_000L);
        assertFalse(limiter.tryAcquire());
        now.addAndGet(1_000_000L);
        assertTrue(limiter.tryAcquire());
    }
}
