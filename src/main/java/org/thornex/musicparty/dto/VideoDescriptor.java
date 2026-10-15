package org.thornex.musicparty.dto;

import java.util.List;

/** Trusted metadata for one selected page and the available pages of an ordinary upload. */
public record VideoDescriptor(
        String canonicalId, String bvid, long cid, int page,
        String title, String pageTitle, String ownerName,
        long durationMs, String coverUrl, List<VideoPageDescriptor> pages,
        String contentType, Long seasonId, Long epId, Long aid, String episodeTitle, Integer episodeOrder, String provider, XifanVideo.Descriptor xifan
) {
    public VideoDescriptor {
        provider = provider == null ? "bilibili" : provider;
        if (!java.util.Set.of("bilibili","xifan").contains(provider) || "xifan".equals(provider) && xifan == null) throw VideoFavorites.failure("INVALID_SELECTION");
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public VideoDescriptor(String canonicalId,String bvid,long cid,int page,String title,String pageTitle,String ownerName,long durationMs,String coverUrl,List<VideoPageDescriptor> pages,String contentType,Long seasonId,Long epId,Long aid,String episodeTitle,Integer episodeOrder) { this(canonicalId,bvid,cid,page,title,pageTitle,ownerName,durationMs,coverUrl,pages,contentType,seasonId,epId,aid,episodeTitle,episodeOrder,"bilibili",null); }
    /** Preserve the existing UGC JSON/code construction contract. */
    public VideoDescriptor(String canonicalId, String bvid, long cid, int page,
                           String title, String pageTitle, String ownerName,
                           long durationMs, String coverUrl, List<VideoPageDescriptor> pages) {
        this(canonicalId,bvid,cid,page,title,pageTitle,ownerName,durationMs,coverUrl,pages,
                "UGC",null,null,null,null,null);
    }

    public VideoDescriptor(String canonicalId, String bvid, long cid, int page,
                           String title, String pageTitle, String ownerName,
                           long durationMs, String coverUrl) {
        this(canonicalId, bvid, cid, page, title, pageTitle, ownerName, durationMs, coverUrl,
                List.of(new VideoPageDescriptor(canonicalId, cid, page, pageTitle, durationMs, true)));
    }
}
