package org.thornex.musicparty.event;

/** A single notification for one verified watching identity in one expired barrier. */
public record VideoWaitTimeoutEvent(String eventId, String waitId, String playbackId,
                                   long sourceRevision, String userName) {}
