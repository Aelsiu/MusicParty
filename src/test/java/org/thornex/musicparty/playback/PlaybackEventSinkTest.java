package org.thornex.musicparty.playback;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.enums.MediaMode;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackEventSinkTest {
    @Test void inactiveChangesDoNotLeakAndArePublishedWhenActivated() {
        PlaybackEventSink sink = new PlaybackEventSink();
        sink.markQueueDirty(MediaMode.MUSIC);
        sink.emit(MediaMode.MUSIC, "old-mode-notice");
        sink.markStateDirty(MediaMode.VIDEO);
        sink.emit(MediaMode.VIDEO, "video-notice");
        var video = sink.take(MediaMode.VIDEO);
        assertTrue(video.stateDirty());
        assertFalse(video.queueDirty());
        assertEquals(java.util.List.of("video-notice"), video.events());
        var music = sink.take(MediaMode.MUSIC);
        assertTrue(music.stateDirty());
        assertTrue(music.queueDirty());
        assertTrue(music.events().isEmpty());
        assertFalse(sink.take(MediaMode.MUSIC).stateDirty());
    }
}
