package org.thornex.musicparty.dto;

import java.util.List;
import java.util.Set;

public record NowPlayingInfo(
        PlayableMusic music,
        long currentPosition,
        String enqueuedById,
        String enqueuedByName,
        Set<String> likedUserIds,
        List<Long> likeMarkers,
        String playbackId,
        long seekAvailableAt,
        long serverTime,
        long sourceRevision,
        Long sourceExpiresAt,
        ResolvedVideoSource videoSource,
        VideoDescriptor videoDescriptor
) {
    public NowPlayingInfo(PlayableMusic music, long currentPosition, String enqueuedById,
            String enqueuedByName, Set<String> likedUserIds, List<Long> likeMarkers,
            String playbackId, long seekAvailableAt, long serverTime,
            long sourceRevision, Long sourceExpiresAt) {
        this(music, currentPosition, enqueuedById, enqueuedByName, likedUserIds, likeMarkers,
                playbackId, seekAvailableAt, serverTime, sourceRevision, sourceExpiresAt, null, null);
    }
    public NowPlayingInfo(PlayableMusic music, long currentPosition, String enqueuedById,
            String enqueuedByName, Set<String> likedUserIds, List<Long> likeMarkers,
            String playbackId, long seekAvailableAt, long serverTime) {
        this(music, currentPosition, enqueuedById, enqueuedByName, likedUserIds, likeMarkers,
                playbackId, seekAvailableAt, serverTime, 0, null);
    }
}
