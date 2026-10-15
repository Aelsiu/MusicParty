package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.util.BilibiliApiUtils;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Metadata/source adapter only. No media fetch, proxy, fallback bypass or credential extraction. */
@Service @org.thornex.musicparty.room.RoomScoped
public class BilibiliPgcApiService {
    public record Season(VideoSeasonDescriptor season, List<VideoEpisodeDescriptor> episodes,
                         Map<String,VideoDescriptor> descriptors) {
        public Season { episodes=List.copyOf(episodes); descriptors=Map.copyOf(descriptors); }
    }
    private final WebClient client;
    private final AppProperties properties;
    private final BilibiliCookieService cookies;
    public BilibiliPgcApiService(WebClient client, AppProperties properties, BilibiliCookieService cookies) {
        this.client=client; this.properties=properties; this.cookies=cookies;
    }
    public Mono<Season> season(long id, boolean episode) {
        if (id<=0 || id>VideoIdentity.MAX_SAFE_ID) return Mono.error(failure("INVALID_VIDEO_INPUT"));
        return request("/pgc/view/web/season", Map.of(episode?"ep_id":"season_id",Long.toString(id)))
            .map(json -> parseSeason(json,episode?null:id,episode?id:null,properties.getPlayer().getVideoMaxDurationMinutes()));
    }
    public Mono<JsonNode> legacyView(String bvid) {
        if(bvid==null || !bvid.matches("BV[A-Za-z0-9]{10}")) return Mono.error(failure("INVALID_VIDEO_INPUT"));
        return request("/x/web-interface/view",Map.of("bvid",bvid));
    }
    public Mono<ResolvedVideoSource> source(VideoDescriptor descriptor, boolean currentInstance) {
        return Mono.defer(() -> {
            validateDescriptor(descriptor);
            if (!currentInstance && descriptor.durationMs()>properties.getPlayer().getVideoMaxDurationMinutes()*60000L)
                throw failure("DURATION_EXCEEDED");
            return request("/pgc/player/web/v2/playurl",Map.of("ep_id",Long.toString(descriptor.epId()),
                "cid",Long.toString(descriptor.cid()),"fnval","4048","qn","80","fourk","1"))
                .map(json -> parseSource(json,descriptor,Instant.now()))
                .map(source -> {
                    var filtered=org.thornex.musicparty.service.VideoSourcePolicy.filter(source,properties.getPlayer().getVideoMaxResolution());
                    if(filtered==null) throw failure("VIDEO_QUALITY_LIMIT");
                    return filtered;
                });
        });
    }
    private Mono<JsonNode> request(String path, Map<String,String> parameters) {
        Mono<String> credential=cookies.hasCookie()?cookies.getCookieHeader():Mono.just("");
        return credential.flatMap(cookie -> client.get().uri(builder -> {
            // Fixed official host: never use a page-supplied URL or redirect target.
            var target=org.springframework.web.util.UriComponentsBuilder.fromHttpUrl("https://api.bilibili.com"+path);
            parameters.forEach(target::queryParam); return target.build().toUri();
        }).headers(headers -> {
            headers.set("Referer","https://www.bilibili.com/");
            headers.set("User-Agent","Mozilla/5.0");
            if (!cookie.isEmpty()) headers.set("Cookie",cookie);
        }).retrieve().bodyToMono(JsonNode.class)).timeout(Duration.ofSeconds(20))
          .switchIfEmpty(Mono.error(failure("TEMPORARY_FAILURE")))
          .onErrorMap(error -> error instanceof VideoFavorites.Failure?error:failure("TEMPORARY_FAILURE"));
    }
    private static JsonNode result(JsonNode json) {
        if(!json.path("code").isIntegralNumber()) throw failure("INCOMPLETE_SOURCE");
        if(json.path("code").asInt()!=0) {
            // Unknown platform codes cannot prove membership, purchase or geography.
            throw failure(switch(json.path("code").asInt()) {
                case -101 -> "CREDENTIALS_REQUIRED";
                case -400,-404 -> "CONTENT_UNAVAILABLE";
                case -352,-412,-503 -> "TEMPORARY_FAILURE";
                default -> "CONTENT_UNAVAILABLE";
            });
        }
        if(!json.path("result").isObject()) throw failure("INCOMPLETE_SOURCE");
        return json.path("result");
    }
    private static long positive(JsonNode node) {
        if(!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<=0 || node.longValue()>VideoIdentity.MAX_SAFE_ID)
            throw failure("IDENTITY_MISMATCH");
        return node.longValue();
    }
    static Season parseSeason(JsonNode json, Long expectedSeason, Long expectedEp, int maxMinutes) {
        JsonNode result=result(json); long seasonId=positive(result.path("season_id"));
        if(result.path("title").asText().isBlank()) throw failure("INCOMPLETE_SOURCE");
        if(expectedSeason!=null && seasonId!=expectedSeason) throw failure("IDENTITY_MISMATCH");
        JsonNode rows=result.path("episodes");
        if(!rows.isArray() || rows.isEmpty()) throw failure("CONTENT_UNAVAILABLE");
        int returned=Math.min(500,rows.size());
        List<VideoEpisodeDescriptor> episodes=new ArrayList<>(); Map<String,VideoDescriptor> trusted=new LinkedHashMap<>();
        Set<Long> ids=new HashSet<>(); boolean found=expectedEp==null;
        for(int index=0;index<returned;index++) {
            JsonNode row=rows.get(index); long ep;
            try { ep=positive(row.path("id")); }
            catch(VideoFavorites.Failure invalid) {
                episodes.add(new VideoEpisodeDescriptor("unavailable:"+index,null,0,index,row.path("title").asText(),
                    row.path("long_title").asText(),0,null,false,"IDENTITY_MISMATCH","BLOCKED"));
                continue;
            }
            if(!ids.add(ep) || row.has("ep_id") && positive(row.path("ep_id"))!=ep) throw failure("IDENTITY_MISMATCH");
            if(expectedEp!=null && ep==expectedEp) found=true;
            String id="bilibili:ep"+ep, reason=null;
            VideoDescriptor descriptor=null;
            try {
                long aid=positive(row.path("aid")),cid=positive(row.path("cid")),duration=positive(row.path("duration"));
                String bvid=row.path("bvid").asText();
                if(!bvid.matches("BV[A-Za-z0-9]{10}")) throw failure("IDENTITY_MISMATCH");
                if(row.path("is_view_hide").asBoolean(false)) reason="CONTENT_UNAVAILABLE";
                JsonNode canWatch=row.path("rights").path("can_watch");
                if(canWatch.isBoolean() && !canWatch.booleanValue() || canWatch.isIntegralNumber() && canWatch.longValue()==0) reason="CONTENT_UNAVAILABLE";
                if(duration>maxMinutes*60000L) reason="DURATION_EXCEEDED";
                descriptor=new VideoDescriptor(id,bvid,cid,0,result.path("title").asText(),row.path("long_title").asText(),
                    "",duration,BilibiliApiUtils.normalizeCoverUrl(row.path("cover").asText()),List.of(),"PGC",
                    seasonId,ep,aid,row.path("title").asText(),index);
            } catch(VideoFavorites.Failure invalid) { reason="IDENTITY_MISMATCH"; }
            if(descriptor!=null) trusted.put(id,descriptor);
            episodes.add(new VideoEpisodeDescriptor(id,id,ep,index,row.path("title").asText(),row.path("long_title").asText(),
                descriptor==null?0:descriptor.durationMs(),descriptor==null?null:descriptor.coverUrl(),reason==null,reason,
                reason==null?"ELIGIBLE_FOR_SOURCE_CHECK":"BLOCKED"));
        }
        if(!found) throw failure("CONTENT_UNAVAILABLE");
        return new Season(new VideoSeasonDescriptor(seasonId,result.path("title").asText(),
            BilibiliApiUtils.normalizeCoverUrl(result.path("cover").asText()),rows.size(),returned,rows.size()>returned),episodes,trusted);
    }
    static void validateDescriptor(VideoDescriptor d) {
        if(d==null || !"PGC".equals(d.contentType()) || d.epId()==null || d.seasonId()==null || d.aid()==null
            || d.epId()<=0 || d.seasonId()<=0 || d.aid()<=0 || d.cid()<=0 || d.durationMs()<=0
            || !d.canonicalId().equals("bilibili:ep"+d.epId()) || !d.bvid().matches("BV[A-Za-z0-9]{10}")
            || d.page()!=0 || !d.pages().isEmpty()) throw failure("IDENTITY_MISMATCH");
    }
    static ResolvedVideoSource parseSource(JsonNode json, VideoDescriptor descriptor, Instant now) {
        validateDescriptor(descriptor); JsonNode result=result(json);
        JsonNode info=result.path("video_info"),business=result.path("play_view_business_info");
        JsonNode ep=business.path("episode_info"),season=business.path("season_info");
        if(positive(ep.path("ep_id"))!=descriptor.epId() || positive(ep.path("cid"))!=descriptor.cid()
            || positive(ep.path("aid"))!=descriptor.aid() || !ep.path("bvid").asText().equals(descriptor.bvid())
            || positive(season.path("season_id"))!=descriptor.seasonId()) throw failure("IDENTITY_MISMATCH");
        if(info.path("is_preview").asBoolean(false) || info.path("is_preview").asInt(0)!=0) throw failure("PREVIEW_ONLY");
        if(info.path("is_drm").asBoolean(false) || info.path("is_drm").asInt(0)!=0) throw failure("DRM_UNSUPPORTED");
        if(!"PLAY_WHOLE".equals(result.path("play_check").path("play_detail").asText())
            || !explicitFalse(info.path("is_preview")) || !explicitFalse(info.path("is_drm")) || !info.path("code").isIntegralNumber()
            || info.path("code").asInt()!=0) throw failure("INCOMPLETE_SOURCE");
        // PGC envelope and identity checked above; share only the strict media validator.
        var envelope=JsonNodeFactory.instance.objectNode().put("code",0); envelope.set("data",info);
        return BilibiliVideoApiService.parseSource(envelope,descriptor,now);
    }
    private static boolean explicitFalse(JsonNode value) {
        return value.isBoolean() && !value.booleanValue() || value.isIntegralNumber() && value.longValue()==0;
    }
}
