package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.*;
import org.thornex.musicparty.dto.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Anonymous VIDEO provider; never participates in the music download pipeline. */
@Service
public class XifanVideoApiService {
    public static final String PLATFORM="xifan-video";
    private final XifanPublicConfigResolver config;
    private final WebClient client=WebClient.builder().defaultHeader("User-Agent","Mozilla/5.0 MirthParty/Video").codecs(c->c.defaultCodecs().maxInMemorySize(2*1024*1024)).build();
    private final WebClient apiClient=WebClient.builder().defaultHeader("User-Agent","Mozilla/5.0 MirthParty/Video").codecs(c->c.defaultCodecs().maxInMemorySize(1024*1024)).build();
    private final Semaphore requests=new Semaphore(8),probes=new Semaphore(4);
    private final java.util.concurrent.ConcurrentHashMap<String,Integer> roomRequests=new java.util.concurrent.ConcurrentHashMap<>(),roomProbes=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long cooldown;
    private static boolean reserve(java.util.concurrent.ConcurrentHashMap<String,Integer> map,String room){var accepted=new java.util.concurrent.atomic.AtomicBoolean();map.compute(room,(key,count)->{int n=count==null?0:count;if(n<2){accepted.set(true);return n+1;}return n;});return accepted.get();}
    private static void release(java.util.concurrent.ConcurrentHashMap<String,Integer> map,String room){map.computeIfPresent(room,(key,count)->count==1?null:count-1);}
    private static String room(){String value=org.thornex.musicparty.room.RoomContext.current();return value==null?"__probe__":value;}
    private record Cached<T>(T value,long expires,long revision) {}
    private final Map<String,Cached<Map<String,Object>>> searches=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Long,Cached<XifanVideo.Detail>> details=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String,Cached<Mp4MetadataProbe.Metadata>> metadataCache=Collections.synchronizedMap(new LinkedHashMap<>());
    public XifanVideoApiService(XifanPublicConfigResolver config){this.config=config;}
    @org.springframework.beans.factory.annotation.Value("${app.xifan.enabled:true}") private boolean deploymentEnabled=true;
    public boolean deploymentEnabled(){return deploymentEnabled;}
    public long revision(){return config.revision();}
    private <T> Mono<T> bounded(Mono<T> task){String room=room();return Mono.defer(()->{if(System.currentTimeMillis()<cooldown || !requests.tryAcquire())return Mono.error(failure("XIFAN_UNAVAILABLE"));if(!reserve(roomRequests,room)){requests.release();return Mono.error(failure("XIFAN_UNAVAILABLE"));}return task.doFinally(signal->{requests.release();release(roomRequests,room);});});}
    private Mono<JsonNode> post(String path,Object body,boolean retry){
        return (path.startsWith("/functions/")?config.playbackKey():config.key()).flatMap(key->bounded(apiClient.post().uri("https://api.xifanacg.com"+path).header("apikey",key).header("Authorization","Bearer "+key)
            .bodyValue(body).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(8))))
            .onErrorResume(WebClientResponseException.Unauthorized.class,error->{if(!retry)return Mono.error(failure("XIFAN_UNAVAILABLE"));config.invalidate();return post(path,body,false);})
            .onErrorResume(WebClientResponseException.TooManyRequests.class,error->{long seconds=60;try{seconds=Long.parseLong(error.getHeaders().getFirst("Retry-After"));}catch(Exception ignored){}cooldown=System.currentTimeMillis()+Math.max(60,Math.min(seconds,3600))*1000;return Mono.error(failure("XIFAN_UNAVAILABLE"));})
            .onErrorMap(error->error instanceof VideoFavorites.Failure?error:failure("XIFAN_UNAVAILABLE"));
    }
    public Mono<Map<String,Object>> search(String query,int page){
        if(query==null || query.codePointCount(0,query.length())>120 || page<1 || page>10000)return Mono.error(failure("INVALID_SELECTION"));
        String key=query+"\n"+page;var cached=searches.get(key);if(cached!=null && cached.expires>System.currentTimeMillis() && cached.revision==revision())return Mono.just(cached.value);
        Map<String,Object> body=new LinkedHashMap<>();body.put("search_term",query);body.put("page_number",page);body.put("items_per_page",24);body.put("sort_by","created_at");body.put("sort_order","desc");body.put("filter_only_published",true);
        for(String name:List.of("filter_type_id","filter_meta_tags","filter_is_finished","filter_format","filter_release_year"))body.put(name,null);
        return post("/rest/v1/rpc/search_animes",body,true).map(root->{if(!root.isArray() || root.size()>24)throw failure("XIFAN_UNAVAILABLE");
            List<XifanVideo.Anime> items=new ArrayList<>();for(JsonNode item:root)items.add(new XifanVideo.Anime(XifanDetailParser.id(item.path("id")),XifanDetailParser.string(item,"title"),XifanDetailParser.safeCover(item.path("cover_url").asText()),item.path("release_year").isInt()?item.path("release_year").asInt():null,item.path("is_finished").asBoolean(),item.path("current_episodes").asInt()));
            long total=root.isEmpty()?0:root.get(0).path("total_count").asLong(-1);if(total<0)throw failure("XIFAN_UNAVAILABLE");
            Map<String,Object> value=Map.of("provider","xifan","items",items,"total",total,"page",page,"pageSize",24,"hasMore",(long)page*24<total);
            synchronized(searches){if(searches.size()>=128)searches.remove(searches.keySet().iterator().next());searches.put(key,new Cached<>(value,System.currentTimeMillis()+60_000,revision()));}return value;
        });
    }
    public Mono<XifanVideo.Detail> detail(long animeId){
        if(animeId<1 || animeId>VideoIdentity.MAX_SAFE_ID)return Mono.error(failure("INVALID_SELECTION"));
        var cached=details.get(animeId);if(cached!=null && cached.expires>System.currentTimeMillis() && cached.revision==revision())return Mono.just(cached.value);
        return bounded(client.get().uri("https://next.xifanacg.com/anime/"+animeId).retrieve().bodyToMono(String.class).retryWhen(reactor.util.retry.Retry.fixedDelay(1,Duration.ofMillis(300))).timeout(Duration.ofSeconds(10)))
            .map(html->new XifanDetailParser().parse(html,animeId)).doOnNext(value->{synchronized(details){if(details.size()>=128)details.remove(details.keySet().iterator().next());details.put(animeId,new Cached<>(value,System.currentTimeMillis()+300_000,revision()));}})
            .onErrorMap(error->error instanceof VideoFavorites.Failure?error:failure("XIFAN_UNAVAILABLE"));
    }
    public Mono<VideoDescriptor> metadata(VideoSelection selection){return prepare(selection).map(Prepared::descriptor);}
    public record Prepared(VideoDescriptor descriptor,ResolvedVideoSource source) {}
    public Mono<Prepared> prepare(VideoSelection selection){
        if(selection==null)return Mono.error(failure("INVALID_SELECTION"));
        String probeRoom=room();
        return detail(selection.animeId()).flatMap(detail->{var route=detail.sources().stream().filter(s->s.id()==selection.sourceId()).findFirst().orElseThrow(()->failure("INVALID_SELECTION"));
            var ep=route.episodes().stream().filter(e->e.id()==selection.episodeId()).findFirst().orElseThrow(()->failure("INVALID_SELECTION"));
            return post("/functions/v1/issue-web-playback",Map.of("action","fallback","episode_id",ep.id(),"source_id",route.id()),true).flatMap(root->{
                if(!root.path("ok").asBoolean() || XifanDetailParser.id(root.path("anime_id"))!=selection.animeId() || XifanDetailParser.id(root.path("episode_id"))!=ep.id())throw failure("XIFAN_UNAVAILABLE");
                Instant now=Instant.now(),expiry=expiry(root,now);JsonNode candidates=root.path("candidates");if(!candidates.isArray())throw failure("XIFAN_UNAVAILABLE");
                List<String> urls=new ArrayList<>();for(JsonNode candidate:candidates)if(candidate.path("source_id").isIntegralNumber() && candidate.path("source_id").asLong()==route.id() && candidate.path("url").isTextual())urls.add(candidate.path("url").asText());
                if(urls.isEmpty() || urls.size()>2)throw failure("MP4_NOT_SUPPORTED");
                return Mono.fromCallable(()->{if(!probes.tryAcquire())throw failure("XIFAN_UNAVAILABLE");if(!reserve(roomProbes,probeRoom)){probes.release();throw failure("XIFAN_UNAVAILABLE");}try{
                    VideoFavorites.Failure last=null;var probe=new Mp4MetadataProbe();for(String url:urls){try(var reader=new XifanRangeReader(url)){
                        String cacheKey=selection.animeId()+":"+ep.id()+":"+route.id();var cached=metadataCache.get(cacheKey);
                        var metadata=probe.probe(reader,cached!=null && cached.expires>System.currentTimeMillis() && cached.revision==revision()?cached.value:null);
                        if(metadata.validator()!=null)synchronized(metadataCache){if(metadataCache.size()>=128)metadataCache.remove(metadataCache.keySet().iterator().next());metadataCache.put(cacheKey,new Cached<>(metadata,System.currentTimeMillis()+600_000,revision()));}
                        var x=new XifanVideo.Descriptor(selection.animeId(),ep.id(),route.id(),route.code(),route.name(),ep.episodeNumber(),ep.kind(),metadata.width(),metadata.height());
                        var descriptor=new VideoDescriptor(ep.itemId(),null,0,0,detail.anime().title(),ep.title(),"XIFAN",metadata.durationMs(),detail.anime().coverUrl(),List.of(),"XIFAN_EPISODE",null,null,null,ep.title(),null,"xifan",x);
                        String mediaUrl=reader.mediaUri().toString();boolean routed=!mediaUrl.equals(url);
                        // The router's declared expiry does not establish the disk URL's lifetime.
                        // Unknown final deadlines use the existing bounded soft-freshness renewal.
                        Instant mediaExpiry=routed?org.thornex.musicparty.util.VideoSourceExpiry.deadline(mediaUrl):expiry;
                        if(routed&&mediaExpiry!=null&&!mediaExpiry.isAfter(Instant.now().plusSeconds(60)))throw failure("XIFAN_UNAVAILABLE");
                        var mp4=new Mp4VideoSource(mediaUrl,List.of(),"mp4",0,"mp4",metadata.height()+"P",metadata.durationMs(),mediaExpiry,metadata.width(),metadata.height());
                        return new Prepared(descriptor,new ResolvedVideoSource(mediaUrl,List.of(),"mp4",0,metadata.durationMs(),now,mediaExpiry,null,mp4,routed||mediaExpiry==null?"URL_DEADLINE":"UPSTREAM_DECLARED"));
                    }catch(VideoFavorites.Failure failure){last=failure;}}
                    throw last==null?failure("MEDIA_METADATA_UNAVAILABLE"):last;
                }finally{probes.release();release(roomProbes,probeRoom);}}).subscribeOn(Schedulers.boundedElastic());
            });
        }).timeout(Duration.ofSeconds(20)).onErrorMap(error->error instanceof VideoFavorites.Failure?error:failure("XIFAN_UNAVAILABLE"));
    }
    static Instant expiry(JsonNode root,Instant now){var value=root.get("expires_at");if(value==null || value.isNull())return null;
        if(!value.isIntegralNumber() || !value.canConvertToLong())throw failure("XIFAN_UNAVAILABLE");long seconds=value.asLong();
        if(seconds<=now.getEpochSecond()+60 || seconds>now.getEpochSecond()+7*86400)throw failure("XIFAN_UNAVAILABLE");return Instant.ofEpochSecond(seconds);
    }
}
