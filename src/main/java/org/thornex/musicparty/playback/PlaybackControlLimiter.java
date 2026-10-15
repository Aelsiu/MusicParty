package org.thornex.musicparty.playback;

import org.springframework.stereotype.Component;
import org.thornex.musicparty.room.RoomScoped;
import java.util.function.LongSupplier;

/** One cooldown shared by the music and video engines, including across mode switches. */
@Component
@RoomScoped
public class PlaybackControlLimiter {
    private final LongSupplier clock;
    private long lastAccepted = Long.MIN_VALUE;
    public PlaybackControlLimiter() { this(System::nanoTime); }
    public PlaybackControlLimiter(LongSupplier clock) { this.clock = clock; }
    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        if (lastAccepted != Long.MIN_VALUE && now - lastAccepted < 1_000_000_000L) return false;
        lastAccepted = now;
        return true;
    }
}
