package org.thornex.musicparty.dto;

import java.util.List;

/** Normalized public text only; no sender identity or upstream URL. */
public record VideoDanmakuSegment(int segmentIndex, List<Entry> entries) {
    public VideoDanmakuSegment { entries = List.copyOf(entries); }
    public record Entry(String id, long progressMs, int mode, int fontSize, int color, String text) {}
}
