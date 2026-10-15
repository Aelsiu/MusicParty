package org.thornex.musicparty.dto;

/** Public preview metadata; trusted CID/BV binding stays in the server descriptor. */
public record VideoEpisodeDescriptor(String itemId, String canonicalId, long epId, int episodeOrder,
        String episodeTitle, String title, long durationMs, String cover, boolean selectable,
        String reasonCode, String availability) {}
