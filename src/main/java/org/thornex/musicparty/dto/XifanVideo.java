package org.thornex.musicparty.dto;

import java.util.List;
public final class XifanVideo {
    private XifanVideo() {}
    public record Anime(long id,String title,String coverUrl,Integer year,boolean finished,int currentEpisodes) {}
    public record Episode(long id,String title,String kind,double episodeNumber) { public String itemId(){return "xifan:ep"+id;} }
    public record Source(long id,String code,String name,List<Episode> episodes,String availability) { public Source{episodes=List.copyOf(episodes);} }
    public record Detail(Anime anime,List<Source> sources) { public Detail{sources=List.copyOf(sources);} }
    public record Descriptor(long animeId,long episodeId,long sourceId,String sourceCode,String sourceName,double episodeNumber,String kind,Integer width,Integer height) {
        public VideoSelection selection(){return new VideoSelection("xifan",animeId,episodeId,sourceId);}
    }
}
