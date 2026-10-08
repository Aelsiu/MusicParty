package org.thornex.musicparty.playback;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** One-shot shared Reactor scheduling; the callback only admits work to the room mailbox. */
@FunctionalInterface
public interface VideoWaitAlarmScheduler {
    Disposable schedule(long delayNanos, Runnable callback);
    static VideoWaitAlarmScheduler reactor() {
        return (delay, callback) -> Mono.delay(Duration.ofNanos(Math.max(1, delay))).subscribe(ignored -> callback.run());
    }
}
