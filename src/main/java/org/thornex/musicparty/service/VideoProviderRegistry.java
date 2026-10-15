package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.room.RoomScoped;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.api.*;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

@Service @RoomScoped
public class VideoProviderRegistry {
    private final Map<String,VideoProvider> providers;
    private final XifanVideoApiService xifan;
    public long revision(String provider){return "xifan".equals(provider)?xifan.revision():0;}
    public VideoProviderRegistry(BilibiliVideoApiService bilibili,XifanVideoApiService xifan,AppProperties config){
        this.xifan=xifan;
        providers=Map.of("bilibili",new VideoProvider(){
            public String provider(){return "bilibili";}public boolean enabled(){return config.getBilibili().isEnabled();}
            public Mono<VideoDescriptor> metadata(Music music){return bilibili.getVideoDescriptor(music.id());}
            public Mono<ResolvedVideoSource> prepareSource(VideoDescriptor d,boolean admitted){return admitted?bilibili.resolveCurrentInstanceSource(d):bilibili.resolveSource(d);}
        },"xifan",new VideoProvider(){
            public String provider(){return "xifan";}public boolean enabled(){return config.isXifanEnabled() && xifan.deploymentEnabled();}
            public Mono<VideoDescriptor> metadata(Music music){if(music.videoSelection()==null || !music.id().equals("xifan:ep"+music.videoSelection().episodeId()))return Mono.error(failure("INVALID_SELECTION"));return xifan.metadata(music.videoSelection());}
            public Mono<ResolvedVideoSource> prepareSource(VideoDescriptor d,boolean admitted){return xifan.prepare(d.xifan().selection()).map(result->{
                long tolerance=Math.max(2000,Math.min(d.durationMs()/200,10000));if(Math.abs(result.descriptor().durationMs()-d.durationMs())>tolerance)throw failure("MEDIA_CONTENT_CHANGED");return result.source();});}
        });
    }
    public Mono<XifanVideoApiService.Prepared> restoreXifan(Music music){if(music.videoSelection()==null || !music.id().equals("xifan:ep"+music.videoSelection().episodeId()))return Mono.error(failure("INVALID_SELECTION"));return xifan.prepare(music.videoSelection());}
    public VideoProvider get(String provider){var value=providers.get(provider);if(value==null)throw failure("INVALID_SELECTION");return value;}
    public static String provider(Music music){return XifanVideoApiService.PLATFORM.equals(music.platform())?"xifan":"bilibili";}
    public static Music toMusic(VideoDescriptor d){return "xifan".equals(d.provider())?new Music(d.canonicalId(),d.title()+" · "+d.episodeTitle(),List.of("XIFAN"),d.durationMs(),XifanVideoApiService.PLATFORM,d.coverUrl(),d.xifan().selection()):BilibiliVideoApiService.toMusic(d);}
}
