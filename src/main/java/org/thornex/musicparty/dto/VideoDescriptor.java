package org.thornex.musicparty.dto;

import java.util.List;

/** Trusted metadata for one selected page and the available pages of an ordinary upload. */
public record VideoDescriptor(
        String canonicalId, String bvid, long cid, int page,
        String title, String pageTitle, String ownerName,
        long durationMs, String coverUrl, List<VideoPageDescriptor> pages
) {
    public VideoDescriptor {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public VideoDescriptor(String canonicalId, String bvid, long cid, int page,
                           String title, String pageTitle, String ownerName,
                           long durationMs, String coverUrl) {
        this(canonicalId, bvid, cid, page, title, pageTitle, ownerName, durationMs, coverUrl,
                List.of(new VideoPageDescriptor(canonicalId, cid, page, pageTitle, durationMs, true)));
    }
}
