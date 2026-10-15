package org.thornex.musicparty.dto;

/** Safe durable lookup hints. Media credentials never belong here. */
public record VideoSelection(String provider,long animeId,long episodeId,long sourceId) {
    public VideoSelection {
        if(!"xifan".equals(provider) || animeId<1 || episodeId<1 || sourceId<1 || animeId>VideoIdentity.MAX_SAFE_ID
                || episodeId>VideoIdentity.MAX_SAFE_ID || sourceId>VideoIdentity.MAX_SAFE_ID) throw VideoFavorites.failure("INVALID_SELECTION");
    }
}
