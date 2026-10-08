package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.room.RoomScoped;

/** Separately qualified queue/history using the existing fair-shuffle rules. */
@Service("videoQueueManager")
@RoomScoped
public class VideoQueueManager extends MusicQueueManager {
    public VideoQueueManager(AppProperties properties) { super(properties); }
}
