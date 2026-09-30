package org.thornex.musicparty.dto;

/** Position is in milliseconds; playbackId identifies this particular play of a song. */
public record SeekRequest(String playbackId, Long position) {}
