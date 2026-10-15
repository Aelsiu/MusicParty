package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.resolver.DefaultAddressResolverGroup;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Resolution is restricted to the policy-checked XIFAN media origin. */
final class XifanMediaDnsResolver {
    interface Primary { List<InetAddress> lookup() throws UnknownHostException; }
    interface Backup { JsonNode query(); }
    private final Primary primary;
    private final Backup backup;
    private final LongSupplier clock;
    private record Answer(List<InetAddress> addresses,long ttlSeconds) {}
    private Answer cached;
    private long expires,cooldown;
    private String failedCode;
    private static final String HOST="play.xfvod.pro";
    // The CDN's mainland DNS route can terminate without A records. The fixed
    // public resolver hint selects its global answer; CDN IPs are never embedded.
    private static final URI BACKUP=URI.create("https://dns.alidns.com/resolve?name=play.xfvod.pro&type=A&edns_client_subnet=8.8.8.0%2F24");
    XifanMediaDnsResolver(){this(()->List.of(InetAddress.getAllByName(HOST)),XifanMediaDnsResolver::queryBackup,System::nanoTime);}
    XifanMediaDnsResolver(Primary primary,Backup backup,LongSupplier clock){this.primary=primary;this.backup=backup;this.clock=clock;}
    List<InetAddress> resolve(URI uri){
        XifanMediaUrlPolicy.validate(uri.toString());
        try{return checked(primary.lookup());}catch(UnknownHostException error){return fallback();}
    }
    private synchronized List<InetAddress> fallback(){
        long now=clock.getAsLong();if(cached!=null&&now<expires)return cached.addresses();
        if(failedCode!=null&&now<cooldown)throw failure(failedCode);
        try{
            var answer=parse(backup.query());cached=answer;
            expires=clock.getAsLong()+Math.min(300,answer.ttlSeconds())*1_000_000_000L;failedCode=null;
            return answer.addresses();
        }catch(RuntimeException error){
            failedCode=error instanceof org.thornex.musicparty.dto.VideoFavorites.Failure f?f.getCode():"XIFAN_UNAVAILABLE";
            cached=null;cooldown=clock.getAsLong()+5_000_000_000L;throw failure(failedCode);
        }
    }
    private static final class BackupClient {
        private static final WebClient CLIENT=WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE)
                .followRedirect(false).responseTimeout(Duration.ofSeconds(3)).option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,2000)))
            .codecs(codecs->codecs.defaultCodecs().maxInMemorySize(16*1024)).build();
    }
    private static JsonNode queryBackup(){return BackupClient.CLIENT.get().uri(BACKUP).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(3)).block();}
    private static Answer parse(JsonNode root){
        if(root==null||!root.path("Status").isIntegralNumber()||!root.path("Status").canConvertToInt()||root.path("Status").asInt(-1)!=0)throw failure("XIFAN_UNAVAILABLE");
        if(!root.path("TC").isBoolean()||root.path("TC").asBoolean())throw failure("MEDIA_ROUTE_UNSUPPORTED");
        JsonNode question=root.path("Question");if(question.isArray()){if(question.size()!=1)throw failure("MEDIA_ROUTE_UNSUPPORTED");question=question.get(0);}
        if(!question.path("name").isTextual()||!HOST.equals(name(question.path("name").asText()))
                ||!question.path("type").isIntegralNumber()||!question.path("type").canConvertToInt()||question.path("type").asInt()!=1)throw failure("MEDIA_ROUTE_UNSUPPORTED");
        JsonNode rows=root.path("Answer");if(!rows.isArray()||rows.isEmpty()||rows.size()>32)throw failure("XIFAN_UNAVAILABLE");
        Map<String,String> aliases=new HashMap<>();Map<String,List<InetAddress>> addresses=new HashMap<>();Map<String,Long> ttls=new HashMap<>();
        for(JsonNode row:rows){
            if(!row.path("name").isTextual()||!row.path("data").isTextual()||!row.path("type").isIntegralNumber()||!row.path("type").canConvertToInt()
                    ||!row.path("TTL").isIntegralNumber()||!row.path("TTL").canConvertToLong())throw failure("MEDIA_ROUTE_UNSUPPORTED");
            String owner=name(row.path("name").asText());long ttl=row.path("TTL").asLong();if(ttl<0||ttl>86400)throw failure("MEDIA_ROUTE_UNSUPPORTED");
            ttls.merge(owner,ttl,Math::min);
            if(row.path("type").asInt()==5){if(aliases.putIfAbsent(owner,name(row.path("data").asText()))!=null)throw failure("MEDIA_ROUTE_UNSUPPORTED");}
            else if(row.path("type").asInt()==1){addresses.computeIfAbsent(owner,key->new ArrayList<>()).add(ipv4(row.path("data").asText()));}
            else throw failure("MEDIA_ROUTE_UNSUPPORTED");
        }
        String current=HOST;Set<String> seen=new HashSet<>();long ttl=300;
        for(int depth=0;depth<8;depth++){
            if(!seen.add(current))throw failure("MEDIA_ROUTE_UNSUPPORTED");ttl=Math.min(ttl,ttls.getOrDefault(current,0L));
            var found=addresses.get(current);
            if(found!=null){
                if(aliases.containsKey(current)||!seen.containsAll(ttls.keySet()))throw failure("MEDIA_ROUTE_UNSUPPORTED");
                return new Answer(checked(new ArrayList<>(new LinkedHashSet<>(found))),ttl);
            }
            current=aliases.get(current);if(current==null)throw failure("XIFAN_UNAVAILABLE");
        }
        throw failure("MEDIA_ROUTE_UNSUPPORTED");
    }
    private static String name(String value){
        String result=value.toLowerCase(Locale.ROOT);if(result.endsWith("."))result=result.substring(0,result.length()-1);
        if(result.length()>253||result.isEmpty())throw failure("MEDIA_ROUTE_UNSUPPORTED");
        for(String label:result.split("\\.",-1))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw failure("MEDIA_ROUTE_UNSUPPORTED");
        return result;
    }
    private static InetAddress ipv4(String value){
        String[] parts=value.split("\\.",-1);if(parts.length!=4)throw failure("MEDIA_ROUTE_UNSUPPORTED");byte[] bytes=new byte[4];
        for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))throw failure("MEDIA_ROUTE_UNSUPPORTED");int part=Integer.parseInt(parts[i]);if(part>255)throw failure("MEDIA_ROUTE_UNSUPPORTED");bytes[i]=(byte)part;}
        try{var address=InetAddress.getByAddress(bytes);if(!XifanMediaUrlPolicy.publicAddress(address))throw failure("MEDIA_ROUTE_UNSUPPORTED");return address;}catch(UnknownHostException impossible){throw failure("MEDIA_ROUTE_UNSUPPORTED");}
    }
    private static List<InetAddress> checked(List<InetAddress> addresses){
        if(addresses.isEmpty() || addresses.size()>8 || addresses.stream().anyMatch(ip->!XifanMediaUrlPolicy.publicAddress(ip)))throw failure("MEDIA_ROUTE_UNSUPPORTED");
        return List.copyOf(addresses);
    }
}
