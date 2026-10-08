package org.thornex.musicparty.dto;

/** Observation only: connection identity and room context are verified outside this payload. */
public record VideoWatchStatusRequest(
        String playbackId, long sourceRevision, long sourceGeneration, long sequence,
        String status, boolean synchronizedWithRoom, boolean foreground, boolean playbackAllowed,
        String waitId, Long waitRevision, Long positionMs
) {}
