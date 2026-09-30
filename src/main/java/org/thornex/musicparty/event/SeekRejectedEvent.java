package org.thornex.musicparty.event;

/** A rejected seek is feedback for its originating connection, not a room-wide notification. */
public record SeekRejectedEvent(String sessionId, String userToken, String message) {}
