package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.*;
import org.thornex.musicparty.dto.*;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Extracts known RSC JSON without executing page scripts or depending on chunk names. */
public final class XifanDetailParser {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String RSC_PUSH="self.__next_f.push([1,";
    public XifanVideo.Detail parse(String html,long expectedId) {
        if(html==null || html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2*1024*1024) throw failure("XIFAN_UNAVAILABLE");
        List<String> payloads=new ArrayList<>();
        // Java's recursive regex repetition over long RSC strings can overflow on the HTTP event loop.
        // Scan only the JSON string boundary; Jackson still validates and decodes every escape.
        try {int offset=0;while(true){
            int push=html.indexOf(RSC_PUSH,offset);if(push<0)break;
            int start=push+RSC_PUSH.length();offset=start;
            if(start>=html.length() || html.charAt(start)!='"')continue;
            int end=endString(html,start);offset=end;
            if(html.startsWith("])",end))payloads.add(JSON.readValue(html.substring(start,end),String.class));
        }}catch(Exception invalid){throw failure("XIFAN_UNAVAILABLE");}
        if(!payloads.isEmpty())payloads.addFirst(String.join("",payloads));
        payloads.add(html);
        for(String payload:payloads){int start=payload.indexOf("{\"anime\":");if(start<0)continue;
            try {JsonNode root=JSON.readTree(payload.substring(start,endObject(payload,start)));return parse(root,expectedId);}
            catch(VideoFavorites.Failure invalid){throw invalid;}catch(Exception invalid){throw failure("XIFAN_UNAVAILABLE");}
        }
        throw failure("XIFAN_UNAVAILABLE");
    }
    private static int endString(String value,int start){
        boolean escape=false;
        for(int i=start+1;i<value.length();i++){
            char c=value.charAt(i);
            if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')return i+1;
        }
        throw failure("XIFAN_UNAVAILABLE");
    }
    private static int endObject(String value,int start){int depth=0;boolean string=false,escape=false;for(int i=start;i<value.length();i++){char c=value.charAt(i);if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')string=false;}else if(c=='"')string=true;else if(c=='{')depth++;else if(c=='}' && --depth==0)return i+1;}throw failure("XIFAN_UNAVAILABLE");}
    public XifanVideo.Detail parse(JsonNode root,long expectedId){
        JsonNode anime=root.path("anime"),sources=root.path("sources");
        if(id(anime.path("id"))!=expectedId || !sources.isArray() || sources.size()>16)throw failure("XIFAN_UNAVAILABLE");
        var item=new XifanVideo.Anime(expectedId,string(anime,"title"),safeCover(anime.path("cover_url").asText()),anime.path("release_year").isInt()?anime.path("release_year").asInt():null,anime.path("is_finished").asBoolean(),anime.path("current_episodes").asInt());
        List<XifanVideo.Source> routes=new ArrayList<>();Set<Long> ids=new HashSet<>();int count=0;
        for(JsonNode source:sources){long sourceId=id(source.path("id"));if(!ids.add(sourceId))throw failure("XIFAN_UNAVAILABLE");
            String code=string(source,"code"),name=string(source,"name");if(!code.matches("[A-Za-z0-9_-]{1,64}"))throw failure("XIFAN_UNAVAILABLE");
            JsonNode episodes=source.path("episodes");if(!episodes.isArray())throw failure("XIFAN_UNAVAILABLE");List<XifanVideo.Episode> rows=new ArrayList<>();Set<Long> epIds=new HashSet<>();
            for(JsonNode ep:episodes){if(++count>1000)throw failure("XIFAN_UNAVAILABLE");long epId=id(ep.path("id"));if(!epIds.add(epId))throw failure("XIFAN_UNAVAILABLE");double number=ep.path("episode_number").asDouble(Double.NaN);if(!Double.isFinite(number) || number<0)throw failure("XIFAN_UNAVAILABLE");
                var title=ep.get("title");String label=title==null || title.isNull() || title.isTextual()&&title.asText().isBlank()
                    ?"第"+java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()+"集":string(ep,"title");
                rows.add(new XifanVideo.Episode(epId,label,string(ep,"kind"),number));}
            rows.sort(Comparator.comparing(XifanVideo.Episode::kind).thenComparingDouble(XifanVideo.Episode::episodeNumber).thenComparingLong(XifanVideo.Episode::id));
            routes.add(new XifanVideo.Source(sourceId,code,name,rows,"UNVERIFIED"));
        }
        // Observed route is a probe priority only, never a format or availability claim.
        routes.sort(Comparator.comparingInt(s->"AL".equals(s.code())?0:1));return new XifanVideo.Detail(item,routes);
    }
    static long id(JsonNode node){if(!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong()<1 || node.asLong()>VideoIdentity.MAX_SAFE_ID)throw failure("XIFAN_UNAVAILABLE");return node.asLong();}
    static String string(JsonNode node,String field){var value=node.path(field);if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>4096)throw failure("XIFAN_UNAVAILABLE");return value.asText();}
    static String safeCover(String value){try{var uri=java.net.URI.create(value);return "https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null?value:null;}catch(Exception ignored){return null;}}
}
