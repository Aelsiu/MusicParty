package org.thornex.musicparty.playback;

import org.springframework.stereotype.Component;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.room.RoomScoped;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Independent of the coordinator and both engines; never broadcasts while an engine holds a lock. */
@Component
@RoomScoped
public class PlaybackEventSink {
    private final EnumSet<MediaMode> stateDirty = EnumSet.noneOf(MediaMode.class);
    private final EnumSet<MediaMode> queueDirty = EnumSet.noneOf(MediaMode.class);
    private final List<Notice> notices = new ArrayList<>();

    public synchronized void markStateDirty(MediaMode mode) { stateDirty.add(mode); }
    public synchronized void markQueueDirty(MediaMode mode) { queueDirty.add(mode); stateDirty.add(mode); }
    public synchronized void emit(MediaMode mode, Object event) { notices.add(new Notice(mode, event)); }

    /** Inactive dirty flags survive. Inactive notices are discarded at this command boundary. */
    public synchronized Drain take(MediaMode active) {
        List<Object> events = notices.stream().filter(notice -> notice.mode == active).map(Notice::event).toList();
        notices.clear();
        return new Drain(stateDirty.remove(active), queueDirty.remove(active), events);
    }

    public synchronized void clear() { stateDirty.clear(); queueDirty.clear(); notices.clear(); }
    private record Notice(MediaMode mode, Object event) {}
    public record Drain(boolean stateDirty, boolean queueDirty, List<Object> events) {
        public Drain { events = List.copyOf(events); }
    }
}
